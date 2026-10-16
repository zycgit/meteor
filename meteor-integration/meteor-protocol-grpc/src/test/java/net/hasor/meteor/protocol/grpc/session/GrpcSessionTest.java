/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.GrpcProtocolTestAccess;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcSessionTest {
    public interface Echo {
        String echo(String request);
    }

    @Test
    public void inboundTimeoutWinsBeforeItsQueuedCleanupRuns() throws Exception {
        // 入站请求已经分发，completion 清理暂存在连接有序队列中。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            GrpcHttpCodec codec = new GrpcHttpCodec(this.config(context), context);
            RequestPayload wireRequest = this.request(service);
            HttpExchange exchange = new HttpExchange(codec.encode(wireRequest));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 业务结果竞争到达时必须保留原超时，pending 留给已排队清理回送状态。
            MetTimeoutException failure = new MetTimeoutException("business deadline");
            request.complete(failure);
            Future<Void> late = session.send(this.response(request.getRequestID()));
            assertSame(failure, late.getCause());
            assertTrue(network.writes.isEmpty());

            // 推进清理后只产生一条超时响应，迟到成功没有占用交换。
            network.runTasks();
            assertEquals(List.of(exchange), network.writes);
            HttpResponse response = exchange.response().getResult();
            assertEquals("4", response.header("grpc-status"));
            assertTrue(response.trailers().isEmpty());
            session.closed(new IOException("test complete"));
        }
    }

    @Test
    public void queuedSuccessfulResponseIsIgnoredAfterRpcCancellation() throws Exception {
        // 出站请求收到有效 HTTP 响应，但协议回调仍暂存在连接有序队列中。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.request(service);
            session.send(request);
            HttpExchange exchange = network.writes.get(0);
            GrpcHttpCodec codec = new GrpcHttpCodec(this.config(context), context);
            assertTrue(exchange.respond(codec.encode(request, this.response(request.getRequestID()))));
            assertTrue(received.isEmpty());

            // Caller 先取消原请求，恢复回调后不能发布迟到 Response 或 Throw。
            CancellationException failure = new CancellationException("caller cancelled");
            request.complete(failure);
            network.runTasks();
            assertTrue(received.isEmpty());
            assertSame(failure, request.completion().getCause());
            session.closed(new IOException("test complete"));
        }
    }

    @Test
    public void queuedTransportFailureIsPublishedAfterSessionClose() throws Exception {
        // HTTP 交换先失败，其 gRPC 回调仍在连接有序队列中。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.request(service);
            session.send(request);
            HttpExchange exchange = network.writes.get(0);
            IOException transportFailure = new IOException("bad certificate");
            exchange.complete(transportFailure);

            // 会话关闭先于排队回调执行，原始传输失败仍必须发布给 Caller。
            session.closed(new IOException("channel closed"));
            assertTrue(received.isEmpty());
            network.runTasks();
            assertEquals(1, received.size());
            ThrowPayload failure = (ThrowPayload) received.get(0);
            assertSame(transportFailure, failure.getThrowable());
            assertSame(transportFailure, request.completion().getCause());
        }
    }

    @Test
    public void earlyStreamingRejectionFailsTheUnreadRequestBody() throws Exception {
        // 使用 HTTP/1 流式请求触发 gRPC 会话的早期版本拒绝。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new ArrayList<>(), false);
            session.connected();
            HttpBodyStream body = new HttpBodyStream(32);
            HttpRequest request = HttpRequest.newStreaming( //
                    "POST", "/grpc/example.Echo/echo", Map.of("content-type", List.of("application/grpc")), body, 1);

            // 拒绝响应物理完成后才结束未消费正文，不能提前把 HTTP/2 stream 重置为 CANCEL。
            HttpExchange exchange = new HttpExchange(request);
            session.receive(exchange);
            assertEquals(1, network.writes.size());
            exchange.complete(null);
            assertTrue(body.completion().getCause() instanceof IOException);
            session.closed(new IOException("test complete"));
        }
    }

    @Test
    public void occupiedInboundExchangeRejectsTheBusinessResponseWithoutWriting() throws Exception {
        // 入站请求仍在等待业务结果，但 HTTP 交换已经被其他响应占用。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            GrpcHttpCodec codec = new GrpcHttpCodec(this.config(context), context);
            HttpExchange exchange = new HttpExchange(codec.encode(this.request(service)));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            HttpResponse occupied = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());
            assertTrue(exchange.respond(occupied));

            // 协议不能把已完成响应的交换再次提交给传输层。
            Future<Void> sent = session.send(this.response(request.getRequestID()));
            assertTrue(sent.getCause() instanceof IllegalStateException);
            assertTrue(network.writes.isEmpty());
            session.closed(new IOException("test complete"));
        }
    }

    @Test
    public void closedSessionRejectsNewOutboundRequests() throws Exception {
        // 关闭会话后准备一条新的出站请求。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection closed");
            session.closed(failure);
            RequestPayload request = this.request(service);

            // 新工作继承会话关闭原因，不创建 HTTP 交换或 worker 任务。
            Future<Void> sent = session.send(request);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
            assertTrue(network.tasks.isEmpty());
        }
    }

    @Test
    public void closedSessionDiscardsAUnaryBodyWorkerReturn() throws Exception {
        // 普通入站调用使用增量正文，使读取 worker 在等待正文结束时关闭会话。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpRequest buffered = new GrpcHttpCodec(this.config(context), context).encode(this.request(service));
            HttpBodyStream body = new HttpBodyStream(64);
            body.offer(buffered.body(), () -> {
            });
            HttpRequest streaming = HttpRequest.newStreaming(//
                    buffered.method(), buffered.uri(), buffered.headers(), body, 2);
            HttpExchange exchange = new HttpExchange(streaming);
            session.receive(exchange);

            // close 终止阻塞正文；worker 回到连接队列后只能清理交换，不能重新投递请求。
            IOException failure = new IOException("connection closed");
            session.closed(failure);
            assertTrue(network.awaitTask());
            network.runTasks();
            assertTrue(received.isEmpty());
            assertTrue(network.writes.isEmpty());
            assertSame(failure, exchange.completion().getCause());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").protocol("grpc").contextPath("/grpc");
        return configuration;
    }

    private ProtocolConfig config(MetContext context) {
        return context.getSettings().getConnectorConfigs().iterator().next().protocol("grpc");
    }

    private ProtocolSession<HttpExchange> session(MetContext context, ControlledNetwork network, //
            List<Payload> received, boolean outbound) {
        ProtocolSessionContext sessionContext = GrpcProtocolTestAccess.context(context, (action, delay) -> {
            throw new AssertionError("Unary lifecycle tests must use RPC deadlines");
        }, network.getLocal(), outbound);
        return new GrpcProtocol().createSession( //
                this.config(context), sessionContext, network, (payload, id) -> received.add(payload));
    }

    private RequestPayload request(MetBindInfo<?> service) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(101);
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.setSerializeType("Json");
        request.setClientTimeout(5000);
        request.addParameter(String.class.getName(), "request");
        return request;
    }

    private ResponsePayload response(long requestId) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(requestId);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnType(String.class.getName());
        response.setReturnData("result");
        return response;
    }

    private static final class ControlledNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes = new ArrayList<>();
        private final Queue<Runnable>    tasks  = new ConcurrentLinkedQueue<>();
        private final CountDownLatch     queued = new CountDownLatch(1);

        public InterAddress getLocal() {
            return new InterAddress("grpc", "127.0.0.1", 2181, "default");
        }

        public InterAddress getRemote() {
            return this.getLocal();
        }

        public boolean isOpen() {
            return true;
        }

        public Future<Void> write(HttpExchange exchange) {
            this.writes.add(exchange);
            return new BasicFuture<>((Void) null);
        }

        public void execute(Runnable task) {
            this.tasks.add(task);
            this.queued.countDown();
        }

        private boolean awaitTask() throws InterruptedException {
            return this.queued.await(2, TimeUnit.SECONDS);
        }

        private void runTasks() {
            while (!this.tasks.isEmpty()) {
                this.tasks.remove().run();
            }
        }

        public Future<Void> close() {
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            return new BasicFuture<>((Void) null);
        }
    }
}

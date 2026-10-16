/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.GrpcWebProtocol;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebSessionTest {
    @Test
    public void connectedSessionBecomesReady() throws Exception {
        // Web 会话沿用连接就绪事件，不创建额外握手或请求定时器。
        try (MetContext context = this.configuration().buildContext()) {
            ObservedNetwork network = new ObservedNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new Received(), true);
            try {
                assertFalse(session.ready().isDone());
                session.connected();
                session.ready().get(2, TimeUnit.SECONDS);
            } finally {
                session.closed(new IOException("test complete"));
            }
        }
    }

    @Test
    public void closedSessionDoesNotCloseBorrowedChannel() throws Exception {
        // 创建者负责物理连接，协议会话只释放自己的挂载引用和交换。
        try (MetContext context = this.configuration().buildContext()) {
            ObservedNetwork network = new ObservedNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new Received(), true);
            IOException failure = new IOException("owner closing");
            session.closed(failure);
            session.closed(failure);

            // 重复关闭保持幂等，并保留首次关闭原因。
            assertSame(failure, session.ready().getCause());
            assertEquals(0, network.closed);
        }
    }

    @Test
    public void ordinaryRequestReachesNativeDispatcherPayload() throws Exception {
        // 注册一个只有 grpc-web 别名的提供者，避免误用原生 grpc 名称空间。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();
            ObservedNetwork network = new ObservedNetwork();
            Received received = new Received();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            try {
                session.connected();
                session.receive(new HttpExchange(this.webRequest(context, this.request(service))));

                // 适配完成后仍交付既有 RequestPayload，而非新增调用模型。
                assertTrue(received.arrived.await(3, TimeUnit.SECONDS));
                RequestPayload request = (RequestPayload) received.payloads.remove();
                assertEquals(List.of("request"), request.getParameterValues());
                assertEquals(service.getBindName(), request.getServiceName());
                assertTrue(request.getRequestID() > 0);
            } finally {
                session.closed(new IOException("test complete"));
            }
        }
    }

    @Test
    public void outboundWebResponseReachesNativeCallerPayload() throws Exception {
        // 消费者通过 Web 工厂发送请求，参数编码仍复用 native gRPC。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();
            ObservedNetwork network = new ObservedNetwork();
            Received received = new Received();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            try {
                session.connected();
                RequestPayload request = this.request(service);
                session.send(request).get(2, TimeUnit.SECONDS);
                assertTrue(network.written.await(3, TimeUnit.SECONDS));
                HttpExchange wire = network.writes.remove();
                wire.respond(this.webResponse(context, request));

                // 结果完成原始请求，并保持同一个请求 ID。
                assertTrue(received.arrived.await(3, TimeUnit.SECONDS));
                ResponsePayload response = (ResponsePayload) received.payloads.remove();
                assertEquals(101, response.getRequestID());
                assertEquals("result", response.getReturnData());
                request.completion().get(2, TimeUnit.SECONDS);
            } finally {
                session.closed(new IOException("test complete"));
            }
        }
    }

    @Test
    public void dispatcherReplyReturnsToTheSameInboundExchange() throws Exception {
        // 入站完成参数绑定后等待 Dispatcher 的业务结果。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();
            ObservedNetwork network = new ObservedNetwork();
            Received received = new Received();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            try {
                session.connected();
                HttpExchange exchange = new HttpExchange(this.webRequest(context, this.request(service)));
                session.receive(exchange);
                assertTrue(received.arrived.await(3, TimeUnit.SECONDS));
                RequestPayload request = (RequestPayload) received.payloads.remove();

                // 业务响应经 native 编码后回到原 Web 交换。
                session.send(this.response(request.getRequestID())).get(3, TimeUnit.SECONDS);
                assertTrue(network.written.await(3, TimeUnit.SECONDS));
                assertSame(exchange, network.writes.remove());
                assertEquals(200, exchange.response().getResult().status());
            } finally {
                session.closed(new IOException("test complete"));
            }
        }
    }

    @Test
    public void closedSessionRejectsNewRequests() throws Exception {
        // Web 包装层关闭后不得绕过 native 的生命周期判断。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();
            ObservedNetwork network = new ObservedNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new Received(), true);
            IOException failure = new IOException("session closed");
            session.closed(failure);
            RequestPayload request = this.request(service);

            // 请求与发送 Future 同时收到原始关闭原因，物理连接没有写入。
            Future<Void> sent = session.send(request);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void manuallyMarkedMessageRequestIsRejectedBeforeWrite() throws Exception {
        // 手工请求也必须遵守 Web 协议需要响应的约束，不能只依赖发布校验。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();
            ObservedNetwork network = new ObservedNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new Received(), true);
            try {
                session.connected();
                RequestPayload request = this.request(service);
                request.setMessage(true);

                // 不支持的消息请求在写入 HTTP 通道之前明确失败。
                Future<Void> sent = session.send(request);
                assertTrue(sent.isDone());
                assertTrue(sent.getCause() instanceof GrpcStatusException);
                assertEquals(12, ((GrpcStatusException) sent.getCause()).getCode());
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(new IOException("test complete"));
            }
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").protocol("grpc-web").contextPath("/web");
        return configuration;
    }

    private ProtocolConfig config(MetContext context) {
        return context.getSettings().getConnectorConfigs().iterator().next().protocol("grpc-web");
    }

    private ProtocolSession<HttpExchange> session(MetContext context, ObservedNetwork network, //
            Received received, boolean outbound) {
        ProtocolSessionContext sessionContext = GrpcProtocolTestAccess.context(context, (task, delay) -> {
            throw new AssertionError("Web adapter must use RPC deadlines");
        }, network.getLocal(), outbound);
        return new GrpcWebProtocol().createSession(this.config(context), sessionContext, network, received::accept);
    }

    private HttpRequest webRequest(MetContext context, RequestPayload payload) throws Exception {
        ProtocolConfig config = this.config(context);
        HttpRequest request = new GrpcHttpCodec(config, context).encode(payload);
        byte[] body = new GrpcWebCodec(config).encodeRequest(request.body(), false);
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc-web+json"));
        return HttpRequest.newBuffered(request.method(), request.uri(), headers, body, 1, Map.of());
    }

    private HttpResponse webResponse(MetContext context, RequestPayload request) throws Exception {
        ProtocolConfig config = this.config(context);
        HttpResponse nativeResponse = new GrpcHttpCodec(config, context).encode(request, this.response(101));
        byte[] body = new GrpcWebCodec(config).encodeResponse( //
                nativeResponse.body(), nativeResponse.trailers(), false);
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc-web+json"));
        return HttpResponse.newBuffered(200, headers, body, Map.of());
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

    public interface Echo {
        String echo(String request);
    }

    private static final class Received {
        private final Queue<Payload> payloads = new ConcurrentLinkedQueue<>();
        private final CountDownLatch arrived  = new CountDownLatch(1);

        private void accept(Payload payload, long requestId) {
            this.payloads.add(payload);
            this.arrived.countDown();
        }
    }

    private static final class ObservedNetwork implements NetworkChannel<HttpExchange> {
        private final Queue<HttpExchange> writes  = new ConcurrentLinkedQueue<>();
        private final CountDownLatch      written = new CountDownLatch(1);
        private       int                 closed;

        public InterAddress getLocal() {
            return new InterAddress("grpc-web", "127.0.0.1", 2181, "default");
        }

        public InterAddress getRemote() {
            return this.getLocal();
        }

        public boolean isOpen() {
            return this.closed == 0;
        }

        public Future<Void> write(HttpExchange exchange) {
            this.writes.add(exchange);
            this.written.countDown();
            return new BasicFuture<>((Void) null);
        }

        public void execute(Runnable command) {
            command.run();
        }

        public Future<Void> close() {
            this.closed++;
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}

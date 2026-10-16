/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolSessionContextTestAccess;
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
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void connectedSessionBecomesReadyWithoutHandshake() throws Exception {
        // HTTP 连接已经建立，Connect 就绪不再发送专属握手报文。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            try {
                assertFalse(session.ready().isDone());
                session.connected();
                session.ready().get(1, TimeUnit.SECONDS);
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void connectionFailureEndsReadyWithoutClosingBorrowedChannel() throws Exception {
        // 会话记录连接失败，底层连接的关闭仍由其所有者负责。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection refused");

            session.closed(failure);
            assertSame(failure, session.ready().getCause());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void closedSessionRejectsNewOutgoingRequest() throws Exception {
        // 已关闭会话不再创建交换，也不再提交工作线程任务。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("session closed");
            session.closed(failure);
            RequestPayload request = request(1);

            Future<Void> sent = session.send(request);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
            assertTrue(network.tasks.isEmpty());
        }
    }

    @Test
    public void closedSessionTerminatesNewIncomingBody() throws Exception {
        // 已关闭会话收到增量正文时，立即释放正文和 HTTP 交换。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            IOException failure = new IOException("session closed");
            session.closed(failure);
            HttpBodyStream body = new HttpBodyStream(64);
            HttpRequest request = HttpRequest.newStreaming( //
                    "POST", "/connect/example.Echo/echo", headers(), body, 1);
            HttpExchange exchange = new HttpExchange(request);

            session.receive(exchange);
            assertSame(failure, exchange.completion().getCause());
            assertSame(failure, body.completion().getCause());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void outgoingWriteFailureEndsRequestAndExchange() throws Exception {
        // 写入失败结束发送者和调用，Caller 从发送 Future 获取错误。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            try {
                RequestPayload request = request(2);
                Future<Void> sent = session.send(request);
                IOException failure = new IOException("physical write failed");

                network.writeResult.failed(failure);
                network.runTasks();
                assertSame(failure, sent.getCause());
                assertSame(failure, request.completion().getCause());
                assertSame(failure, network.writes.get(0).completion().getCause());
                // 发送 Future 已报告失败，不再重复投递 ThrowPayload。
                assertTrue(received.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void cancellingSendReleasesRequestAndPhysicalWrite() throws Exception {
        // 用户取消尚未完成的发送，当前交换与物理写入同步结束。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            network.writeResult = new BasicFuture<>();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            try {
                RequestPayload request = request(3);
                Future<Void> sent = session.send(request);

                assertTrue(sent.cancel());
                network.runTasks();
                assertTrue(network.writeResult.isCancelled());
                assertTrue(request.completion().getCause() instanceof CancellationException);
                assertTrue(network.writes.get(0).completion().isDone());
                assertEquals(0, network.closes);
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void closingSessionEndsOutstandingOutgoingCall() throws Exception {
        // 出站写入已完成，但业务结果仍未到达。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = request(4);
            session.send(request).get(1, TimeUnit.SECONDS);
            IOException failure = new IOException("connection closed while waiting");

            // 关闭会话即解除 Caller 等待，并保留具体关闭原因。
            session.closed(failure);
            network.runTasks();
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void responseBeforeWriteFailureIsDeliveredOnlyOnce() throws Exception {
        // 响应先到，物理写入 Future 仍暂不完成。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            try {
                RequestPayload request = request(5);
                Future<Void> sent = session.send(request);
                HttpResponse response = codec(context).encode(request, response(5));
                network.writes.get(0).respond(response);
                network.runTasks();
                assertFalse(sent.isDone());

                // 后到的写失败只结束发送者，不再次发布失败覆盖已经交付的结果。
                IOException failure = new IOException("late write failure");
                network.writeResult.failed(failure);
                network.runTasks();
                assertSame(failure, sent.getCause());
                assertEquals(1, received.size());
                assertEquals("result", ((ResponsePayload) received.get(0)).getReturnData());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void inboundTimeoutWinsBeforeQueuedCleanup() throws Exception {
        // 入站调用已经分发，超时清理被暂存在连接的有序队列中。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            try {
                HttpExchange exchange = new HttpExchange(codec(context).encode(request(6)));
                session.receive(exchange);
                RequestPayload request = (RequestPayload) received.get(0);
                MetTimeoutException failure = new MetTimeoutException("business deadline");
                request.complete(failure);

                // 清理尚未执行时到达的成功结果仍须尊重原来的超时状态。
                Future<Void> late = session.send(response(request.getRequestID()));
                assertSame(failure, late.getCause());
                assertTrue(network.writes.isEmpty());
                network.runTasks();
                HttpResponse response = exchange.response().getResult();
                assertEquals(504, response.status());
                String body = new String(response.body(), StandardCharsets.UTF_8);
                assertTrue(body.contains("deadline_exceeded"));
                assertEquals(List.of(exchange), network.writes);
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void queuedResponseIsIgnoredAfterCallerCancellation() throws Exception {
        // 响应已到达交换，但协议回调尚未在连接队列中执行。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            try {
                RequestPayload request = request(7);
                session.send(request).get(1, TimeUnit.SECONDS);
                HttpResponse response = codec(context).encode(request, response(7));
                network.writes.get(0).respond(response);
                assertTrue(received.isEmpty());
                CancellationException failure = new CancellationException("caller cancelled");

                request.complete(failure);
                network.runTasks();
                assertSame(failure, request.completion().getCause());
                assertTrue(received.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void queuedTransportFailurePreservesCauseDuringClose() throws Exception {
        // 传输交换先失败，随后收到会话关闭通知，错误回调仍在队列中。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = request(8);
            session.send(request).get(1, TimeUnit.SECONDS);
            IOException transportFailure = new IOException("bad certificate");
            network.writes.get(0).complete(transportFailure);

            // Caller 应收到传输的具体原因，不能被后续笼统的关闭原因覆盖。
            session.closed(new IOException("channel closed"));
            network.runTasks();
            assertEquals(1, received.size());
            assertSame(transportFailure, ((ThrowPayload) received.get(0)).getThrowable());
            assertSame(transportFailure, request.completion().getCause());
        }
    }

    @Test
    public void rejectedStreamingBodyWaitsForErrorResponseWrite() throws Exception {
        // 无法识别的媒体类型在读取增量正文之前拒绝。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            try {
                HttpBodyStream body = new HttpBodyStream(64);
                Map<String, List<String>> headers = Map.of("content-type", List.of("text/plain"));
                HttpRequest request = HttpRequest.newStreaming( //
                        "POST", "/connect/example.Echo/echo", headers, body, 2);
                HttpExchange exchange = new HttpExchange(request);

                // 错误响应尚未写完时不提前中止正文，避免抢先重置 HTTP/2 stream。
                session.receive(exchange);
                assertEquals(List.of(exchange), network.writes);
                assertFalse(body.completion().isDone());
                exchange.complete(null);
                assertNotNull(body.completion().getCause());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void occupiedExchangeDoesNotReceiveSecondResponse() throws Exception {
        // 业务尚未回答时，原 HTTP 交换已经被其他响应占用。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            try {
                HttpExchange exchange = new HttpExchange(codec(context).encode(request(9)));
                session.receive(exchange);
                RequestPayload request = (RequestPayload) received.get(0);
                HttpResponse occupied = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());
                assertTrue(exchange.respond(occupied));

                Future<Void> sent = session.send(response(request.getRequestID()));
                assertTrue(sent.getCause() instanceof IllegalStateException);
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void acceptedInvocationDoesNotWriteBusinessResponse() throws Exception {
        // Dispatcher 的入队回执不能成为普通 Connect 调用的最终结果。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            try {
                HttpExchange exchange = new HttpExchange(codec(context).encode(request(10)));
                session.receive(exchange);
                RequestPayload request = (RequestPayload) received.get(0);
                ResponsePayload accepted = response(request.getRequestID());
                accepted.setStatus(ProtocolStatus.Accept);

                session.send(accepted).get(1, TimeUnit.SECONDS);
                assertFalse(exchange.response().isDone());
                assertFalse(request.completion().isDone());
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void incomingResponseFutureWaitsForPhysicalWrite() throws Exception {
        // 业务已回答，但物理响应尚未发送完毕。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            try {
                HttpExchange exchange = new HttpExchange(codec(context).encode(request(11)));
                session.receive(exchange);
                RequestPayload request = (RequestPayload) received.get(0);
                Future<Void> sent = session.send(response(request.getRequestID()));
                assertFalse(sent.isDone());

                network.writeResult.completed(null);
                sent.get(1, TimeUnit.SECONDS);
                assertEquals(List.of(exchange), network.writes);
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void closedSessionDiscardsPendingUnaryBodyWorkerResult() throws Exception {
        // 普通请求的增量正文未到 EOF，读取 worker 正在等待后续正文。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpRequest buffered = codec(context).encode(request(12));
            HttpBodyStream body = new HttpBodyStream(64);
            body.offer(buffered.body(), () -> {
            });
            HttpRequest input = HttpRequest.newStreaming( //
                    buffered.method(), buffered.uri(), buffered.headers(), body, buffered.version());
            HttpExchange exchange = new HttpExchange(input);
            session.receive(exchange);

            // 关闭解除正文等待，回到连接队列的 worker 只能清理，不能重新投递业务。
            IOException failure = new IOException("closed during body read");
            session.closed(failure);
            assertTrue(network.awaitTask());
            network.runTasks();
            assertTrue(received.isEmpty());
            assertTrue(network.writes.isEmpty());
            assertSame(failure, exchange.completion().getCause());
        }
    }

    @Test
    public void incompleteUnaryBodyExpiresBeforeDispatch() throws Exception {
        // 请求已经包含完整 JSON，但 HTTP 正文没有 EOF，尚不能交给 Caller。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            AtomicReference<Runnable> deadline = new AtomicReference<>();
            ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext(context, (action, delay) -> {
                deadline.set(action);
                return () -> true;
            }, network.getLocal(), false);
            ConnectMountRuntime mount = new ConnectMountRuntime(config()).retain(context);
            ConnectSession session = new ConnectSession( //
                    runtime, network, (payload, id) -> received.add(payload), mount);
            try {
                HttpRequest buffered = codec(context).encode(request(16));
                HttpBodyStream body = new HttpBodyStream(64);
                body.offer(buffered.body(), () -> {
                });
                HttpRequest input = HttpRequest.newStreaming( //
                        buffered.method(), buffered.uri(), buffered.headers(), body, buffered.version());
                HttpExchange exchange = new HttpExchange(input);
                session.receive(exchange);
                assertNotNull(deadline.get());
                assertTrue(received.isEmpty());

                // 读取阶段的期限先返回协议错误，响应写完之前保留未读正文。
                deadline.get().run();
                network.runTasks();
                HttpResponse response = exchange.response().getResult();
                assertEquals(504, response.status());
                String error = new String(response.body(), StandardCharsets.UTF_8);
                assertTrue(error.contains("deadline_exceeded"));
                assertFalse(body.completion().isDone());
                assertEquals(List.of(exchange), network.writes);
                exchange.complete(null);
                assertNotNull(body.completion().getCause());
                assertTrue(received.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void completedUnaryBodyHandsDeadlineOwnershipToCaller() throws Exception {
        // 增量正文已结束，读取阶段只在正式分发前短暂拥有期限句柄。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            AtomicReference<Runnable> deadline = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext(context, (action, delay) -> {
                deadline.set(action);
                return () -> cancelled.compareAndSet(false, true);
            }, network.getLocal(), false);
            ConnectMountRuntime mount = new ConnectMountRuntime(config()).retain(context);
            ConnectSession session = new ConnectSession( //
                    runtime, network, (payload, id) -> received.add(payload), mount);
            try {
                HttpRequest buffered = codec(context).encode(request(17));
                HttpBodyStream body = new HttpBodyStream(64);
                body.offer(buffered.body(), () -> {
                });
                body.finish(Map.of());
                HttpRequest input = HttpRequest.newStreaming( //
                        buffered.method(), buffered.uri(), buffered.headers(), body, buffered.version());
                HttpExchange exchange = new HttpExchange(input);
                session.receive(exchange);
                assertTrue(network.awaitTask());
                network.runTasks();

                // 已交付调用只由 Caller 管理期限，迟到的读取回调不能终止它。
                assertEquals(1, received.size());
                assertTrue(cancelled.get());
                assertNotNull(deadline.get());
                deadline.get().run();
                network.runTasks();
                assertFalse(exchange.response().isDone());
                RequestPayload request = (RequestPayload) received.get(0);
                assertFalse(request.completion().isDone());
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void completedRequestIsNotSentAgain() throws Exception {
        // 已取消调用不能在会话层重新建立网络交换。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            try {
                RequestPayload request = request(13);
                request.complete(new CancellationException("already cancelled"));

                Future<Void> sent = session.send(request);
                assertTrue(sent.getCause() instanceof CancellationException);
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void messageRequestIsRejectedBeforeWrite() throws Exception {
        // Connect 没有消息模式，不为它伪造普通调用或本地回执。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            try {
                RequestPayload request = request(14);
                request.setMessage(true);

                Future<Void> sent = session.send(request);
                assertNotNull(sent.getCause());
                assertTrue(network.writes.isEmpty());
            } finally {
                session.closed(null);
            }
        }
    }

    @Test
    public void drainAnswersOutstandingUnaryInvocation() throws Exception {
        // 排空前还有未返回的普通调用，需先填补 HTTP 响应位置。
        try (MetContext context = context()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            try {
                HttpExchange exchange = new HttpExchange(codec(context).encode(request(15)));
                session.receive(exchange);

                session.prepareDrain();
                assertEquals(List.of(exchange), network.writes);
                assertTrue(exchange.response().isDone());
                assertTrue(exchange.response().getResult().status() >= 400);
                assertEquals(0, network.closes);
            } finally {
                session.closed(null);
            }
        }
    }

    private static MetContext context() throws Exception {
        MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext();
        context.publisher().rsfService(Echo.class, value -> value) //
                .name("Echo") //
                .aliasName("connect", "example.Echo") //
                .serialize("Json") //
                .register();
        return context;
    }

    private static ProtocolConfig config() {
        return ProtocolConfig.newConfig("connect", "connect", Map.of("contextPath", "/connect"));
    }

    private static ConnectHttpCodec codec(MetContext context) {
        return new ConnectHttpCodec(config(), context);
    }

    private static ProtocolSession<HttpExchange> session(MetContext context, ControlledNetwork network, //
            List<Payload> received, boolean outbound) {
        // 增量正文分发前允许注册读取期限，分发后的 RPC 期限仍由 Caller 管理。
        ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext(context, (action, delay) -> {
            return () -> true;
        }, network.getLocal(), outbound);
        ConnectMountRuntime mount = new ConnectMountRuntime(config());
        return new ConnectSession(runtime, network, (payload, id) -> received.add(payload), mount.retain(context));
    }

    private static RequestPayload request(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.setSerializeType("Json");
        request.setClientTimeout(5000);
        request.addParameter(String.class.getName(), "request");
        return request;
    }

    private static ResponsePayload response(long id) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData("result");
        response.setReturnType(String.class.getName());
        return response;
    }

    private static Map<String, List<String>> headers() {
        return Map.of("content-type", List.of("application/json"), "connect-protocol-version", List.of("1"));
    }

    private static final class ControlledNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private final Queue<Runnable>    tasks       = new ConcurrentLinkedQueue<>();
        private final CountDownLatch     queued      = new CountDownLatch(1);
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);
        private       int                closes;

        @Override
        public InterAddress getLocal() {
            return new InterAddress("connect", "127.0.0.1", 8080, "default");
        }

        @Override
        public InterAddress getRemote() {
            return this.getLocal();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Future<Void> write(HttpExchange exchange) {
            this.writes.add(exchange);
            return this.writeResult;
        }

        @Override
        public void execute(Runnable task) {
            this.tasks.add(task);
            this.queued.countDown();
        }

        private boolean awaitTask() throws InterruptedException {
            return this.queued.await(2, TimeUnit.SECONDS);
        }

        private void runTasks() {
            Runnable task;
            while ((task = this.tasks.poll()) != null) {
                task.run();
            }
        }

        @Override
        public Future<Void> close() {
            this.closes++;
            return new BasicFuture<>((Void) null);
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}

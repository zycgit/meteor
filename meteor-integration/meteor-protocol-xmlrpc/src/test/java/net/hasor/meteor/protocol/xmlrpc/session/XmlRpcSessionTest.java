/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcSessionTest {
    @Test
    public void factoryDeclaresHttpMessageTransport() {
        // XML-RPC 扩展复用 HTTP 传输，不新增专属网络实现。
        XmlRpcProtocol factory = new XmlRpcProtocol();
        ProtocolConfig config = configuration();
        assertEquals("xmlrpc", factory.name());
        assertEquals("http", factory.transportType());
        assertEquals("Json", factory.defaultSerializeType());
        assertEquals(HttpExchange.class, factory.messageType());
        assertEquals(RouteProbeResult.REJECT, factory.probe(config, exchange("<invalid/>")));
    }

    @Test
    public void httpSessionBecomesReadyOnConnected() throws Exception {
        // HTTP 没有额外协议握手，已连接事件只完成就绪 Future。
        try (MetContext context = new MetConfiguration().buildContext()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            assertFalse(session.ready().isDone());

            session.connected();
            session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedFailsReady() throws Exception {
        // 传输在连接完成前失败，应保留握手阶段的原始异常。
        try (MetContext context = new MetConfiguration().buildContext()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection failed");

            session.closed(failure);
            assertSame(failure, session.ready().getCause());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void messageCallIsRejectedBeforeWriting() throws Exception {
        // XML-RPC 标准没有通知帧，不把消息调用伪装成普通调用或本地回执。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(11);
            request.setMessage(true);

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void completedRequestCannotBeSent() throws Exception {
        // 调用已被用户取消，协议不再编码或提交新的网络写入。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(12);
            request.complete(new CancellationException("caller cancelled"));

            assertTrue(session.send(request).getCause() instanceof CancellationException);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void outgoingWriteFailureEndsCallAndExchange() throws Exception {
        // 发出请求但暂不完成物理写入。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(13);
            Future<Void> sent = session.send(request);
            assertFalse(sent.isDone());

            // 写失败的原始原因同时到达发送者与调用层。
            IOException failure = new IOException("write disconnected");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
        }
    }

    @Test
    public void writeFailureAfterEarlyResponseStillCompletesSendFuture() throws Exception {
        // 响应先于写回调到达时，调用完成和发送完成仍是两个生命周期。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(16));
            network.writes.get(0).respond(response("early"));
            assertFalse(sent.isDone());

            // 后到的写失败必须完成发送者，且不能再次交付业务结果。
            IOException failure = new IOException("late write failure");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(1, received.size());
            assertTrue(received.get(0) instanceof ResponsePayload);
        }
    }

    @Test
    public void closeAfterEarlyResponseReleasesPendingWrite() throws Exception {
        // 已收到响应但写回调仍未结束，关闭也不能遗留发送等待者。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(17));
            network.writes.get(0).respond(response("early"));
            IOException failure = new IOException("closed before write callback");

            session.closed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(1, received.size());
        }
    }

    @Test
    public void cancellingSendCancelsPhysicalWrite() throws Exception {
        // 取消尚未写出的请求，写 Future 与请求关联都必须释放。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(14);
            Future<Void> sent = session.send(request);

            assertTrue(sent.cancel());
            assertTrue(network.writeResult.isCancelled());
            assertTrue(request.completion().isDone());
            assertTrue(network.writes.get(0).completion().isDone());
        }
    }

    @Test
    public void cancelledRequestDiscardsLateResponse() throws Exception {
        // 请求已完整写出，调用方在响应到达前取消它。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(15);
            session.send(request).get(1, TimeUnit.SECONDS);
            HttpExchange exchange = network.writes.get(0);

            // 迟到响应不能重新交回调用层，也不能保留失效交换。
            request.complete(new CancellationException("caller cancelled"));
            exchange.respond(response("late"));
            assertTrue(exchange.completion().isDone());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void malformedResponseDoesNotAffectOtherCalls() throws Exception {
        // 同一会话上的两个独立 HTTP 交换等待各自响应。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            session.send(outgoing(21));
            session.send(outgoing(22));

            // 一个响应损坏后，另一个请求仍应正常匹配。
            network.writes.get(0).respond(xmlResponse(200, "<invalid/>"));
            network.writes.get(1).respond(response("valid"));
            assertEquals(2, received.size());
            assertTrue(received.get(0) instanceof ThrowPayload);
            ResponsePayload valid = (ResponsePayload) received.get(1);
            assertEquals(22, valid.getRequestID());
            assertEquals("valid", valid.getReturnData());
        }
    }

    @Test
    public void httpFailureDoesNotWaitForRpcTimeout() throws Exception {
        // HTTP 层已返回明确失败，协议应直接结束调用。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(23);
            session.send(request);

            network.writes.get(0).respond(xmlResponse(503, "unavailable"));
            assertTrue(request.completion().isDone());
            assertEquals(1, received.size());
            assertTrue(((ThrowPayload) received.get(0)).getThrowable().getMessage().contains("503"));
        }
    }

    @Test
    public void closeReleasesOutgoingCallWithoutClosingBorrowedChannel() throws Exception {
        // 请求正在等待响应，通道的所有权仍属于传输层。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            session.send(outgoing(24));
            IOException failure = new IOException("peer disconnected");

            session.closed(failure);
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void inboundAcceptDoesNotCompleteHttpExchange() throws Exception {
        // Dispatcher 的 Accept 只是入队通知，XML-RPC 仍等待真实业务结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload accepted = reply(request.getRequestID(), "ignored");
            accepted.setStatus(ProtocolStatus.Accept);

            session.send(accepted).get(1, TimeUnit.SECONDS);
            assertFalse(exchange.response().isDone());
            assertFalse(request.completion().isDone());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void inboundResponseWaitsForPhysicalWrite() throws Exception {
        // 业务已经返回，但物理响应尚未写完。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "result"));
            assertFalse(sent.isDone());
            assertFalse(request.completion().isDone());

            // 发送者和请求的完成时机都以物理写入为准。
            network.writeResult.completed(null);
            sent.get(1, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            assertEquals(List.of(exchange), network.writes);
        }
    }

    @Test
    public void inboundWriteFailureReleasesExchange() throws Exception {
        // 已编码响应等待网络写入失败。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "result"));

            IOException failure = new IOException("response write failed");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertSame(failure, exchange.completion().getCause());
            assertSame(failure, request.completion().getCause());
        }
    }

    @Test
    public void closeReleasesResponseStillWriting() throws Exception {
        // 即使业务已回答，关闭仍要通知尚未完成的物理写入等待者。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "result"));

            IOException failure = new IOException("closed while writing");
            session.closed(failure);
            assertSame(failure, sent.getCause());
            assertSame(failure, exchange.completion().getCause());
            assertTrue(request.completion().isDone());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void drainAnswersPendingCallBeforeTransportCloses() throws Exception {
        // 服务没有返回时，排空阶段先补齐 HTTP 响应位置。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            session.prepareDrain();
            assertEquals(List.of(exchange), network.writes);
            assertEquals(200, exchange.response().getResult().status());
            assertTrue(body(exchange).contains("<fault>"));
            assertTrue(request.completion().isDone());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void completionWithoutResponseWritesFault() throws Exception {
        // 调用层因拒绝或取消结束请求，不能留下没有响应的 HTTP 交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            request.complete(new CancellationException("invocation cancelled"));
            assertEquals(List.of(exchange), network.writes);
            assertTrue(body(exchange).contains("<fault>"));
        }
    }

    @Test
    public void peerAbortCompletesIncomingInvocation() throws Exception {
        // 对端在业务返回前中止 HTTP 交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            IOException failure = new IOException("peer aborted exchange");

            exchange.complete(failure);
            assertSame(failure, request.completion().getCause());
            session.send(reply(request.getRequestID(), "late")).get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void duplicateResponseDoesNotWriteTwice() throws Exception {
        // 第一份响应尚未写完时再次收到同一请求的结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call();
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> first = session.send(reply(request.getRequestID(), "first"));
            Future<Void> duplicate = session.send(reply(request.getRequestID(), "duplicate"));

            // 同一交换只能产生一份响应，两个观察者共享其完成结果。
            assertSame(first, duplicate);
            assertEquals(List.of(exchange), network.writes);
            assertTrue(body(exchange).contains("first"));
            network.writeResult.completed(null);
            first.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void malformedXmlProducesHttp200Fault() throws Exception {
        // 语法错误是 XML-RPC fault，不进入调用层也不变成 HTTP 500。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = exchange("<methodCall>");

            session.receive(exchange);
            assertEquals(200, exchange.response().getResult().status());
            assertTrue(body(exchange).contains("<fault>"));
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void methodMismatchProducesHttp405() throws Exception {
        // 不支持的方法在 HTTP 边界拒绝。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            HttpRequest request = HttpRequest.newBuffered("GET", "/rpc", Map.of(), new byte[0], 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);

            session.receive(exchange);
            assertEquals(405, exchange.response().getResult().status());
            assertEquals("POST", exchange.response().getResult().header("Allow"));
        }
    }

    @Test
    public void mediaMismatchProducesHttp415() throws Exception {
        // 非 XML 正文不进入 XML 解码流程。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/json"));
            HttpRequest request = HttpRequest.newBuffered("POST", "/rpc", headers, new byte[0], 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);

            session.receive(exchange);
            assertEquals(415, exchange.response().getResult().status());
        }
    }

    @Test
    public void unknownMountProducesHttp404() throws Exception {
        // 会话仅处理配置的挂载目录，不能在任意 URL 暴露服务。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            HttpRequest request = HttpRequest.newBuffered("POST", "/other", Map.of(), new byte[0], 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);

            session.receive(exchange);
            assertEquals(404, exchange.response().getResult().status());
        }
    }

    @Test
    public void immediateFaultWriteFailureCompletesExchange() throws Exception {
        // 没有业务请求的语法错误回复也必须处理物理写入失败。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            HttpExchange exchange = exchange("invalid");
            session.receive(exchange);

            IOException failure = new IOException("fault write failed");
            network.writeResult.failed(failure);
            assertSame(failure, exchange.completion().getCause());
        }
    }

    @Test
    public void faultEncodingFailureTerminatesExchange() throws Exception {
        // 正文限额比 fault 本身还小，不能递归生成错误或把 HTTP 交换悬挂。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            Map<String, String> options = Map.of("contextPath", "/rpc", "maxMessageSize", "1");
            ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", options);
            ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                    context, (action, delay) -> null, network.getLocal(), false);
            XmlRpcProtocol factory = new XmlRpcProtocol();
            ProtocolSession<HttpExchange> session = factory.createSession(config, runtime, network, (payload, id) -> {
                fail("Invalid request must not reach Dispatcher");
            });
            HttpExchange exchange = exchange("<invalid/>");

            session.receive(exchange);
            assertTrue(exchange.completion().isDone());
            assertNotNull(exchange.completion().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    private static MetContext context() throws Exception {
        MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext();
        context.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                .name("Echo") //
                .aliasName("xmlrpc", "demo") //
                .register();
        return context;
    }

    private static ProtocolConfig configuration() {
        return ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("contextPath", "/rpc"));
    }

    private static ProtocolSession<HttpExchange> session(MetContext context, MemoryNetwork network, //
            List<Payload> received, boolean outbound) {
        // 使用最小构造桥接，不为协议测试扩大核心上下文的可见性。
        BiFunction<Runnable, Long, Cancellable> scheduler = (action, delay) -> {
            throw new AssertionError("XML-RPC must reuse RPC request deadlines");
        };
        InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", 8080, "default");
        ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                context, scheduler, address, outbound);
        XmlRpcProtocol factory = new XmlRpcProtocol();
        return factory.createSession(configuration(), runtime, network, (payload, id) -> received.add(payload));
    }

    private static RequestPayload outgoing(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), "value");
        return request;
    }

    private static ResponsePayload reply(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        response.setReturnType(String.class.getName());
        return response;
    }

    private static HttpExchange call() {
        String method = "<methodCall><methodName>demo.echo</methodName>";
        String params = "<params><param><value><string>value</string></value></param></params></methodCall>";
        return exchange(method + params);
    }

    private static HttpExchange exchange(String body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("text/xml"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuffered("POST", "/rpc", headers, bytes, 1, Map.of());
        return new HttpExchange(request);
    }

    private static HttpResponse response(String value) {
        String prefix = "<methodResponse><params><param><value><string>";
        String suffix = "</string></value></param></params></methodResponse>";
        return xmlResponse(200, prefix + value + suffix);
    }

    private static HttpResponse xmlResponse(int status, String body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("text/xml"));
        return HttpResponse.newBuffered(status, headers, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String body(HttpExchange exchange) {
        return new String(exchange.response().getResult().body(), StandardCharsets.UTF_8);
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);
        private       int                closes;

        @Override
        public InterAddress getLocal() {
            return new InterAddress("xmlrpc", "127.0.0.1", 8080, "default");
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
        public Future<Void> write(HttpExchange message) {
            this.writes.add(message);
            return this.writeResult;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
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

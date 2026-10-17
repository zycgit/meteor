/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
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
import net.hasor.meteor.protocol.jsonrpc.JsonRpcProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void factoryDeclaresHttpMessageTransport() {
        // 同一个 SPI 同时承载普通 HTTP 和升级后的 WebSocket，不按编码再造传输层。
        JsonRpcProtocol factory = new JsonRpcProtocol();
        ProtocolConfig config = configuration();
        assertEquals("jsonrpc", factory.name());
        assertEquals("http", factory.transportType());
        assertEquals("Json", factory.defaultSerializeType());
        assertEquals(HttpExchange.class, factory.messageType());
        assertEquals(RouteProbeResult.REJECT, factory.probe(config, exchange("{}")));
    }

    @Test
    public void httpSessionBecomesReadyAfterConnected() throws Exception {
        // 创建不需要握手的 HTTP 会话。
        try (MetContext context = new MetConfiguration().buildContext()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 已连接事件完成协议就绪状态，不产生额外 HTTP 请求。
            session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void notificationIsAcceptedOnlyAfterCompleteWrite() throws Exception {
        // 暂停物理写入，让消息调用停留在已提交但尚未写完的状态。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload notification = outgoing(11, true);

            // 写完前不通知 RPC 层 Accept，也不提前完成发送 Future。
            Future<Void> sent = session.send(notification);
            assertFalse(sent.isDone());
            assertTrue(received.isEmpty());

            // 完整写出后只生成本地确认，不需要远端确认报文。
            network.writeResult.completed(null);
            sent.get(1, TimeUnit.SECONDS);
            assertEquals(1, received.size());
            ResponsePayload accepted = (ResponsePayload) received.get(0);
            assertEquals(11, accepted.getRequestID());
            assertEquals(ProtocolStatus.Accept, accepted.getStatus());
        }
    }

    @Test
    public void notificationWriteFailureNeverProducesAccept() throws Exception {
        // 使用仍未完成的物理写入，保留失败发生时的原始异常。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(12, true));

            // 写失败必须终止发送，消息层不能看到成功提交。
            IOException failure = new IOException("write disconnected");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            for (Payload payload : received) {
                assertTrue(payload instanceof ThrowPayload);
            }
        }
    }

    @Test
    public void cancelledNotificationNeverProducesLateAccept() throws Exception {
        // 保留物理写入 Future，模拟用户在写完前取消 RPC。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload notification = outgoing(13, true);
            session.send(notification);

            // 请求已终止后，即使底层随后写完，也不能重新确认这个调用。
            notification.complete(new CancellationException("caller cancelled"));
            network.writeResult.completed(null);
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void cancellingNotificationSendCancelsItsPendingWrite() throws Exception {
        // 消息发送尚未写完，发送者取消自己持有的发送 Future。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload notification = outgoing(14, true);
            Future<Void> sent = session.send(notification);

            // 独占的出站写入随等待者取消，RPC 生命周期终止且不生成 Accept。
            assertTrue(sent.cancel());
            assertTrue(network.writeResult.isCancelled());
            assertTrue(notification.completion().isDone());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void cancelledRequestDiscardsLateResponse() throws Exception {
        // 发出普通调用并保留它对应的 HTTP 交换。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(21, false);
            session.send(request).get(1, TimeUnit.SECONDS);
            HttpExchange exchange = network.writes.get(0);

            // RPC 取消后清除关联；晚到的成功响应不能送回已终止的调用。
            request.complete(new CancellationException("caller cancelled"));
            exchange.respond(response("{\"jsonrpc\":\"2.0\",\"result\":\"late\",\"id\":21}"));
            assertTrue(exchange.completion().isDone());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void malformedResponseFailsOnlyItsRequest() throws Exception {
        // 在同一会话同时发出两个请求，验证损坏的响应没有串扰。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            session.send(outgoing(22, false));
            session.send(outgoing(23, false));

            // 第一条响应不是合法 JSON，第二条仍可完成正常 RPC。
            network.writes.get(0).respond(response("{malformed"));
            network.writes.get(1).respond(response("{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":23}"));
            assertEquals(2, received.size());
            assertTrue(received.get(0) instanceof ThrowPayload);
            ResponsePayload success = (ResponsePayload) received.get(1);
            assertEquals(23, success.getRequestID());
            assertEquals("ok", success.getReturnData());
        }
    }

    @Test
    public void closeFailsOutstandingResponse() throws Exception {
        // 普通请求已写完，仍然等待远端响应。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            session.send(outgoing(31, false));
            HttpExchange exchange = network.writes.get(0);

            // 连接关闭必须释放未完成交换，并将原始断连原因交回调用层。
            IOException failure = new IOException("peer disconnected");
            session.closed(failure);
            assertSame(failure, exchange.completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
        }
    }

    @Test
    public void drainResolvesUnansweredBatchBeforeWaitingForWrites() throws Exception {
        // 同一入站批次先完成一个业务结果，另一个业务仍未返回。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch();
            session.receive(exchange);
            RequestPayload answered = (RequestPayload) received.get(0);
            RequestPayload unanswered = (RequestPayload) received.get(1);
            Future<Void> pending = session.send(reply(answered.getRequestID(), "finished"));

            // 准备排空补齐未返回的错误位置，让已生成结果能够形成完整批次。
            session.prepareDrain();
            assertEquals(List.of(exchange), network.writes);
            assertTrue(unanswered.completion().isDone());
            JsonNode body = JSON.readTree(exchange.response().getResult().body());
            assertEquals("finished", body.get(0).get("result").asText());
            assertEquals(-32603, body.get(1).get("error").get("code").asInt());

            // 生成批次不代表物理写出，先前的业务发送者仍等待写完成。
            assertFalse(pending.isDone());
            network.writeResult.completed(null);
            pending.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void batchRepliesWaitForOnePhysicalWrite() throws Exception {
        // 两个入站请求共享一个批次，物理响应由测试控制完成时机。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch();
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 业务逆序完成，两个发送者都必须等待唯一一次批次写入。
            Future<Void> secondSent = session.send(reply(second.getRequestID(), "second"));
            Future<Void> firstSent = session.send(reply(first.getRequestID(), "first"));
            assertEquals(List.of(exchange), network.writes);
            assertFalse(firstSent.isDone());
            assertFalse(secondSent.isDone());

            // 完成写入后两个业务响应的发送 Future 一起结束。
            network.writeResult.completed(null);
            firstSent.get(1, TimeUnit.SECONDS);
            secondSent.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void cancellingOneBatchWaiterDoesNotCancelOtherReply() throws Exception {
        // 同一批次包含两个独立请求，先提交第一个业务结果。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(batch());
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 取消单个等待者不能取消同批次的其他调用和共享物理写入。
            Future<Void> cancelled = session.send(reply(first.getRequestID(), "first"));
            assertTrue(cancelled.cancel());
            Future<Void> remaining = session.send(reply(second.getRequestID(), "second"));
            assertFalse(network.writeResult.isCancelled());
            assertFalse(remaining.isDone());

            network.writeResult.completed(null);
            remaining.get(1, TimeUnit.SECONDS);
            assertTrue(cancelled.isCancelled());
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void closeReleasesPartiallyAnsweredBatch() throws Exception {
        // 只有第一个子调用生成结果，整批响应尚未达到写出条件。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch();
            session.receive(exchange);
            RequestPayload answered = (RequestPayload) received.get(0);
            RequestPayload unanswered = (RequestPayload) received.get(1);
            Future<Void> pending = session.send(reply(answered.getRequestID(), "waiting"));

            // 断连结束批次等待者和业务请求，不再生成任何网络写入。
            IOException failure = new IOException("peer disconnected");
            session.closed(failure);
            assertSame(failure, pending.getCause());
            assertTrue(answered.completion().isDone());
            assertTrue(unanswered.completion().isDone());
            assertTrue(exchange.completion().isDone());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void closeReleasesBatchWhoseResponseIsStillWriting() throws Exception {
        // 两个业务都已完成，但唯一一次物理批次写入仍未结束。
        try (MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext()) {
            publish(context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch();
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);
            Future<Void> firstSent = session.send(reply(first.getRequestID(), "first"));
            Future<Void> secondSent = session.send(reply(second.getRequestID(), "second"));

            // 此时没有未回答请求，断连仍必须结束正在写出的批次等待者。
            IOException failure = new IOException("write disconnected");
            session.closed(failure);
            assertSame(failure, firstSent.getCause());
            assertSame(failure, secondSent.getCause());
            assertSame(failure, exchange.completion().getCause());
            assertTrue(first.completion().isDone());
            assertTrue(second.completion().isDone());
        }
    }

    private static void publish(MetContext context) {
        context.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                .name("Echo") //
                .aliasName("jsonrpc", "demo") //
                .register();
    }

    private static ProtocolConfig configuration() {
        return ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of("contextPath", "/rpc"));
    }

    private static ProtocolSession<HttpExchange> session(MetContext context, MemoryNetwork network, //
            List<Payload> received, boolean outbound) {
        // 协议复用调用层的截止时间，不自行安排一套请求定时器。
        BiFunction<Runnable, Long, Cancellable> scheduler = (action, delay) -> {
            throw new AssertionError("JSON-RPC must use RPC request deadlines");
        };
        InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", 8080, "default");
        ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                context, scheduler, address, outbound);

        // 通过协议工厂装配会话；内存通道只控制网络写入完成时机。
        JsonRpcProtocol factory = new JsonRpcProtocol();
        ProtocolSession<HttpExchange> session = factory.createSession( //
                configuration(), runtime, network, (payload, id) -> received.add(payload));
        session.connected();
        return session;
    }

    private static RequestPayload outgoing(long id, boolean notification) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod(notification ? "notify" : "echo");
        request.setMessage(notification);
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

    private static HttpExchange batch() {
        String first = "{\"jsonrpc\":\"2.0\",\"method\":\"demo.echo\",\"params\":[\"first\"],\"id\":1}";
        String second = "{\"jsonrpc\":\"2.0\",\"method\":\"demo.echo\",\"params\":[\"second\"],\"id\":2}";
        return exchange("[" + first + "," + second + "]");
    }

    private static HttpExchange exchange(String body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/json"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuffered("POST", "/rpc", headers, bytes, 1, Map.of());
        return new HttpExchange(request);
    }

    private static HttpResponse response(String body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/json"));
        return HttpResponse.newBuffered(200, headers, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);

        @Override
        public InterAddress getLocal() {
            return new InterAddress("jsonrpc", "127.0.0.1", 8080, "default");
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
            return new BasicFuture<>((Void) null);
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}

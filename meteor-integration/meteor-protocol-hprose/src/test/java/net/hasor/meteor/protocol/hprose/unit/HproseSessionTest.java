/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import hprose.io.HproseReader;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;
import net.hasor.meteor.protocol.hprose.HproseUnitAccess.Codec;
import net.hasor.meteor.protocol.hprose.HproseUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void batchRepliesKeepRequestOrderAndShareThePhysicalWrite() throws Exception {
        // 准备入站批次会话，让物理写入等待测试主动完成。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec codec = new Codec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch(codec);
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 后面的业务先完成；未齐的批次不发送部分 HTTP 响应。
            Future<Void> secondSent = session.send(reply(second.getRequestID(), "second"));
            assertFalse(secondSent.isDone());
            assertTrue(network.writes.isEmpty());
            Future<Void> firstSent = session.send(reply(first.getRequestID(), "first"));
            assertEquals(List.of(exchange), network.writes);
            assertFalse(firstSent.isDone());
            assertFalse(secondSent.isDone());

            // 物理写入完成后才释放两个发送者，报文顺序与调用顺序一致。
            network.writeResult.completed(null);
            firstSent.get(1, TimeUnit.SECONDS);
            secondSent.get(1, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().getResult();
            ByteArrayInputStream body = new ByteArrayInputStream(response.body());
            assertEquals('R', body.read());
            assertEquals("first", new HproseReader(body).readString());
            assertEquals('R', body.read());
            assertEquals("second", new HproseReader(body).readString());
            assertEquals('z', body.read());
            assertEquals(-1, body.read());
        }
    }

    @Test
    public void batchTimeoutPreservesOtherResults() throws Exception {
        // 准备同一批次的两个调用，让第一个先产生正常结果。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec codec = new Codec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch(codec);
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);
            Future<Void> sent = session.send(reply(first.getRequestID(), "finished"));

            // 单个子调用过期只填充自己的结果位置，已完成结果仍然返回。
            second.complete(new TimeoutException("expired child"));
            sent.get(1, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().getResult();
            ByteArrayInputStream body = new ByteArrayInputStream(response.body());
            assertEquals('R', body.read());
            assertEquals("finished", new HproseReader(body).readString());
            assertEquals('E', body.read());
            assertEquals("expired child", new HproseReader(body).readString());
            assertEquals('z', body.read());
        }
    }

    @Test
    public void closeReleasesAnsweredAndUnansweredBatchChildren() throws Exception {
        // 准备接收批次请求的协议会话，并记录每个子调用。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec codec = new Codec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            // 同批两个调用中，只让第一个产生结果，保留等待整批响应的发送者。
            session.receive(batch(codec));
            RequestPayload answered = (RequestPayload) received.get(0);
            RequestPayload unanswered = (RequestPayload) received.get(1);
            Future<Void> pending = session.send(reply(answered.getRequestID(), "waiting"));

            // 断连同时结束已生成结果和未应答的调用，不再向网络写入。
            IOException closed = new IOException("peer disconnected");
            session.closed(closed);
            assertSame(closed, pending.getCause());
            assertSame(closed, answered.completion().getCause());
            assertSame(closed, unanswered.completion().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void malformedBatchNeverDispatchesItsValidPrefix() throws Exception {
        // 准备批次编解码器和入站会话，记录实际分发的请求。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec codec = new Codec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            // 破坏批次末尾标记，前面的合法调用也不能被分发。
            HttpExchange validBatch = batch(codec);
            byte[] invalid = validBatch.request().body();
            invalid[invalid.length - 1] = '?';
            HttpRequest input = HttpRequest.newBuffered("POST", "/rpc", Map.of(), invalid, 1, Map.of());
            HttpExchange exchange = new HttpExchange(input);
            session.receive(exchange);
            assertTrue(received.isEmpty());
            HttpResponse response = exchange.response().getResult();
            assertEquals('E', response.body()[0]);
            assertEquals(1, network.writes.size());
        }
    }

    private static HttpExchange batch(Codec codec) throws Exception {
        // 去掉第一条调用的结束标记，拼成共用结束标记的批次报文。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] first = codec.encode(outgoing(1, "one")).body();
        body.write(first, 0, first.length - 1);
        body.write(codec.encode(outgoing(2, "two")).body());
        byte[] batchBody = body.toByteArray();
        HttpRequest request = HttpRequest.newBuffered("POST", "/rpc", Map.of(), batchBody, 1, Map.of());
        return new HttpExchange(request);
    }

    @Test
    public void acceptAcknowledgementDoesNotWriteHttpResponse() throws Exception {
        // 准备入站会话，观察内部确认是否触发 HTTP 写入。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec protocol = new Codec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            // Accept 只作内部确认，不发送 HTTP 响应。
            HttpExchange first = call(protocol, "first");
            session.receive(first);
            RequestPayload one = (RequestPayload) received.get(0);
            ResponsePayload accepted = reply(one.getRequestID(), "ignored");
            accepted.setStatus(ProtocolStatus.Accept);
            session.send(accepted).get(5, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void reversedRepliesKeepTheirOriginalExchanges() throws Exception {
        // 准备独立交换的编解码器和会话，记录网络写入顺序。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec protocol = new Codec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            // 接收两个独立 HTTP 交换，分别保留其请求编号。
            HttpExchange first = call(protocol, "first");
            HttpExchange second = call(protocol, "second");
            session.receive(first);
            session.receive(second);
            RequestPayload one = (RequestPayload) received.get(0);
            RequestPayload two = (RequestPayload) received.get(1);

            // 响应逆序提交时仍对应各自的 HTTP 交换。
            session.send(reply(two.getRequestID(), "second-reply")).get(5, TimeUnit.SECONDS);
            session.send(reply(one.getRequestID(), "first-reply")).get(5, TimeUnit.SECONDS);
            assertSame(second, network.writes.get(0));
            assertSame(first, network.writes.get(1));
            HttpResponse secondWire = second.response().get(5, TimeUnit.SECONDS);
            HttpResponse firstWire = first.response().get(5, TimeUnit.SECONDS);
            assertEquals("second-reply", protocol.decode(two, secondWire).getReturnData());
            assertEquals("first-reply", protocol.decode(one, firstWire).getReturnData());
        }
    }

    @Test
    public void unifiedSessionFinishesExpiredRequestsAndRejectsLateReplies() throws Exception {
        // 准备一条等待业务响应的入站请求。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec protocol = new Codec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(protocol, "expire");
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // RPC 层结束请求，会话立即回送错误，之后的迟到响应不得重复写入。
            request.complete(new TimeoutException("expired"));
            assertSame(exchange, network.writes.get(0));
            HttpResponse wire = exchange.response().get(5, TimeUnit.SECONDS);
            ResponsePayload expired = protocol.decode(request, wire);
            assertEquals(ProtocolStatus.InvokeError, expired.getStatus());
            ResponsePayload lateReply = reply(request.getRequestID(), "late");
            Future<Void> lateSent = session.send(lateReply);
            assertNotNull(lateSent.getCause());
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void unifiedSessionDrainReleasesEveryUnansweredExchange() throws Exception {
        // 准备两条尚未返回结果的 HTTP 调用，作为排空对象。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec protocol = new Codec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(call(protocol, "one"));
            session.receive(call(protocol, "two"));

            // 排空时逐条结束尚未应答的 HTTP 交换。
            session.prepareDrain();
            assertEquals(2, network.writes.size());
            for (HttpExchange exchange : network.writes) {
                HttpResponse wire = exchange.response().get(5, TimeUnit.SECONDS);
                ResponsePayload response = protocol.decode(outgoing(0, ""), wire);
                assertEquals(ProtocolStatus.InvokeError, response.getStatus());
            }
            RequestPayload first = (RequestPayload) received.get(0);
            ResponsePayload lateReply = reply(first.getRequestID(), "late");
            Future<Void> lateSent = session.send(lateReply);
            assertNotNull(lateSent.getCause());
        }
    }

    @Test
    public void unifiedSessionCloseCompletesAllInboundRequests() throws Exception {
        // 准备两条在途入站请求，保留完成信号供关闭后检查。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
            Codec protocol = new Codec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(call(protocol, "one"));
            session.receive(call(protocol, "two"));

            // 通道已经关闭，只结束请求并保留原因，不再尝试发送错误帧。
            IOException failure = new IOException("closed");
            session.closed(failure);
            assertTrue(network.writes.isEmpty());
            for (Payload message : received) {
                RequestPayload request = (RequestPayload) message;
                assertSame(failure, request.completion().getCause());
            }
        }
    }

    @Test
    public void outboundResponseCompletesItsOriginalRequest() throws Exception {
        // 准备出站会话，记录网络响应转换出的 Payload。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 发出请求并准备携带相同请求编号的成功响应。
            RequestPayload first = outgoing(20, "first");
            session.send(first).get(5, TimeUnit.SECONDS);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HproseUtils.parseResponse(20, reply(20, "answer"), body);

            // HTTP 响应到达后，映射回原请求的 Payload 并结束其生命周期。
            byte[] responseBody = body.toByteArray();
            HttpResponse wire = HttpResponse.newBuffered(200, Collections.emptyMap(), responseBody, Map.of());
            network.writes.get(0).respond(wire);
            ResponsePayload response = (ResponsePayload) received.get(0);
            assertEquals("answer", response.getReturnData());
            assertEquals(20, response.getRequestID());
            assertTrue(first.completion().isDone());
        }
    }

    @Test
    public void networkFailureIsDeliveredAsThrowPayload() throws Exception {
        // 准备出站会话，观察原请求和失败通知的完成原因。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 网络失败结束对应请求，原始原因通过 ThrowPayload 交付。
            RequestPayload next = outgoing(21, "fail");
            session.send(next).get(5, TimeUnit.SECONDS);
            IOException failure = new IOException("network closed");
            network.writes.get(0).complete(failure);
            ThrowPayload failed = (ThrowPayload) received.get(0);
            assertSame(failure, failed.getThrowable());
            assertSame(failure, next.completion().getCause());
        }
    }

    @Test
    public void malformedOutboundReplyFailsOnlyItsRequest() throws Exception {
        // 准备出站会话，观察一条响应失败是否影响其它请求。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 两个请求同时等待响应，第一条解码失败只能结束对应调用。
            RequestPayload request = outgoing(50, "bad-response");
            RequestPayload next = outgoing(51, "next");
            session.send(request).get(5, TimeUnit.SECONDS);
            session.send(next).get(5, TimeUnit.SECONDS);
            byte[] invalidBody = new byte[] { '?' };
            HttpResponse invalidReply = HttpResponse.newBuffered(200, Map.of(), invalidBody, Map.of());
            network.writes.get(0).respond(invalidReply);
            assertEquals(1, received.size());
            ThrowPayload failed = (ThrowPayload) received.get(0);
            assertTrue(failed.getThrowable() instanceof IOException);
            assertSame(failed.getThrowable(), request.completion().getCause());
            assertFalse(next.completion().isDone());

            // 另一条在途请求不受影响，仍能接收完整响应。
            byte[] validBody = new byte[] { 'R', 'n', 'z' };
            HttpResponse validReply = HttpResponse.newBuffered(200, Map.of(), validBody, Map.of());
            network.writes.get(1).respond(validReply);
            ResponsePayload response = (ResponsePayload) received.get(1);
            assertEquals(51, response.getRequestID());
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertNull(next.completion().getCause());
            assertTrue(next.completion().isDone());
        }
    }

    @Test
    public void completedRequestIsNotSent() throws Exception {
        // 准备出站会话，分别记录物理写入和接收通知。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 截止时间已到的请求在提交前就结束，发送不得触达网络。
            RequestPayload expired = outgoing(60, "expired");
            expired.complete(new TimeoutException("deadline"));
            Future<Void> sent = session.send(expired);
            assertTrue(sent.getCause() instanceof CancellationException);
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void outboundSessionRejectsResponsePayload() throws Exception {
        // 准备主动连接的会话，观察不支持的消息是否被写出。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 主动连接的会话只允许发送请求，拒绝反向提交响应。
            Future<Void> sent = session.send(reply(60, "reply"));
            assertTrue(sent.getCause() instanceof UnsupportedOperationException);
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void inboundSessionRejectsRequestPayload() throws Exception {
        // 准备服务声明和内存网络，供方向校验使用。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 入站会话只允许回送响应，拒绝发起新请求。
            ProtocolSession<HttpExchange> inbound = session(context, network, received, false);
            Future<Void> sent = inbound.send(outgoing(61, "wrong-direction"));
            assertTrue(sent.getCause() instanceof UnsupportedOperationException);
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    private static ProtocolSession<HttpExchange> session(MetContext runtime, MemoryNetwork network, //
            List<Payload> received, boolean outbound) {
        // 会话借用 RPC 截止时间；协议自行安排超时任务即为测试失败。
        BiFunction<Runnable, Long, Cancellable> scheduler = (action, delay) -> {
            throw new AssertionError("Hprose must use RPC deadlines rather than schedule its own timeout");
        };

        // 将协议与内存网络组装成已连接会话，收到的消息交由测试观察。
        InterAddress address = new InterAddress("hprose", "127.0.0.1", 8080, "default");
        ProtocolContext context = new ProtocolContext(runtime, scheduler, address, outbound);
        ProtocolConfig config = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/rpc"));
        HproseProtocol factory = new HproseProtocol();
        BiConsumer<Long, Payload> messages = (id, payload) -> received.add(payload);
        ProtocolSession<HttpExchange> session = factory.create(config, context, network, messages);
        session.connected();
        assertTrue(session.ready().isDone());
        return session;
    }

    private static HttpExchange call(Codec protocol, String value) throws Exception {
        return new HttpExchange(protocol.encode(outgoing(1, value)));
    }

    private static RequestPayload outgoing(long id, String value) {
        // 构造对应 Echo.echo(String) 的请求，供编解码和会话测试共用。
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), value);
        return request;
    }

    private static ResponsePayload reply(long id, String value) {
        // 固定为字符串成功结果，调用方只指定请求编号和返回值。
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        response.setReturnType(String.class.getName());
        return response;
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);

        public InterAddress getLocal() {
            return new InterAddress("hprose", "127.0.0.1", 2181, "default");
        }

        public InterAddress getRemote() {
            return this.getLocal();
        }

        public boolean isOpen() {
            return true;
        }

        public Future<Void> write(HttpExchange message) {
            // 记录物理写入，完成时机由用例设置的 Future 控制。
            this.writes.add(message);
            return this.writeResult;
        }

        public void execute(Runnable task) {
            task.run();
        }

        public Future<Void> close() {
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}

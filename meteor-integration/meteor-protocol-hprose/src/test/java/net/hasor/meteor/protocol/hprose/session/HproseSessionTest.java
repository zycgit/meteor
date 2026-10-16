/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.session;
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
import java.util.function.BiFunction;
import java.util.function.ObjLongConsumer;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolSessionContextTestAccess;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void connectedSessionReportsReady() throws Exception {
        // 创建连接后的会话，检查协议就绪状态。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            assertTrue(session.ready().isDone());
        }
    }

    @Test
    public void inboundSessionAssignsIndependentRequestIds() throws Exception {
        // 准备包含两个调用的入站批次和会话。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);

            // 会话接收入站调用时分配通道内唯一的请求标识。
            session.receive(batch(codec));
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);
            assertTrue(first.getRequestID() > 0);
            assertNotEquals(first.getRequestID(), second.getRequestID());
        }
    }

    @Test
    public void batchRepliesKeepRequestOrder() throws Exception {
        // 准备包含两个调用的入站批次会话。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch(codec);
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 后面的业务先完成；最终报文仍按请求位置排列结果。
            Future<Void> secondSent = session.send(reply(second.getRequestID(), "second"));
            Future<Void> firstSent = session.send(reply(first.getRequestID(), "first"));
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
    public void batchRepliesSharePhysicalWriteCompletion() throws Exception {
        // 准备入站批次会话，让物理写入等待测试主动完成。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch(codec);
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 同一批次的两个发送者都等待唯一一次物理写入完成。
            Future<Void> secondSent = session.send(reply(second.getRequestID(), "second"));
            Future<Void> firstSent = session.send(reply(first.getRequestID(), "first"));
            assertEquals(List.of(exchange), network.writes);
            assertFalse(firstSent.isDone());
            assertFalse(secondSent.isDone());

            network.writeResult.completed(null);
            firstSent.get(1, TimeUnit.SECONDS);
            secondSent.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void cancellingOneBatchReplyDoesNotCancelSharedWrite() throws Exception {
        // 准备入站批次会话，让物理写入和 HTTP 交换等待测试主动完成。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = batch(codec);
            network.writeResult.onFinal(done -> exchange.complete(done.getCause()));
            session.receive(exchange);
            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);

            // 取消一个子响应的等待者，其余响应仍组成批次并提交唯一的物理写入。
            Future<Void> cancelled = session.send(reply(first.getRequestID(), "first"));
            assertTrue(cancelled.cancel());
            Future<Void> remaining = session.send(reply(second.getRequestID(), "second"));
            assertTrue(cancelled.isCancelled());
            assertEquals(List.of(exchange), network.writes);
            assertFalse(remaining.isDone());

            // 物理写入完成后，剩余发送者和对应 HTTP 交换均正常结束。
            network.writeResult.completed(null);
            remaining.get(1, TimeUnit.SECONDS);
            exchange.completion().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void batchTimeoutPreservesOtherResults() throws Exception {
        // 准备同一批次的两个调用，让第一个先产生正常结果。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
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
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
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
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
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

    @Test
    public void malformedCorsRequestKeepsCredentialResponseHeaders() throws Exception {
        // 准备带 Origin 的非法入站请求，观察协议错误响应的跨域头。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            String origin = "https://client.example";
            Map<String, List<String>> headers = Map.of("Origin", List.of(origin));
            HttpRequest request = HttpRequest.newBuffered("POST", "/rpc", headers, new byte[] { '?' }, 1, Map.of());
            HttpExchange exchange = new HttpExchange(request);

            // 整体解码失败仍沿用请求 Origin，使凭证请求可以读取错误正文。
            session.receive(exchange);
            HttpResponse response = exchange.response().get(1, TimeUnit.SECONDS);
            assertEquals(origin, response.header("Access-Control-Allow-Origin"));
            assertEquals("true", response.header("Access-Control-Allow-Credentials"));
            assertEquals("Origin", response.header("Vary"));
            assertTrue(received.isEmpty());
        }
    }

    private static HttpExchange batch(HproseHttpCodec codec) throws Exception {
        // 去掉第一条调用的结束标记，拼成共用结束标记的批次报文。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] first = codec.encodeRequest(outgoing(1, "one")).body();
        body.write(first, 0, first.length - 1);
        body.write(codec.encodeRequest(outgoing(2, "two")).body());
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
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, context);

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
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, context);

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
            assertEquals("second-reply", protocol.decodeResponse(two, secondWire).getReturnData());
            assertEquals("first-reply", protocol.decodeResponse(one, firstWire).getReturnData());
        }
    }

    @Test
    public void unifiedSessionFinishesExpiredRequestsAndRejectsLateReplies() throws Exception {
        // 准备一条等待业务响应的入站请求。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, context);

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
            ResponsePayload expired = protocol.decodeResponse(request, wire);
            assertEquals(ProtocolStatus.InvokeError, expired.getStatus());
            ResponsePayload lateReply = reply(request.getRequestID(), "late");
            Future<Void> lateSent = session.send(lateReply);
            assertNotNull(lateSent.getCause());
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void completedInboundRequestRejectsQueuedBusinessResponse() throws Exception {
        // 准备入站请求，并暂停会话完成回调所在的连接执行器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec codec = new HproseHttpCodec(config, context);
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(codec, "timeout");
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            network.deferTasks = true;

            // RPC 已进入超时终态时，较早排队的业务响应不能覆盖该结果。
            TimeoutException timeout = new TimeoutException("deadline");
            request.complete(timeout);
            Future<Void> late = session.send(reply(request.getRequestID(), "late"));
            assertSame(timeout, late.getCause());
            assertTrue(network.writes.isEmpty());

            // 恢复连接执行器后，由请求完成回调写出原始超时结果。
            network.resumeTasks();
            assertEquals(List.of(exchange), network.writes);
            HttpResponse wire = exchange.response().get(1, TimeUnit.SECONDS);
            ResponsePayload response = codec.decodeResponse(request, wire);
            assertEquals(ProtocolStatus.InvokeError, response.getStatus());
            assertEquals("deadline", response.getOption("message"));
        }
    }

    @Test
    public void prepareDrainResolvesPendingWithoutClosingBorrowedChannel() throws Exception {
        // 准备两条尚未返回结果的 HTTP 调用，作为排空对象。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, context);

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
                ResponsePayload response = protocol.decodeResponse(outgoing(0, ""), wire);
                assertEquals(ProtocolStatus.InvokeError, response.getStatus());
            }
            RequestPayload first = (RequestPayload) received.get(0);
            ResponsePayload lateReply = reply(first.getRequestID(), "late");
            Future<Void> lateSent = session.send(lateReply);
            assertNotNull(lateSent.getCause());
            assertEquals(0, network.closeCalls);
            assertEquals(0, network.drainCalls);
        }
    }

    @Test
    public void unifiedSessionCloseCompletesAllInboundRequests() throws Exception {
        // 准备两条在途入站请求，保留完成信号供关闭后检查。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, context);

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
            body.write('R');
            new HproseWriter(body).writeString("answer");
            body.write('z');

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
    public void completedOutboundRequestDoesNotPublishItsLocalFailure() throws Exception {
        // 准备已写出的出站请求，观察 RPC 本地终结后的协议通知。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(62, "timeout");
            session.send(request).get(1, TimeUnit.SECONDS);

            // 本地超时已经结束 RPC，协议不得把同一原因再次发布为网络消息。
            request.complete(new TimeoutException("deadline"));
            assertTrue(request.completion().isDone());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void completedOutboundRequestDoesNotPublishQueuedResponse() throws Exception {
        // 准备已到达但尚未由连接执行器处理的出站响应。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(63, "timeout");
            session.send(request).get(1, TimeUnit.SECONDS);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write('R');
            new HproseWriter(body).writeString("late");
            body.write('z');
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), body.toByteArray(), Map.of());
            network.deferTasks = true;
            network.writes.get(0).respond(response);

            // 回调排队期间本地请求进入终态，恢复执行后不得再投递业务响应。
            request.complete(new TimeoutException("deadline"));
            network.resumeTasks();
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
        ProtocolSessionContext context = ProtocolSessionContextTestAccess.newContext( //
                runtime, scheduler, address, outbound);
        ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
        HproseProtocol factory = new HproseProtocol();
        ObjLongConsumer<Payload> messages = (payload, id) -> received.add(payload);
        ProtocolSession<HttpExchange> session = factory.createSession(config, context, network, messages);
        session.connected();
        return session;
    }

    private static HttpExchange call(HproseHttpCodec protocol, String value) throws Exception {
        return new HttpExchange(protocol.encodeRequest(outgoing(1, value)));
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
        private final List<Runnable>     tasks       = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);
        private       boolean            deferTasks;
        private       int                closeCalls;
        private       int                drainCalls;

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
            if (this.deferTasks) {
                this.tasks.add(task);
            } else {
                task.run();
            }
        }

        private void resumeTasks() {
            this.deferTasks = false;
            List<Runnable> pending = new ArrayList<>(this.tasks);
            this.tasks.clear();
            for (Runnable task : pending) {
                task.run();
            }
        }

        public Future<Void> close() {
            this.closeCalls++;
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            this.drainCalls++;
            return new BasicFuture<>((Void) null);
        }
    }
}

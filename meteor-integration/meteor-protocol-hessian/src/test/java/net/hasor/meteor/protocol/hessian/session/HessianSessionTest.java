/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import com.caucho.hessian.io.*;
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
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void connectedCompletesReadyWithoutProtocolHandshake() throws Exception {
        // HTTP 连接建立后即可发送 Hessian 调用，不产生额外握手报文。
        try (MetContext context = context()) {
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
        // 连接失败保留原始原因，协议会话不反向关闭借用的传输通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection failed");

            session.closed(failure);
            assertSame(failure, session.ready().getCause());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void hessianOneRequestReceivesHessianOneReply() throws Throwable {
        // 使用官方 Hessian 1 编码器准备请求，客户端版本不由服务端默认配置决定。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(1);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 回包保留 Hessian 1 标识，并可由官方解码器消费。
            session.send(reply(request.getRequestID(), "one")).get(1, TimeUnit.SECONDS);
            byte[] body = exchange.response().getResult().body();
            assertEquals('r', body[0]);
            assertEquals(1, body[1]);
            assertEquals("one", readReply(body));
        }
    }

    @Test
    public void hessianTwoRequestReceivesHessianTwoReply() throws Throwable {
        // 使用官方 Hessian 2 请求验证响应版本和正文。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            session.send(reply(request.getRequestID(), "two")).get(1, TimeUnit.SECONDS);
            byte[] body = exchange.response().getResult().body();
            assertEquals('H', body[0]);
            assertEquals(2, body[1]);
            assertEquals("two", readReply(body));
        }
    }

    @Test
    public void legacyCallCanNegotiateHessianTwoReply() throws Throwable {
        // Caucho 兼容模式用 Hessian 1 参数编码和 c2 头请求 Hessian 2 响应。
        try (MetContext context = context()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HessianOutput output = new HessianOutput(body);
            output.setVersion(2);
            output.call("echo", new Object[] { "value" });
            output.flush();
            HttpExchange exchange = exchange("POST", "/rpc/demo", "application/x-hessian", body.toByteArray());
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 会话应记住请求声明的响应版本，而不是仅记住参数编码器。
            session.send(reply(request.getRequestID(), "compatible")).get(1, TimeUnit.SECONDS);
            byte[] response = exchange.response().getResult().body();
            assertEquals('H', response[0]);
            assertEquals("compatible", readReply(response));
        }
    }

    @Test
    public void requestsInOneSessionReceiveIndependentIds() throws Exception {
        // Hessian 线上没有请求 ID，多个 HTTP 交换需要独立的内部调用标识。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, new MemoryNetwork(), received, false);
            session.receive(call(2));
            session.receive(call(2));

            RequestPayload first = (RequestPayload) received.get(0);
            RequestPayload second = (RequestPayload) received.get(1);
            assertTrue(first.getRequestID() > 0);
            assertNotEquals(first.getRequestID(), second.getRequestID());
        }
    }

    @Test
    public void outgoingRequestUsesHessianTwoByDefault() throws Exception {
        // 未指定版本时，客户端提交标准 Hessian 2 请求。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            session.send(outgoing(11)).get(1, TimeUnit.SECONDS);

            HttpRequest request = network.writes.get(0).request();
            assertEquals("POST", request.method());
            assertEquals("/rpc/demo", request.uri());
            assertEquals("application/x-hessian", request.header("Content-Type"));
            assertEquals('H', request.body()[0]);
            assertEquals(2, request.body()[1]);
        }
    }

    @Test
    public void configuredHessianOneRequestUsesLegacyCallHeader() throws Exception {
        // 出站版本配置只改变 Hessian 线编码，不改变底层 HTTP 版本。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            Map<String, String> options = Map.of("contextPath", "/rpc", "version", "1");
            ProtocolConfig config = ProtocolConfig.newConfig("hessian", "hessian", options);
            ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                    context, (action, delay) -> null, network.getLocal(), true);
            HessianSession session = new HessianSession(config, runtime, network, (payload, id) -> {
            });

            session.send(outgoing(22)).get(1, TimeUnit.SECONDS);
            HttpRequest request = network.writes.get(0).request();
            assertEquals('c', request.body()[0]);
            assertEquals(1, request.body()[1]);
            assertEquals(1, request.version());
        }
    }

    @Test
    public void messageCallIsRejectedBeforeWriting() throws Exception {
        // Hessian 必须有响应，不把消息模式悄悄转换为普通调用。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(12);
            request.setMessage(true);

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void completedRequestCannotBeSent() throws Exception {
        // 调用已被取消，协议不应建立新的 HTTP 交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(13);
            request.complete(new CancellationException("cancelled before send"));

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof CancellationException);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void outgoingWriteFailureCompletesRequestAndExchange() throws Exception {
        // 控制物理写入完成时机，观察调用、发送者和交换的同一失败原因。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(14);
            Future<Void> sent = session.send(request);
            assertFalse(sent.isDone());

            IOException failure = new IOException("request write failed");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
        }
    }

    @Test
    public void responseBeforeWriteFailureIsNotDeliveredTwice() throws Exception {
        // 响应先于写回调到达，发送 Future 仍应等待物理写入结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(15));
            network.writes.get(0).respond(response("early"));
            assertFalse(sent.isDone());

            // 后到的失败只通知发送者，调用层不再收到第二个结果。
            IOException failure = new IOException("late write failure");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(1, received.size());
            assertEquals("early", ((ResponsePayload) received.get(0)).getReturnData());
        }
    }

    @Test
    public void cancellingSendCancelsPhysicalWrite() throws Exception {
        // 请求尚未写完，取消发送应释放传输 Future 和当前交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(16);
            Future<Void> sent = session.send(request);

            assertTrue(sent.cancel());
            assertTrue(network.writeResult.isCancelled());
            assertTrue(request.completion().getCause() instanceof CancellationException);
            assertTrue(network.writes.get(0).completion().isDone());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void cancellationAfterSendReleasesPendingResponse() throws Exception {
        // 物理写入已完成，调用方取消仍需停止等待响应。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(17);
            session.send(request).get(1, TimeUnit.SECONDS);
            CancellationException failure = new CancellationException("caller cancelled");

            request.complete(failure);
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertFalse(network.writes.get(0).respond(response("late")));
            assertTrue(received.isEmpty());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void malformedResponseDoesNotInterruptAnotherPendingCall() throws Exception {
        // 同一个会话中有两个等待响应的调用，其中一个收到损坏报文。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload first = outgoing(25);
            RequestPayload second = outgoing(26);
            session.send(first).get(1, TimeUnit.SECONDS);
            session.send(second).get(1, TimeUnit.SECONDS);
            byte[] incomplete = new byte[] { 'H', 2, 0 };
            Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/x-hessian"));
            HttpResponse broken = HttpResponse.newBuffered(200, headers, incomplete, Map.of());

            // 解码错误立即结束对应请求，另一个交换仍可独立完成。
            network.writes.get(0).respond(broken);
            assertNotNull(first.completion().getCause());
            assertFalse(second.completion().isDone());
            network.writes.get(1).respond(response("unaffected"));
            assertNull(second.completion().getCause());
            assertTrue(second.completion().isDone());
            assertEquals(2, received.size());
            assertTrue(received.get(0) instanceof ThrowPayload);
            ResponsePayload result = (ResponsePayload) received.get(1);
            assertEquals(second.getRequestID(), result.getRequestID());
            assertEquals("unaffected", result.getReturnData());
        }
    }

    @Test
    public void nonSuccessHttpStatusFailsPendingCall() throws Exception {
        // 非 200 的 HTTP 响应不当作 Hessian 成功报文解码。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(18);
            session.send(request).get(1, TimeUnit.SECONDS);

            HttpResponse response = HttpResponse.newBuffered(503, Map.of(), new byte[0], Map.of());
            network.writes.get(0).respond(response);
            assertTrue(request.completion().isDone());
            assertEquals(1, received.size());
            Throwable failure = ((ThrowPayload) received.get(0)).getThrowable();
            assertTrue(failure.getMessage().contains("503"));
        }
    }

    @Test
    public void closeReleasesPendingCallWithoutClosingBorrowedChannel() throws Exception {
        // 传输层通知会话关闭，协议只释放当前调用关联。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload request = outgoing(19);
            session.send(request).get(1, TimeUnit.SECONDS);
            IOException failure = new IOException("transport disconnected");

            session.closed(failure);
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void closeAfterEarlyResponseReleasesPendingWrite() throws Exception {
        // 已交付响应但物理写回调仍未结束，关闭不得遗留发送等待者。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(20));
            network.writes.get(0).respond(response("early"));
            IOException failure = new IOException("closed before write callback");

            session.closed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(1, received.size());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void closedSessionRejectsNewOutgoingCall() throws Exception {
        // 关闭后的请求沿用关闭原因，不重新创建交换或网络写入。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("already closed");
            session.closed(failure);
            RequestPayload request = outgoing(23);

            Future<Void> sent = session.send(request);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void drainingSessionRejectsNewOutgoingCall() throws Exception {
        // 排空开始后拒绝新的调用，已经提交的写入仍由传输层处理。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            session.prepareDrain();
            RequestPayload request = outgoing(24);

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof IOException);
            assertSame(sent.getCause(), request.completion().getCause());
            assertTrue(network.writes.isEmpty());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void closedSessionRejectsNewIncomingExchange() throws Exception {
        // 关闭通知之后才到达的 HTTP 交换直接结束，不再交给 Dispatcher。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            IOException failure = new IOException("already closed");
            session.closed(failure);
            HttpExchange exchange = call(2);

            session.receive(exchange);
            assertSame(failure, exchange.completion().getCause());
            assertTrue(received.isEmpty());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void acceptedInvocationDoesNotSendBusinessResponse() throws Exception {
        // Dispatcher 的入队回执不是 Hessian 业务结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
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
    public void incomingResponseWaitsForPhysicalWrite() throws Exception {
        // 业务返回后，发送 Future 和入站调用仍等待真正写出。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "result"));
            assertFalse(sent.isDone());
            assertFalse(request.completion().isDone());

            // 物理写入完成后才能释放调用。
            network.writeResult.completed(null);
            sent.get(1, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            assertEquals(List.of(exchange), network.writes);
        }
    }

    @Test
    public void incomingWriteFailureCompletesExchange() throws Exception {
        // 响应写失败由同一个原始异常结束发送者、业务请求与 HTTP 交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "result"));
            IOException failure = new IOException("response write failed");

            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertSame(failure, exchange.completion().getCause());
        }
    }

    @Test
    public void incomingHttpMetadataReachesDispatcher() throws Exception {
        // 协议保留请求头中的调用元信息，供上层过滤器和 Dispatcher 使用。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, new MemoryNetwork(), received, false);
            Map<String, List<String>> headers = Map.of( //
                    "Content-Type", List.of("application/x-hessian"), "X-Trace", List.of("trace-1"));
            byte[] body = call(2).request().body();
            HttpRequest request = HttpRequest.newBuffered("POST", "/rpc/demo", headers, body, 1, Map.of());

            session.receive(new HttpExchange(request));
            RequestPayload payload = (RequestPayload) received.get(0);
            assertEquals("trace-1", payload.getOption("X-Trace"));
        }
    }

    @Test
    public void responseMetadataReachesHttpReply() throws Exception {
        // 上层响应选项随 Hessian 正文一起写入对应 HTTP 响应。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload response = reply(request.getRequestID(), "result");
            response.addOption("X-Trace", "trace-2");

            session.send(response).get(1, TimeUnit.SECONDS);
            assertEquals("trace-2", exchange.response().getResult().header("X-Trace"));
        }
    }

    @Test
    public void chineseBusinessFailureRemainsInFaultBody() throws Exception {
        // 内核 message 选项携带业务错误说明，不属于可透传的 HTTP 元信息。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload response = reply(request.getRequestID(), null);
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new IllegalArgumentException("业务参数无效"));
            response.addOption("message", "业务参数无效");

            // 中文说明留在 Hessian fault 正文，不能产生无法编码的 HTTP 头部。
            session.send(response).get(1, TimeUnit.SECONDS);
            HttpResponse http = exchange.response().getResult();
            assertNull(http.header("message"));
            IllegalArgumentException failure = assertThrows( //
                    IllegalArgumentException.class, () -> readReply(http.body()));
            assertEquals("业务参数无效", failure.getMessage());
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void requestMetadataWithLineBreakIsRejectedBeforeWrite() throws Exception {
        // 出站元信息包含换行时，在提交 HTTP 交换之前拒绝请求。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(27);
            request.addOption("X-Trace", "trace\r\nInjected: value");

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(sent.getCause().getMessage().contains("X-Trace"));
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void invalidResponseMetadataBecomesFaultAndCompletesCall() throws Exception {
        // 用户自定义响应头不能直接携带中文；校验失败也必须结束当前调用。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload response = reply(request.getRequestID(), "result");
            response.addOption("X-Trace", "不能直接作为响应头");

            // 用标准 Hessian fault 替代无效响应，故障元信息不写入 HTTP 头。
            session.send(response).get(1, TimeUnit.SECONDS);
            HttpResponse http = exchange.response().getResult();
            assertNull(http.header("X-Trace"));
            IllegalArgumentException failure = assertThrows( //
                    IllegalArgumentException.class, () -> readReply(http.body()));
            assertTrue(failure.getMessage().contains("X-Trace"));
            assertTrue(request.completion().isDone());
            assertEquals(List.of(exchange), network.writes);
        }
    }

    @Test
    public void invalidRequestMetadataNameIsRejectedBeforeWrite() throws Exception {
        // HTTP 字段名必须是 token，包含空格的选项名不能成为请求头。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(28);
            request.addOption("X Trace", "value");

            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(sent.getCause().getMessage().contains("X Trace"));
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void duplicateReplySharesPendingWrite() throws Throwable {
        // 同一请求被重复回答时，HTTP 交换只能产生第一份响应。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> first = session.send(reply(request.getRequestID(), "first"));
            Future<Void> second = session.send(reply(request.getRequestID(), "second"));

            assertSame(first, second);
            assertEquals(List.of(exchange), network.writes);
            assertEquals("first", readReply(exchange.response().getResult().body()));
            network.writeResult.completed(null);
            first.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void peerAbortCancelsIncomingInvocation() throws Exception {
        // 对端在业务返回前终止交换，迟到的结果不再产生写入。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            IOException failure = new IOException("peer cancelled");

            exchange.complete(failure);
            assertSame(failure, request.completion().getCause());
            session.send(reply(request.getRequestID(), "late")).get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void drainAnswersOutstandingInvocationWithFault() throws Exception {
        // 排空先补齐尚未回答的 HTTP 位置，不关闭借用的传输通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(1);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            session.prepareDrain();
            byte[] body = exchange.response().getResult().body();
            assertEquals(200, exchange.response().getResult().status());
            assertEquals('r', body[0]);
            IOException failure = assertThrows(IOException.class, () -> readReply(body));
            assertEquals("Hessian connector closing", failure.getMessage());
            assertTrue(request.completion().isDone());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void invocationCancellationWritesHessianFault() throws Exception {
        // 入站请求主动结束时，协议必须提供可消费的失败响应。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            request.complete(new CancellationException("invocation cancelled"));
            byte[] body = exchange.response().getResult().body();
            CancellationException failure = assertThrows(CancellationException.class, () -> readReply(body));
            assertEquals("invocation cancelled", failure.getMessage());
            assertEquals(List.of(exchange), network.writes);
        }
    }

    @Test
    public void closeReleasesResponseStillWriting() throws Exception {
        // 业务已回答但写入仍未结束，关闭必须解除发送者等待。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(2);
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
    public void invalidCallProducesHessianFault() throws Exception {
        // 请求体语法失败不能进入 Dispatcher，也不能遗留没有响应的交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            byte[] incomplete = new byte[] { 'H', 2, 0, 'C' };
            HttpExchange exchange = exchange("POST", "/rpc/demo", "application/x-hessian", incomplete);

            session.receive(exchange);
            byte[] response = exchange.response().getResult().body();
            assertEquals(200, exchange.response().getResult().status());
            assertThrows(HessianServiceException.class, () -> readReply(response));
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void unsupportedMethodProducesHttp405() throws Exception {
        // HTTP 方法校验在 Hessian 解码之前完成。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = exchange("GET", "/rpc/demo", "application/x-hessian", new byte[0]);

            session.receive(exchange);
            HttpResponse response = exchange.response().getResult();
            assertEquals(405, response.status());
            assertEquals("POST", response.header("Allow"));
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void unsupportedMediaTypeProducesHttp415() throws Exception {
        // 非 Hessian 媒体类型不得尝试作为 Hessian 请求执行。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("POST", "/rpc/demo", "application/json", new byte[0]);

            session.receive(exchange);
            assertEquals(415, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void officialProxyMediaTypeReachesDispatcher() throws Exception {
        // Caucho 默认客户端使用 x-application/hessian，兼容其标准请求媒体类型。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, new MemoryNetwork(), received, false);
            byte[] body = call(2).request().body();
            HttpExchange exchange = exchange("POST", "/rpc/demo", "x-application/hessian", body);

            session.receive(exchange);
            assertEquals(1, received.size());
            RequestPayload request = (RequestPayload) received.get(0);
            assertEquals("echo", request.getTargetMethod());
            assertEquals(List.of("value"), request.getParameterValues());
            assertFalse(exchange.response().isDone());
        }
    }

    @Test
    public void unknownMountProducesHttp404() throws Exception {
        // 配置挂载范围外的 URL 不暴露服务。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("POST", "/other/demo", "application/x-hessian", new byte[0]);

            session.receive(exchange);
            assertEquals(404, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void immediateFaultWriteFailureCompletesExchange() throws Exception {
        // 无业务调用关联的错误响应，同样受物理写入失败控制。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);
            byte[] incomplete = new byte[] { 'H', 2, 0, 'C' };
            HttpExchange exchange = exchange("POST", "/rpc/demo", "application/x-hessian", incomplete);
            session.receive(exchange);
            IOException failure = new IOException("fault write failed");

            network.writeResult.failed(failure);
            assertSame(failure, exchange.completion().getCause());
        }
    }

    @Test
    public void unencodableFaultReleasesInvocationAndExchange() throws Exception {
        // 业务异常携带不能序列化的回调，原生 Hessian 编码 detail 时抛出运行时异常。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolConfig config = ProtocolConfig.newConfig("hessian", "hessian", Map.of("contextPath", "/rpc"));
            ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                    context, (action, delay) -> null, network.getLocal(), false);
            HessianSession session = new HessianSession(config, runtime, network, (payload, id) -> {
                received.add(payload);
                throw new UnencodableFailure();
            });
            HttpExchange exchange = call(2);

            // 错误响应无法编码时，当前调用和 HTTP 交换立即失败，不留下待发送正文。
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Throwable failure = exchange.completion().getCause();
            assertTrue(failure instanceof RuntimeException);
            assertTrue(failure.getMessage().contains("Serializable"));
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());

            // 迟到的业务回复已经没有关联位置，发送 Future 正常结束且不新增写入。
            Future<Void> late = session.send(reply(request.getRequestID(), "late"));
            late.get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void inboundSessionRejectsOutgoingRequestPayload() throws Exception {
        // 服务端会话不能被误用为客户端请求发送通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolSession<HttpExchange> session = session(context, network, new ArrayList<>(), false);

            Future<Void> sent = session.send(outgoing(21));
            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(network.writes.isEmpty());
        }
    }

    private static MetContext context() throws Exception {
        MetContext context = new MetConfiguration().setDefaultGroup("wire").buildContext();
        context.publisher().rsfService(Echo.class, value -> value) //
                .name("Echo") //
                .aliasName("hessian", "demo") //
                .serialize("Hessian") //
                .register();
        return context;
    }

    private static ProtocolSession<HttpExchange> session(MetContext context, MemoryNetwork network, //
            List<Payload> received, boolean outbound) {
        // 请求期限属于 Caller；会话单测不允许协议私自建立另一套定时器。
        BiFunction<Runnable, Long, Cancellable> scheduler = (action, delay) -> {
            throw new AssertionError("Hessian must reuse RPC request deadlines");
        };
        ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                context, scheduler, network.getLocal(), outbound);
        ProtocolConfig config = ProtocolConfig.newConfig("hessian", "hessian", Map.of("contextPath", "/rpc"));
        return new HessianSession(config, runtime, network, (payload, id) -> received.add(payload));
    }

    private static RequestPayload outgoing(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.setSerializeType("Hessian");
        request.addParameter(String.class.getName(), "value");
        return request;
    }

    private static ResponsePayload reply(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("Hessian");
        response.setReturnData(value);
        response.setReturnType(String.class.getName());
        return response;
    }

    private static HttpExchange call(int version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AbstractHessianOutput output;
        if (version == 1) {
            output = new HessianOutput(bytes);
        } else {
            output = new Hessian2Output(bytes);
        }
        output.call("echo", new Object[] { "value" });
        output.flush();
        output.close();
        return exchange("POST", "/rpc/demo", "application/x-hessian", bytes.toByteArray());
    }

    private static HttpExchange exchange(String method, String path, String contentType, byte[] body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of(contentType));
        HttpRequest request = HttpRequest.newBuffered(method, path, headers, body, 1, Map.of());
        return new HttpExchange(request);
    }

    private static HttpResponse response(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeString(value);
        output.completeReply();
        output.flush();
        output.close();
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/x-hessian"));
        return HttpResponse.newBuffered(200, headers, bytes.toByteArray(), Map.of());
    }

    private static Object readReply(byte[] body) throws Throwable {
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        if (body[0] == 'H') {
            bytes.skipNBytes(3);
            Hessian2Input input = new Hessian2Input(bytes);
            try {
                return input.readReply(String.class);
            } finally {
                input.close();
            }
        }
        HessianInput input = new HessianInput(bytes);
        try {
            return input.readReply(String.class);
        } finally {
            input.close();
        }
    }

    private static final class UnencodableFailure extends RuntimeException {
        // Runnable 未继承 Serializable，保留该字段以触发真实序列化失败。
        private final Runnable callback = () -> {
        };
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);
        private       int                closes;

        @Override
        public InterAddress getLocal() {
            return new InterAddress("hessian", "127.0.0.1", 8080, "default");
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolSessionContextTestAccess;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransport;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThriftHttpSessionTest {
    @Test
    public void connectionCompletesReadyWithoutWritingHandshake() throws Exception {
        // HTTP 建连即可进入可用状态，不额外发送 Thrift 握手报文。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true);
            assertFalse(session.ready().isDone());

            session.connected();
            session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedPreservesFailureWithoutClosingBorrowedChannel() throws Exception {
        // 会话只释放自身关联，不反向关闭借用的网络通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection refused");

            session.closed(failure);
            assertSame(failure, session.ready().getCause());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void outboundBinaryUsesUnframedBodyAndIndependentSequenceIds() throws Exception {
        // 两个 long 请求 ID 的低 32 位相同，线序号仍必须独立分配。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true);
            session.send(outgoing(5)).get(1, TimeUnit.SECONDS);
            session.send(outgoing((1L << 32) + 5)).get(1, TimeUnit.SECONDS);

            // 官方解码器直接从正文读取消息头，前面没有 framed 长度。
            HttpRequest first = network.writes.get(0).request();
            TProtocol firstInput = input("binary", first.body());
            TMessage firstHeader = firstInput.readMessageBegin();
            TMessage secondHeader = input("binary", network.writes.get(1).request().body()).readMessageBegin();
            InteropService.echo_args args = new InteropService.echo_args();
            args.read(firstInput);
            assertEquals("POST", first.method());
            assertEquals("/rpc", first.uri());
            assertEquals("application/x-thrift", first.header("Content-Type"));
            assertEquals("echo", firstHeader.name);
            assertEquals(TMessageType.CALL, firstHeader.type);
            assertNotEquals(firstHeader.seqid, secondHeader.seqid);
            assertEquals("value", args.getValue());
        }
    }

    @Test
    public void compactOptionUsesNativeCompactBody() throws Exception {
        // 仅切换 Thrift 编码，HTTP 交换仍采用同一个传输契约。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolConfig config = config(Map.of("contextPath", "/rpc", "encoding", "compact"));
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true, config);
            session.send(outgoing(1)).get(1, TimeUnit.SECONDS);

            TProtocol protocol = input("compact", network.writes.get(0).request().body());
            assertEquals("echo", protocol.readMessageBegin().name);
            InteropService.echo_args args = new InteropService.echo_args();
            args.read(protocol);
            assertEquals("value", args.getValue());
        }
    }

    @Test
    public void jsonOptionUsesNativeThriftJsonBody() throws Exception {
        // JSON 编码仍是官方 Thrift JSON，不是普通业务对象 JSON。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolConfig config = config(Map.of("contextPath", "/rpc", "encoding", "json"));
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true, config);
            session.send(outgoing(1)).get(1, TimeUnit.SECONDS);

            TProtocol protocol = input("json", network.writes.get(0).request().body());
            assertEquals("echo", protocol.readMessageBegin().name);
            InteropService.echo_args args = new InteropService.echo_args();
            args.read(protocol);
            assertEquals("value", args.getValue());
        }
    }

    @Test
    public void outboundHttpTwoPreservesRequestMetadata() throws Exception {
        // HTTP/2 选择和业务元数据直接交给共享 HTTP 通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ProtocolConfig config = config(Map.of("contextPath", "/rpc", "httpVersion", "2"));
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true, config);
            RequestPayload request = outgoing(1);
            request.addOption("X-Trace", "trace-1");
            request.addOption("Content-Type", "ignored");

            session.send(request).get(1, TimeUnit.SECONDS);
            HttpRequest http = network.writes.get(0).request();
            assertEquals(2, http.version());
            assertEquals("trace-1", http.header("X-Trace"));
            assertEquals("application/x-thrift", http.header("Content-Type"));
        }
    }

    @Test
    public void inboundCallProducesOfficialThriftReplyWithOriginalIdentity() throws Exception {
        // 官方 Binary CALL 进入内部负载，业务响应保留方法与线序号。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, false);
            HttpExchange exchange = call("echo", TMessageType.CALL, 73, new InteropService.echo_args("request"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            session.send(reply(request.getRequestID(), "answer")).get(1, TimeUnit.SECONDS);
            HttpResponse response = exchange.response().getResult();
            TProtocol protocol = input("binary", response.body());
            TMessage header = protocol.readMessageBegin();
            InteropService.echo_result result = new InteropService.echo_result();
            result.read(protocol);
            assertEquals(200, response.status());
            assertEquals("application/x-thrift", response.header("Content-Type"));
            assertEquals("echo", header.name);
            assertEquals(73, header.seqid);
            assertEquals(TMessageType.REPLY, header.type);
            assertEquals("answer", result.getSuccess());
            assertEquals("request", request.getParameterValues().get(0));
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void ordinaryVoidCallStillProducesReply() throws Exception {
        // IDL 普通 void 需要 REPLY，不能根据返回值为空误判为 ONEWAY。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("ping", TMessageType.CALL, 9, new InteropService.ping_args());
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            session.send(reply(request.getRequestID(), null)).get(1, TimeUnit.SECONDS);
            TProtocol protocol = input("binary", exchange.response().getResult().body());
            assertEquals(TMessageType.REPLY, protocol.readMessageBegin().type);
            new InteropService.ping_result().read(protocol);
            assertFalse(request.isMessage());
        }
    }

    @Test
    public void onewayWaitsForInternalAcceptThenWritesHttp200Empty() throws Exception {
        // 消息入队前不确认；接纳后官方 THttpClient.flush 可以消费 200 空正文。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, false);
            HttpExchange exchange = call("notify", TMessageType.ONEWAY, 12, new InteropService.notify_args("event"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            assertTrue(request.isMessage());
            assertFalse(exchange.response().isDone());

            ResponsePayload accepted = new ResponsePayload();
            accepted.setRequestID(request.getRequestID());
            accepted.setStatus(ProtocolStatus.Accept);
            session.send(accepted).get(1, TimeUnit.SECONDS);
            assertEquals(200, exchange.response().getResult().status());
            assertEquals(0, exchange.response().getResult().body().length);
            assertEquals(List.of(exchange), network.writes);
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void outboundOnewayMapsHttp200EmptyToInternalAccept() throws Exception {
        // 只有远端 HTTP 确认到达后才通知 Caller 接纳成功。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = notification(19);
            session.send(request).get(1, TimeUnit.SECONDS);
            assertTrue(received.isEmpty());

            HttpExchange exchange = network.writes.get(0);
            exchange.respond(HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of()));
            TMessage header = input("binary", exchange.request().body()).readMessageBegin();
            ResponsePayload accepted = (ResponsePayload) received.get(0);
            assertEquals(TMessageType.ONEWAY, header.type);
            assertEquals(ProtocolStatus.Accept, accepted.getStatus());
            assertEquals(19, accepted.getRequestID());
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void rejectedOnewayAdmissionReturnsHttpFailure() throws Exception {
        // Dispatcher 入队失败时不产生成功接纳，也不向 ONEWAY 写业务 Reply。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("notify", TMessageType.ONEWAY, 13, new InteropService.notify_args("event"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload rejected = new ResponsePayload();
            rejected.setRequestID(request.getRequestID());
            rejected.setStatus(ProtocolStatus.QueueFull);
            session.send(rejected).get(1, TimeUnit.SECONDS);

            assertEquals(500, exchange.response().getResult().status());
            assertEquals(0, exchange.response().getResult().body().length);
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void outboundOnewayRejectsHttp204() throws Exception {
        // 官方 Thrift HTTP 客户端只接受 200，204 不能映射为成功接纳。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = notification(20);
            session.send(request).get(1, TimeUnit.SECONDS);

            network.writes.get(0).respond(HttpResponse.newBuffered(204, Map.of(), new byte[0], Map.of()));
            assertTrue(received.get(0) instanceof ThrowPayload);
            assertTrue(request.completion().getCause() instanceof IOException);
        }
    }

    @Test
    public void outboundOnewayRejectsUnexpectedThriftReplyBody() throws Exception {
        // ONEWAY 不读取业务结果，额外 Thrift Reply 属于协议错误。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = notification(21);
            session.send(request).get(1, TimeUnit.SECONDS);

            network.writes.get(0).respond(httpResponse(new byte[] { 1 }));
            assertTrue(received.get(0) instanceof ThrowPayload);
            assertTrue(request.completion().getCause() instanceof IOException);
        }
    }

    @Test
    public void unknownMethodRemainsHttp200WithApplicationException() throws Exception {
        // 消息语法正确、业务方法不存在时，错误仍是合法 Thrift 消息。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("missing", TMessageType.CALL, 51, new InteropService.ping_args());
            session.receive(exchange);

            HttpResponse response = exchange.response().getResult();
            TProtocol protocol = input("binary", response.body());
            TMessage header = protocol.readMessageBegin();
            TApplicationException failure = TApplicationException.readFrom(protocol);
            assertEquals(200, response.status());
            assertEquals(TMessageType.EXCEPTION, header.type);
            assertEquals(51, header.seqid);
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void unknownOnewayMethodCannotPretendToBeAccepted() throws Exception {
        // 未解析到可入队业务的消息必须拒绝，不能返回客户端会解释为 Accept 的 200 空正文。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("missing", TMessageType.ONEWAY, 52, new InteropService.ping_args());
            session.receive(exchange);

            assertEquals(400, exchange.response().getResult().status());
            assertEquals(0, exchange.response().getResult().body().length);
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void declaredExceptionRemainsHttp200WithGeneratedResult() throws Exception {
        // IDL 声明的异常编码进 result 对应字段，而不是 HTTP 错误页。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("fail", TMessageType.CALL, 6, new InteropService.fail_args("reason"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            ResponsePayload response = reply(request.getRequestID(), null);
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new DeclaredFailure(409, "reason"));

            session.send(response).get(1, TimeUnit.SECONDS);
            HttpResponse http = exchange.response().getResult();
            TProtocol protocol = input("binary", http.body());
            assertEquals(TMessageType.REPLY, protocol.readMessageBegin().type);
            InteropService.fail_result result = new InteropService.fail_result();
            result.read(protocol);
            assertEquals(200, http.status());
            assertEquals(409, result.getFailure().getCode());
            assertEquals("reason", result.getFailure().getReason());
        }
    }

    @Test
    public void unsupportedHttpMethodReturns405() throws Exception {
        // HTTP 层错误在 Thrift 正文解析前完成响应。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("GET", "/rpc", "application/x-thrift", new byte[0]);
            session.receive(exchange);

            assertEquals(405, exchange.response().getResult().status());
            assertEquals("POST", exchange.response().getResult().header("Allow"));
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void unsupportedContentTypeReturns415() throws Exception {
        // 普通 JSON 不能被自动解释为 Thrift JSON 协议。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("POST", "/rpc", "application/json", new byte[0]);
            session.receive(exchange);

            assertEquals(415, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void contentTypeParametersAndRequestMetadataAreAccepted() throws Exception {
        // 官方客户端可附带媒体类型参数，原始 HTTP 元数据映射到负载首值。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            byte[] body = encode("binary", "echo", TMessageType.CALL, 2, new InteropService.echo_args("value"));
            Map<String, List<String>> headers = Map.of( //
                    "Content-Type", List.of("application/x-thrift; charset=utf-8"), //
                    "X-Trace", List.of("first", "second"));
            HttpRequest http = HttpRequest.newBuffered("POST", "/rpc?trace=1", headers, body, 1, Map.of());
            session.receive(new HttpExchange(http));

            RequestPayload request = (RequestPayload) received.get(0);
            assertEquals("first", request.getOption("X-Trace"));
            assertEquals("/rpc?trace=1", request.getOption("Location"));
        }
    }

    @Test
    public void malformedThriftBodyReturns400() throws Exception {
        // 语法破损不伪装成 HTTP 200 的业务成功。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("POST", "/rpc", "application/x-thrift", new byte[] { (byte) 0x80 });
            session.receive(exchange);

            assertEquals(400, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void trailingGarbageReturns400() throws Exception {
        // 一个 HTTP 正文只容纳一条 Thrift 消息，消息后垃圾不能被忽略。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            byte[] message = encode("binary", "ping", TMessageType.CALL, 1, new InteropService.ping_args());
            byte[] body = Arrays.copyOf(message, message.length + 1);
            body[body.length - 1] = 1;
            HttpExchange exchange = exchange("POST", "/rpc", "application/x-thrift", body);
            session.receive(exchange);

            assertEquals(400, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void concatenatedMessagesReturn400() throws Exception {
        // HTTP 消息边界不能被当作 TCP 流中的连续调用。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            byte[] message = encode("binary", "ping", TMessageType.CALL, 1, new InteropService.ping_args());
            byte[] body = Arrays.copyOf(message, message.length * 2);
            System.arraycopy(message, 0, body, message.length, message.length);
            HttpExchange exchange = exchange("POST", "/rpc", "application/x-thrift", body);
            session.receive(exchange);

            assertEquals(400, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void oversizedBodyReturns413() throws Exception {
        // 正文长度限制在进入 Thrift 解析器与 Dispatcher 之前检查。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ProtocolConfig config = config(Map.of("contextPath", "/rpc", "maxMessageSize", "16"));
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false, config);
            HttpExchange exchange = exchange("POST", "/rpc", "application/x-thrift", new byte[17]);
            session.receive(exchange);

            assertEquals(413, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void requestOutsideConfiguredMountReturns404() throws Exception {
        // 目录匹配使用路径边界，/rpc2 不属于 /rpc。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = exchange("POST", "/rpc2", "application/x-thrift", new byte[0]);
            session.receive(exchange);

            assertEquals(404, exchange.response().getResult().status());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void outboundResponseChecksSequenceId() throws Exception {
        // 即使响应已关联到 HTTP exchange，也必须核对 Thrift 消息序号。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(9);
            session.send(request).get(1, TimeUnit.SECONDS);
            byte[] body = encode("binary", "echo", TMessageType.REPLY, 999, new InteropService.echo_result("wrong"));
            network.writes.get(0).respond(httpResponse(body));

            assertTrue(received.get(0) instanceof ThrowPayload);
            assertTrue(request.completion().getCause() instanceof IOException);
            assertEquals(1, received.size());
        }
    }

    @Test
    public void outboundResponseChecksMethodName() throws Exception {
        // 相同序号下的方法错误也不能送达原调用。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(10);
            session.send(request).get(1, TimeUnit.SECONDS);
            int seqid = input("binary", network.writes.get(0).request().body()).readMessageBegin().seqid;
            byte[] body = encode("binary", "other", TMessageType.REPLY, seqid, new InteropService.echo_result("wrong"));
            network.writes.get(0).respond(httpResponse(body));

            assertTrue(received.get(0) instanceof ThrowPayload);
            assertTrue(request.completion().getCause() instanceof IOException);
        }
    }

    @Test
    public void outboundResponseDeliversDeclaredExceptionWithoutWrapping() throws Exception {
        // 生成 result 中声明的异常保持原类型，经 ThrowPayload 交给调用层。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(11);
            request.setTargetMethod("fail");
            session.send(request).get(1, TimeUnit.SECONDS);
            int seqid = input("binary", network.writes.get(0).request().body()).readMessageBegin().seqid;
            InteropService.fail_result result = new InteropService.fail_result();
            result.setFailure(new DeclaredFailure(410, "declared"));
            network.writes.get(0).respond(httpResponse(encode("binary", "fail", TMessageType.REPLY, seqid, result)));

            Throwable failure = ((ThrowPayload) received.get(0)).getThrowable();
            assertTrue(failure instanceof DeclaredFailure);
            assertEquals(410, ((DeclaredFailure) failure).getCode());
            assertSame(failure, request.completion().getCause());
        }
    }

    @Test
    public void requestCancellationReleasesOnlyItsOwnExchange() throws Exception {
        // 取消一个调用后，另一个 HTTP 交换仍可接收正常结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload first = outgoing(31);
            RequestPayload second = outgoing(32);
            session.send(first).get(1, TimeUnit.SECONDS);
            session.send(second).get(1, TimeUnit.SECONDS);
            CancellationException cancellation = new CancellationException("cancel first");
            first.complete(cancellation);

            HttpExchange secondExchange = network.writes.get(1);
            int seqid = input("binary", secondExchange.request().body()).readMessageBegin().seqid;
            byte[] body = encode("binary", "echo", TMessageType.REPLY, seqid, new InteropService.echo_result("second"));
            secondExchange.respond(httpResponse(body));
            assertSame(cancellation, network.writes.get(0).completion().getCause());
            assertEquals("second", ((ResponsePayload) received.get(0)).getReturnData());
            assertTrue(second.completion().isDone());
            assertNull(second.completion().getCause());
            assertEquals(1, received.size());
        }
    }

    @Test
    public void writeFailureReleasesRequestAndExchange() throws Exception {
        // 真实写入失败的原因同时结束发送者、调用和 HTTP 关联。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(41);
            Future<Void> sent = session.send(request);
            IOException failure = new IOException("write failed");
            network.writeResult.failed(failure);

            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
        }
    }

    @Test
    public void callerTimeoutReleasesExchangeAndIgnoresLateReply() throws Exception {
        // 期限由调用层结束，会话只释放关联，晚到响应不能重新完成业务调用。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(44);
            session.send(request).get(1, TimeUnit.SECONDS);
            HttpExchange exchange = network.writes.get(0);
            TimeoutException failure = new TimeoutException("caller deadline");
            request.complete(failure);

            int seqid = input("binary", exchange.request().body()).readMessageBegin().seqid;
            byte[] body = encode("binary", "echo", TMessageType.REPLY, seqid, new InteropService.echo_result("late"));
            assertFalse(exchange.respond(httpResponse(body)));
            assertSame(failure, exchange.completion().getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void lateWriteCompletionCannotReleaseReusedRequestId() throws Exception {
        // 旧写回调晚于超时到达时，只能释放旧关联，不能删除复用内部 ID 的新交换。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            BasicFuture<Void> firstWriting = new BasicFuture<>();
            network.writeResult = firstWriting;
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload first = outgoing(45);
            session.send(first);
            first.complete(new TimeoutException("first expired"));

            network.writeResult = new BasicFuture<>((Void) null);
            RequestPayload second = outgoing(45);
            session.send(second).get(1, TimeUnit.SECONDS);
            firstWriting.completed(null);
            HttpExchange exchange = network.writes.get(1);
            int seqid = input("binary", exchange.request().body()).readMessageBegin().seqid;
            byte[] body = encode("binary", "echo", TMessageType.REPLY, seqid, new InteropService.echo_result("second"));
            exchange.respond(httpResponse(body));

            assertEquals(1, received.size());
            assertEquals("second", ((ResponsePayload) received.get(0)).getReturnData());
            assertTrue(second.completion().isDone());
            assertNull(second.completion().getCause());
        }
    }

    @Test
    public void responseBeforeWriteFailureDoesNotDeliverTwice() throws Exception {
        // 响应先到达、物理写回调后失败时，调用层只能收到一次结果。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            Future<Void> sent = session.send(outgoing(42));
            HttpExchange exchange = network.writes.get(0);
            int seqid = input("binary", exchange.request().body()).readMessageBegin().seqid;
            byte[] body = encode("binary", "echo", TMessageType.REPLY, seqid, new InteropService.echo_result("first"));
            exchange.respond(httpResponse(body));
            assertFalse(sent.isDone());

            IOException failure = new IOException("late write failure");
            network.writeResult.failed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(1, received.size());
            assertEquals("first", ((ResponsePayload) received.get(0)).getReturnData());
        }
    }

    @Test
    public void sendCancellationReleasesPendingWrite() throws Exception {
        // 发送 Future 的取消会停止物理写入并释放调用关联。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, true);
            RequestPayload request = outgoing(43);
            Future<Void> sent = session.send(request);
            sent.cancel();

            assertTrue(network.writeResult.isCancelled());
            assertTrue(request.completion().getCause() instanceof CancellationException);
            assertTrue(network.writes.get(0).completion().isDone());
            assertEquals(1, received.size());
        }
    }

    @Test
    public void closeReleasesResponseStillWriting() throws Exception {
        // 响应已建立但写入未完成，关闭必须解除等待且不关闭借用通道。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            network.writeResult = new BasicFuture<>();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, false);
            HttpExchange exchange = call("echo", TMessageType.CALL, 1, new InteropService.echo_args("value"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> sent = session.send(reply(request.getRequestID(), "answer"));
            IOException failure = new IOException("closed while writing");
            session.closed(failure);

            assertSame(failure, sent.getCause());
            assertSame(failure, exchange.completion().getCause());
            assertSame(failure, request.completion().getCause());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void drainAnswersOutstandingCallWithoutClosingBorrowedChannel() throws Exception {
        // 排空补齐当前 HTTP 位置，最终物理关闭由传输所有者执行。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, network, received, false);
            HttpExchange exchange = call("echo", TMessageType.CALL, 1, new InteropService.echo_args("value"));
            session.receive(exchange);
            session.prepareDrain();

            TProtocol protocol = input("binary", exchange.response().getResult().body());
            assertEquals(TMessageType.EXCEPTION, protocol.readMessageBegin().type);
            TApplicationException failure = TApplicationException.readFrom(protocol);
            assertEquals("Thrift HTTP connector closing", failure.getMessage());
            assertTrue(((RequestPayload) received.get(0)).completion().isDone());
            assertEquals(0, network.closes);
        }
    }

    @Test
    public void inboundCancellationWritesApplicationException() throws Exception {
        // 内部调用取消时仍为普通 CALL 提供合法 Thrift 错误响应。
        try (MetContext context = context()) {
            List<Payload> received = new ArrayList<>();
            ThriftHttpSession session = session(context, new MemoryNetwork(), received, false);
            HttpExchange exchange = call("echo", TMessageType.CALL, 4, new InteropService.echo_args("value"));
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            request.complete(new CancellationException("cancelled"));

            TProtocol protocol = input("binary", exchange.response().getResult().body());
            assertEquals(TMessageType.EXCEPTION, protocol.readMessageBegin().type);
            assertEquals("cancelled", TApplicationException.readFrom(protocol).getMessage());
            assertEquals(200, exchange.response().getResult().status());
        }
    }

    @Test
    public void metadataRejectsHeaderInjectionBeforeWriting() throws Exception {
        // 业务元数据不能通过换行构造额外 HTTP 字段。
        try (MetContext context = context()) {
            MemoryNetwork network = new MemoryNetwork();
            ThriftHttpSession session = session(context, network, new ArrayList<>(), true);
            RequestPayload request = outgoing(61);
            request.addOption("X-Trace", "first\r\nInjected: true");
            Future<Void> sent = session.send(request);

            assertTrue(sent.getCause() instanceof IllegalArgumentException);
            assertTrue(network.writes.isEmpty());
        }
    }

    private static MetContext context() throws Exception {
        MetConfiguration configuration = new MetConfiguration() //
                .setDefaultGroup("wire").setDefaultProtocol("thrift-http");
        configuration.connector("http").protocol("thrift-http").contextPath("/rpc");
        MetContext context = configuration.buildContext();
        context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                .name("Demo").aliasName("thrift-http", "Demo") //
                .bindResource(new ThriftServiceResource(ExtendedInteropService.class)).register();
        return context;
    }

    private static ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("thrift-http", "thrift-http", options);
    }

    private static ThriftHttpSession session(MetContext context, MemoryNetwork network, //
            List<Payload> received, boolean outbound) {
        return session(context, network, received, outbound, config(Map.of("contextPath", "/rpc")));
    }

    private static ThriftHttpSession session(MetContext context, MemoryNetwork network, //
            List<Payload> received, boolean outbound, ProtocolConfig config) {
        // 请求期限属于 Caller，协议插件不再建立私有定时器。
        BiFunction<Runnable, Long, Cancellable> scheduler = (action, delay) -> {
            throw new AssertionError("Thrift HTTP must reuse RPC request deadlines");
        };
        ProtocolSessionContext runtime = ProtocolSessionContextTestAccess.newContext( //
                context, scheduler, network.getLocal(), outbound, new RouteMountIndex("http-0", config));
        return new ThriftHttpSession(config, runtime, network, (payload, id) -> received.add(payload));
    }

    private static RequestPayload outgoing(long id) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Demo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.setSerializeType("thrift");
        request.addParameter(String.class.getName(), "value");
        return request;
    }

    private static RequestPayload notification(long id) {
        RequestPayload request = outgoing(id);
        request.setTargetMethod("notify");
        request.setMessage(true);
        return request;
    }

    private static ResponsePayload reply(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnType(String.class.getName());
        response.setReturnData(value);
        return response;
    }

    private static HttpExchange call(String method, byte type, int seqid, TBase<?, ?> args) throws Exception {
        byte[] body = encode("binary", method, type, seqid, args);
        return exchange("POST", "/rpc", "application/x-thrift", body);
    }

    private static HttpExchange exchange(String method, String path, String contentType, byte[] body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of(contentType));
        return new HttpExchange(HttpRequest.newBuffered(method, path, headers, body, 1, Map.of()));
    }

    private static HttpResponse httpResponse(byte[] body) {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/x-thrift"));
        return HttpResponse.newBuffered(200, headers, body, Map.of());
    }

    private static byte[] encode(String encoding, String method, byte type, int seqid, //
            TBase<?, ?> args) throws Exception {
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol protocol = protocol(encoding, output);
        protocol.writeMessageBegin(new TMessage(method, type, seqid));
        args.write(protocol);
        protocol.writeMessageEnd();
        return Arrays.copyOf(output.getArray(), output.length());
    }

    private static TProtocol input(String encoding, byte[] data) throws Exception {
        return protocol(encoding, new TMemoryInputTransport(data));
    }

    private static TProtocol protocol(String encoding, TTransport transport) {
        if (StringUtils.equals(encoding, "compact")) {
            return new TCompactProtocol(transport);
        }
        if (StringUtils.equals(encoding, "json")) {
            return new TJSONProtocol(transport);
        }
        return new TBinaryProtocol(transport);
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes      = new ArrayList<>();
        private       BasicFuture<Void>  writeResult = new BasicFuture<>((Void) null);
        private       int                closes;

        @Override
        public InterAddress getLocal() {
            return new InterAddress("thrift-http", "127.0.0.1", 8080, "default");
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
        public void execute(Runnable action) {
            action.run();
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

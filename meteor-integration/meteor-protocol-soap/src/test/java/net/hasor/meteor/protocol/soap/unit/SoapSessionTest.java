/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.SoapProtocolTestAccess;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.connector.transport.http.channel.SoapHttpRequestTestAccess;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.soap.channel.SoapProtocol;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.support.SignalService;
import org.junit.Test;
import static org.junit.Assert.*;

/** 会话生命周期单元测试：真实编解码器配合可控的有序传输，不启动网络监听。 */
public class SoapSessionTest {
    @Test
    public void connectedCompletesReadiness() throws Exception {
        // 仅构建运行时元数据，协议会话在物理连接通知前保持未就绪。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            assertFalse(session.ready().isDone());

            // 连接事件完成握手 Future，不产生业务消息或 HTTP 写入。
            session.connected();
            session.ready().get(1, TimeUnit.SECONDS);
            assertTrue(received.isEmpty());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void closeBeforeConnectedFailsReadiness() throws Exception {
        // 会话尚未收到 connected，准备真实断连原因。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new ArrayList<>(), true);
            IOException failure = new IOException("connection failed");

            // 就绪等待者得到原始失败，迟到的 connected 不能覆盖它。
            session.closed(failure);
            session.connected();
            assertSame(failure, session.ready().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void ordinaryAcceptKeepsTheExchangeWaitingForItsFinalResponse() throws Exception {
        // 普通 SOAP 操作先进入 Dispatcher，再收到内部入队确认。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // Accept 只结束这次内部发送，不提前结束 HTTP 调用。
            Future<Void> accepted = session.send(this.response(request.getRequestID(), ProtocolStatus.Accept));
            accepted.get(1, TimeUnit.SECONDS);
            assertFalse(exchange.response().isDone());
            assertFalse(request.completion().isDone());
            assertTrue(network.writes.isEmpty());

            // 同一关联仍可接收最终业务响应，并在物理写入后释放。
            Future<Void> sent = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertEquals(List.of(exchange), network.writes);
            network.writeResults.get(0).completed(null);
            network.runTasks();
            sent.get(1, TimeUnit.SECONDS);
            assertEquals(200, exchange.response().getResult().status());
            assertTrue(request.completion().isDone());
        }
    }

    @Test
    public void oneWayAcceptWritesAnEmpty202AndCompletesAfterTheWrite() throws Exception {
        // 消息契约收到 Accept 时，应在线上完成唯一的 one-way 回执。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(SignalService.class, value -> {
            }).aliasName("soap", "SignalService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.signalCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 回执已经编码，但发送 Future 和调用释放都等待物理写入完成。
            Future<Void> sent = session.send(this.response(request.getRequestID(), ProtocolStatus.Accept));
            assertEquals(List.of(exchange), network.writes);
            assertEquals(202, exchange.response().getResult().status());
            assertEquals(0, exchange.response().getResult().body().length);
            assertFalse(sent.isDone());
            assertFalse(request.completion().isDone());

            // 写入完成后不再保留能够接收业务最终响应的关联。
            network.writeResults.get(0).completed(null);
            network.runTasks();
            sent.get(1, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            Future<Void> late = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof IllegalStateException);
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void duplicateInboundResponseCannotOverwriteTheFirstResponse() throws Exception {
        // 首次业务结果已经提交给 transport，但物理写入尚未完成。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            Future<Void> first = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            HttpResponse original = exchange.response().getResult();
            assertFalse(first.isDone());

            // 相同请求的重复响应必须失败，不能重写或额外写出 HTTP 响应。
            Future<Void> duplicate = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertTrue(duplicate.getCause() instanceof IllegalStateException);
            assertSame(original, exchange.response().getResult());
            assertEquals(List.of(exchange), network.writes);

            // 首次发送仍按自己的传输结果完成。
            network.writeResults.get(0).completed(null);
            network.runTasks();
            first.get(1, TimeUnit.SECONDS);
            assertNull(request.completion().getCause());
        }
    }

    @Test
    public void inboundTimeoutWritesOneFaultAndRejectsTheLateBusinessResponse() throws Exception {
        // 普通入站调用已经分发，业务尚未给出响应。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // RPC 截止时间结束调用，会话在自己的有序执行器上生成一个 Fault。
            TimeoutException failure = new TimeoutException("business deadline");
            request.complete(failure);
            network.runTasks();
            assertEquals(List.of(exchange), network.writes);
            assertEquals(500, exchange.response().getResult().status());
            assertSame(failure, request.completion().getCause());

            // 超时后才到达的业务响应不能再占用该交换。
            Future<Void> late = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof IllegalStateException);
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void inboundTimeoutWinsBeforeItsQueuedCleanupRuns() throws Exception {
        // 入站调用已超时，但结束回调还在连接有序执行器中等待清理 pending。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 已终结状态立即拒绝竞争到达的业务结果，pending 留给已排队回调生成 Fault。
            TimeoutException failure = new TimeoutException("business deadline");
            request.complete(failure);
            Future<Void> late = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof CancellationException);
            assertTrue(network.writes.isEmpty());

            // 清理回调只写出超时 Fault，迟到成功没有占用 HTTP 交换。
            network.runTasks();
            assertEquals(List.of(exchange), network.writes);
            assertEquals(500, exchange.response().getResult().status());
            assertSame(failure, request.completion().getCause());
        }
    }

    @Test
    public void failedInboundExchangeReleasesItsRequestWithoutWritingAFault() throws Exception {
        // 服务端已分发请求，随后对端提前结束 HTTP 交换。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 交换失败传播给业务请求，不能尝试向已结束的交换回送 Fault。
            IOException failure = new IOException("peer disconnected");
            exchange.complete(failure);
            network.runTasks();
            assertSame(failure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
            Future<Void> late = session.send(this.response(request.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void closingInboundSessionReleasesAllPendingExchangesWithoutWriting() throws Exception {
        // 同一会话持有两个尚未完成的入站交换。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange first = this.echoCall(context);
            HttpExchange second = this.echoCall(context);
            session.receive(first);
            session.receive(second);
            RequestPayload firstRequest = (RequestPayload) received.get(0);
            RequestPayload secondRequest = (RequestPayload) received.get(1);

            // 断连先清除全部关联，再以同一原因释放交换及业务请求。
            IOException failure = new IOException("channel closed");
            session.closed(failure);
            network.runTasks();
            assertSame(failure, first.completion().getCause());
            assertSame(failure, second.completion().getCause());
            assertSame(failure, firstRequest.completion().getCause());
            assertSame(failure, secondRequest.completion().getCause());
            assertTrue(network.writes.isEmpty());
            Future<Void> late = session.send(this.response(firstRequest.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void closingInboundSessionWithoutCauseUsesAnExplicitFailure() throws Exception {
        // 入站交换仍在等待业务结果，transport 关闭通知没有携带异常。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange exchange = this.echoCall(context);
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);

            // 无原因关闭仍必须以显式 IOException 结束交换和请求，不能被解释为成功。
            session.closed(null);
            network.runTasks();
            Throwable exchangeFailure = exchange.completion().getCause();
            assertTrue(exchangeFailure instanceof IOException);
            assertEquals("SOAP session closed", exchangeFailure.getMessage());
            assertSame(exchangeFailure, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void inboundDrainWritesFaultsBeforeReleasingPendingRequests() throws Exception {
        // 协议分区有两个待响应交换，物理连接由共享 HTTP transport 管理。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpExchange first = this.echoCall(context);
            HttpExchange second = this.echoCall(context);
            session.receive(first);
            session.receive(second);

            // 排空阶段先补齐 HTTP Fault，不主动关闭共享的入站物理连接。
            session.prepareDrain();
            network.runTasks();
            assertEquals(2, network.writes.size());
            assertEquals(500, first.response().getResult().status());
            assertEquals(500, second.response().getResult().status());
            RequestPayload firstRequest = (RequestPayload) received.get(0);
            RequestPayload secondRequest = (RequestPayload) received.get(1);
            assertTrue(firstRequest.completion().getCause() instanceof IOException);
            assertTrue(secondRequest.completion().getCause() instanceof IOException);
            assertFalse(network.draining);

            // 重复 prepareDrain 不生成重复响应，也不重新接受业务结果。
            session.prepareDrain();
            Future<Void> late = session.send(this.response(firstRequest.getRequestID(), ProtocolStatus.OK));
            assertTrue(late.getCause() instanceof IllegalStateException);
            assertEquals(2, network.writes.size());
        }
    }

    @Test
    public void outboundDrainDoesNotCloseTheBorrowedTransport() throws Exception {
        // 协议会话只清理自己的 pending，物理连接仍由 HTTP transport 管理。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ControlledNetwork network = new ControlledNetwork();
            ProtocolSession<HttpExchange> session = this.session(context, network, new ArrayList<>(), true);
            session.connected();

            // 没有入站 pending 时不写消息，也不改变借入连接的生命周期。
            session.prepareDrain();
            assertFalse(network.draining);
            assertTrue(network.isOpen());
            assertTrue(network.writes.isEmpty());
        }
    }

    @Test
    public void completedOutboundRequestIsRejectedBeforeEncodingOrWriting() throws Exception {
        // 已终结的 RPC 不再需要契约查找，也不能进入物理发送队列。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = new RequestPayload();
            TimeoutException expired = new TimeoutException("already expired");
            request.complete(expired);

            // 直接返回本地取消失败，保留原始 RPC 结束原因。
            Future<Void> sent = session.send(request);
            assertTrue(sent.getCause() instanceof CancellationException);
            assertSame(expired, request.completion().getCause());
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void cancellingOutboundWriteCancelsTransportAndReleasesTheExchange() throws Exception {
        // 出站请求已经提交，但 HTTP 写入仍在等待完成。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            Future<Void> sent = session.send(request);
            assertFalse(sent.isDone());

            // 调用者取消发送，传输写入、HTTP 交换和 RPC 关联同时结束。
            assertTrue(sent.cancel());
            network.runTasks();
            assertTrue(network.writeResults.get(0).isCancelled());
            assertTrue(request.completion().getCause() instanceof CancellationException);
            HttpExchange exchange = network.writes.get(0);
            assertTrue(exchange.completion().getCause() instanceof CancellationException);
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void rpcTimeoutSuppressesItsLocallyGeneratedHttpFailureNotification() throws Exception {
        // 请求写入成功，仍在等待远端 HTTP 响应。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            session.send(request);
            network.writeResults.get(0).completed(null);

            // RPC 截止时间触发 HTTP 交换释放，该释放事件不能再次报告同一个调用失败。
            TimeoutException failure = new TimeoutException("RPC deadline");
            request.complete(failure);
            network.runTasks();
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertSame(failure, request.completion().getCause());
            assertTrue(received.isEmpty());
            assertFalse(network.writes.get(0).respond(this.echoResponse()));
        }
    }

    @Test
    public void queuedSuccessfulResponseIsIgnoredAfterRpcCancellation() throws Exception {
        // HTTP 响应已到达，协议解码回调仍排在连接执行器中。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            session.send(request);
            network.writeResults.get(0).completed(null);
            HttpExchange exchange = network.writes.get(0);
            assertTrue(exchange.respond(this.echoResponse()));
            exchange.complete(null);
            assertTrue(received.isEmpty());

            // 在已排队回调执行前取消 RPC，迟到成功不能再次投递给 Caller。
            CancellationException failure = new CancellationException("caller cancelled");
            request.complete(failure);
            network.runTasks();
            assertTrue(received.isEmpty());
            assertSame(failure, request.completion().getCause());
        }
    }

    @Test
    public void duplicateRemoteResponsesAreDeliveredOnlyOnce() throws Exception {
        // 请求已发送，对端准备重复完成同一个 HTTP exchange。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            session.send(request);
            network.writeResults.get(0).completed(null);
            HttpExchange exchange = network.writes.get(0);

            // 交换只接受第一个响应，协议只投递一条关联到原请求的结果。
            assertTrue(exchange.respond(this.echoResponse()));
            assertFalse(exchange.respond(this.echoResponse()));
            exchange.complete(null);
            network.runTasks();
            assertEquals(1, received.size());
            ResponsePayload response = (ResponsePayload) received.get(0);
            assertEquals(request.getRequestID(), response.getRequestID());
            assertEquals("result", response.getReturnData());
            assertTrue(request.completion().isDone());
            assertNull(request.completion().getCause());
        }
    }

    @Test
    public void outboundWriteFailurePreservesTheTransportCause() throws Exception {
        // 出站请求等待实际传输完成，写失败将同时结束 HTTP exchange。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            Future<Void> sent = session.send(request);

            // 保留原始传输异常，只向 Caller 投递一次失败通知。
            IOException failure = new IOException("HTTP write failed");
            network.writeResults.get(0).failed(failure);
            network.runTasks();
            assertSame(failure, sent.getCause());
            assertSame(failure, request.completion().getCause());
            assertSame(failure, network.writes.get(0).completion().getCause());
            assertEquals(1, received.size());
            assertSame(failure, ((ThrowPayload) received.get(0)).getThrowable());
        }
    }

    @Test
    public void malformedRemoteResponseCompletesTheRpcWithADecodeFailure() throws Exception {
        // HTTP 层成功收到响应，但正文不是合法的 SOAP Envelope。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EchoService> service = context.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, true);
            session.connected();
            RequestPayload request = this.outgoing(service);
            session.send(request);
            network.writeResults.get(0).completed(null);
            HttpExchange exchange = network.writes.get(0);
            byte[] invalid = "<invalid/>".getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> headers = Map.of("Content-Type", List.of("text/xml; charset=utf-8"));
            exchange.respond(HttpResponse.newBuffered(200, headers, invalid, Map.of()));
            exchange.complete(null);

            // 解码错误完成原 RPC 并投递失败，不能留下一个永远等待业务响应的请求。
            network.runTasks();
            assertEquals(1, received.size());
            Throwable failure = ((ThrowPayload) received.get(0)).getThrowable();
            assertTrue(failure instanceof IllegalArgumentException);
            assertSame(failure, request.completion().getCause());
        }
    }

    @Test
    public void immediateFaultWriteFailureCompletesTheExchangeWithItsTransportCause() throws Exception {
        // 传输拒绝写入时尚未接管 exchange，模拟只完成写 Future 的失败路径。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            network.completeExchanges = false;
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpRequest request = this.echoCall(context).request();
            byte[] body = "<invalid/>".getBytes(StandardCharsets.UTF_8);
            HttpRequest invalid = HttpRequest.newBuffered(request.method(), request.uri(), //
                    request.headers(), body, 1, Map.of());
            HttpExchange exchange = new HttpExchange(this.route(context, invalid));

            // 非法 Envelope 立即生成 Fault，尚未进入业务分派或完成传输。
            session.receive(exchange);
            assertEquals(List.of(exchange), network.writes);
            assertEquals(500, exchange.response().getResult().status());
            assertFalse(exchange.completion().isDone());
            assertTrue(received.isEmpty());

            // 即时回复必须观察发送失败，并原样结束 exchange。
            IOException failure = new IOException("immediate HTTP reply rejected");
            network.writeResults.get(0).failed(failure);
            network.runTasks();
            assertSame(failure, exchange.completion().getCause());
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void immediateFaultWriteCancellationCompletesTheExchange() throws Exception {
        // 传输取消写入时只完成写 Future，exchange 由即时回复路径负责释放。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService").register();
            ControlledNetwork network = new ControlledNetwork();
            network.completeExchanges = false;
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = this.session(context, network, received, false);
            session.connected();
            HttpRequest request = this.echoCall(context).request();
            byte[] body = "<invalid/>".getBytes(StandardCharsets.UTF_8);
            HttpRequest invalid = HttpRequest.newBuffered(request.method(), request.uri(), //
                    request.headers(), body, 1, Map.of());
            HttpExchange exchange = new HttpExchange(this.route(context, invalid));

            // Fault 已编码，但取消发生前交换仍等待物理写入。
            session.receive(exchange);
            assertEquals(List.of(exchange), network.writes);
            assertEquals(500, exchange.response().getResult().status());
            assertFalse(exchange.completion().isDone());
            assertTrue(received.isEmpty());

            // 取消产生显式的 exchange 失败，不能留下悬空的即时响应。
            network.writeResults.get(0).cancel();
            network.runTasks();
            assertTrue(exchange.completion().getCause() instanceof CancellationException);
            assertEquals(1, network.writes.size());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").protocol("soap").contextPath("/services");
        return configuration;
    }

    private ProtocolSession<HttpExchange> session(MetContext context, ControlledNetwork network, //
            List<Payload> received, boolean outbound) {
        ProtocolConfig config = context.getSettings().getConnectorConfigs().iterator().next().protocol("soap");
        ProtocolSessionContext protocolContext = SoapProtocolTestAccess.context(context, (action, delay) -> {
            throw new AssertionError("SOAP session must use RPC deadlines");
        }, network.getLocal(), outbound);
        return new SoapProtocol().createSession( //
                config, protocolContext, network, (payload, id) -> received.add(payload));
    }

    private HttpExchange echoCall(MetContext context) {
        String body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" //
                + "<m:echo xmlns:m=\"urn:hasor:meteor:soap:EchoService\"><arg0>request</arg0></m:echo>" //
                + "</s:Body></s:Envelope>";
        Map<String, List<String>> headers = Map.of(//
                "Content-Type", List.of("text/xml; charset=utf-8"), //
                "SOAPAction", List.of("\"urn:hasor:meteor:soap:EchoService/echo\""));
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuffered("POST", "/services/EchoService", headers, content, 1, Map.of());
        return new HttpExchange(this.route(context, request));
    }

    private HttpExchange signalCall(MetContext context) {
        String body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" //
                + "<m:signal xmlns:m=\"urn:hasor:meteor:soap:SignalService\"><arg0>request</arg0></m:signal>" //
                + "</s:Body></s:Envelope>";
        Map<String, List<String>> headers = Map.of(//
                "Content-Type", List.of("text/xml; charset=utf-8"), //
                "SOAPAction", List.of("\"urn:hasor:meteor:soap:SignalService/signal\""));
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuffered("POST", "/services/SignalService", headers, content, 1, Map.of());
        return new HttpExchange(this.route(context, request));
    }

    private HttpRequest route(MetContext context, HttpRequest request) {
        ProtocolConfig config = context.getSettings().getConnectorConfigs().iterator().next().protocol("soap");
        return SoapHttpRequestTestAccess.route(request, SoapProtocolTestAccess.selection(config, request.uri()));
    }

    private HttpResponse echoResponse() {
        String body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" //
                + "<m:echoResponse xmlns:m=\"urn:hasor:meteor:soap:EchoService\"><return>result</return>" //
                + "</m:echoResponse></s:Body></s:Envelope>";
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("text/xml; charset=utf-8"));
        return HttpResponse.newBuffered(200, headers, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private RequestPayload outgoing(MetBindInfo<?> service) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(101);
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), "request");
        return request;
    }

    private ResponsePayload response(long requestId, short status) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(requestId);
        response.setStatus(status);
        response.setReturnData("result");
        response.setReturnType(String.class.getName());
        return response;
    }

    private static final class ControlledNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange>      writes            = new ArrayList<>();
        private final List<BasicFuture<Void>> writeResults      = new ArrayList<>();
        private final Queue<Runnable>         tasks             = new ArrayDeque<>();
        private       boolean                 open              = true;
        private       boolean                 draining;
        private       boolean                 completeExchanges = true;

        @Override
        public InterAddress getLocal() {
            return new InterAddress("soap", "127.0.0.1", 2181, "default");
        }

        @Override
        public InterAddress getRemote() {
            return this.getLocal();
        }

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public Future<Void> write(HttpExchange exchange) {
            // 只模拟传输层写入完成与交换释放，SOAP 编解码完全由真实会话执行。
            BasicFuture<Void> written = new BasicFuture<>();
            this.writes.add(exchange);
            this.writeResults.add(written);
            written.onFinal(done -> {
                if (!this.completeExchanges) {
                    return;
                }
                if (done.isCancelled()) {
                    exchange.complete(new CancellationException("transport write cancelled"));
                } else if (done.getCause() != null) {
                    exchange.complete(done.getCause());
                } else if (exchange.response().isDone()) {
                    exchange.complete(null);
                }
            });
            return written;
        }

        @Override
        public void execute(Runnable task) {
            this.tasks.add(task);
        }

        private void runTasks() {
            // 明确推进已排队回调，测试可在收到响应与处理响应之间制造取消交错。
            while (!this.tasks.isEmpty()) {
                this.tasks.remove().run();
            }
        }

        @Override
        public Future<Void> close() {
            this.open = false;
            return new BasicFuture<>((Void) null);
        }

        @Override
        public Future<Void> drainAndClose() {
            this.draining = true;
            return new BasicFuture<>();
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseSessionTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void unifiedSessionCorrelatesReversedRepliesAndDoesNotWriteAcceptAcknowledgements() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange first = call(protocol, "first");
            HttpExchange second = call(protocol, "second");
            session.receive(first);
            session.receive(second);
            RequestPayload one = (RequestPayload) received.get(0);
            RequestPayload two = (RequestPayload) received.get(1);
            ResponsePayload accepted = reply(one.getRequestID(), "ignored");
            accepted.setStatus(ProtocolStatus.Accept);
            session.send(accepted).get(5, TimeUnit.SECONDS);
            assertTrue(network.writes.isEmpty());

            // 响应逆序提交时仍对应各自的 HTTP 交换。
            session.send(reply(two.getRequestID(), "second-reply")).get(5, TimeUnit.SECONDS);
            session.send(reply(one.getRequestID(), "first-reply")).get(5, TimeUnit.SECONDS);
            assertSame(second, network.writes.get(0));
            assertSame(first, network.writes.get(1));
            HttpResponse secondWire = second.response().get(5, TimeUnit.SECONDS);
            HttpResponse firstWire = first.response().get(5, TimeUnit.SECONDS);
            assertEquals("second-reply", protocol.decode(two.getRequestID(), secondWire).getReturnData());
            assertEquals("first-reply", protocol.decode(one.getRequestID(), firstWire).getReturnData());
        }
    }

    @Test
    public void unifiedSessionFinishesExpiredRequestsAndRejectsLateReplies() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            HttpExchange exchange = call(protocol, "expire");
            session.receive(exchange);
            RequestPayload request = (RequestPayload) received.get(0);
            request.complete(new TimeoutException("expired"));
            assertSame(exchange, network.writes.get(0));
            HttpResponse wire = exchange.response().get(5, TimeUnit.SECONDS);
            ResponsePayload expired = protocol.decode(request.getRequestID(), wire);
            assertEquals(ProtocolStatus.InvokeError, expired.getStatus());
            assertNotNull(session.send(reply(request.getRequestID(), "late")).getCause());
            assertEquals(1, network.writes.size());
        }
    }

    @Test
    public void unifiedSessionDrainReleasesEveryUnansweredExchange() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(call(protocol, "one"));
            session.receive(call(protocol, "two"));
            session.prepareDrain();
            assertEquals(2, network.writes.size());
            for (HttpExchange exchange : network.writes) {
                HttpResponse wire = exchange.response().get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolStatus.InvokeError, protocol.decode(0, wire).getStatus());
            }
            RequestPayload first = (RequestPayload) received.get(0);
            assertNotNull(session.send(reply(first.getRequestID(), "late")).getCause());
        }
    }

    @Test
    public void unifiedSessionCloseCompletesAllInboundRequests() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, false);
            session.receive(call(protocol, "one"));
            session.receive(call(protocol, "two"));
            IOException failure = new IOException("closed");
            session.closed(failure);
            assertTrue(network.writes.isEmpty());
            for (Payload message : received) {
                assertSame(failure, ((RequestPayload) message).completion().getCause());
            }
        }
    }

    @Test
    public void unifiedSessionMapsOutboundCompletionAndFailureToPayloads() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();

            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);
            RequestPayload first = outgoing(20, "first");
            session.send(first).get(5, TimeUnit.SECONDS);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HproseUtils.parseResponse(20, reply(20, "answer"), body);
            network.writes.get(0).respond(new HttpResponse(200, Collections.emptyMap(), body.toByteArray()));
            assertEquals("answer", ((ResponsePayload) received.get(0)).getReturnData());
            assertEquals(20, ((ResponsePayload) received.get(0)).getRequestID());
            assertTrue(first.completion().isDone());

            // 下一次网络失败只能结束对应请求，原始原因通过 ThrowPayload 交付。
            RequestPayload next = outgoing(21, "fail");
            session.send(next).get(5, TimeUnit.SECONDS);
            IOException failure = new IOException("network closed");
            network.writes.get(1).complete(failure);
            assertSame(failure, ((ThrowPayload) received.get(1)).getThrowable());
            assertSame(failure, next.completion().getCause());
        }
    }

    @Test
    public void malformedOutboundReplyFailsOnlyItsRequest() throws Exception {
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
            network.writes.get(0).respond(new HttpResponse(200, Map.of(), new byte[] { '?' }));
            assertEquals(1, received.size());
            ThrowPayload failed = (ThrowPayload) received.get(0);
            assertTrue(failed.getThrowable() instanceof IOException);
            assertSame(failed.getThrowable(), request.completion().getCause());
            assertFalse(next.completion().isDone());

            // 另一条在途请求不受影响，仍能接收完整响应。
            network.writes.get(1).respond(new HttpResponse(200, Map.of(), new byte[] { 'R', 'n', 'z' }));
            ResponsePayload response = (ResponsePayload) received.get(1);
            assertEquals(51, response.getRequestID());
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertNull(next.completion().getCause());
            assertTrue(next.completion().isDone());
        }
    }

    @Test
    public void completedRequestsAndWrongDirectionPayloadsDoNotWrite() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).name("Echo").register();
            MemoryNetwork network = new MemoryNetwork();
            List<Payload> received = new ArrayList<>();
            ProtocolSession<HttpExchange> session = session(context, network, received, true);

            // 已结束请求不能因为稍后调用 send 而再次发出。
            RequestPayload expired = outgoing(60, "expired");
            expired.complete(new TimeoutException("deadline"));
            assertTrue(session.send(expired).getCause() instanceof CancellationException);

            // 出站会话不接受响应包，入站会话不接受主动请求包。
            assertTrue(session.send(reply(60, "reply")).getCause() instanceof UnsupportedOperationException);
            ProtocolSession<HttpExchange> inbound = session(context, network, received, false);
            Future<Void> wrongDirection = inbound.send(outgoing(61, "wrong-direction"));
            assertTrue(wrongDirection.getCause() instanceof UnsupportedOperationException);
            assertTrue(network.writes.isEmpty());
            assertTrue(received.isEmpty());
        }
    }

    private static ProtocolSession<HttpExchange> session(MetContext runtime, MemoryNetwork network, List<Payload> received, boolean outbound) {
        ProtocolContext context = new ProtocolContext(runtime, (action, delay) -> {
            throw new AssertionError("Hprose must use RPC deadlines rather than schedule its own timeout");
        }, new InterAddress("hprose", "127.0.0.1", 8080, "default"), outbound);
        ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
        HproseProtocol factory = new HproseProtocol();
        BiConsumer<Long, Payload> messages = (id, payload) -> received.add(payload);
        ProtocolSession<HttpExchange> session = factory.create(config, context, network, messages);
        session.connected();
        assertTrue(session.ready().isDone());
        return session;
    }

    private static HttpExchange call(HproseCodec protocol, String value) throws Exception {
        return new HttpExchange(protocol.encode(outgoing(1, value)));
    }

    private static RequestPayload outgoing(long id, String value) {
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
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        response.setReturnType(String.class.getName());
        return response;
    }

    private static final class MemoryNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes = new ArrayList<>();

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
            this.writes.add(message);
            return new BasicFuture<>((Void) null);
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.TimeoutException;
import hprose.io.HproseReader;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorManager;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.ReceivedListener;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.connector.protocol.EndpointConnectorFactory;
import net.hasor.rsf.connector.protocol.ProtocolContext;
import net.hasor.rsf.connector.protocol.ProtocolSession;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.connector.transport.http.HttpExchange;
import net.hasor.rsf.connector.transport.http.HttpRequest;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfServiceType;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseProtocolTest {
    private static final ReceivedListener RECEIVER = new ReceivedListener() {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
            throw new AssertionError("Unexpected response");
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            throw new AssertionError(failure.getThrowable());
        }
    };

    public interface Echo {
        String echo(String value);
    }

    private final ConnectorManager manager = subscribedManager(services(), RECEIVER);

    @After
    public void close() {
        this.manager.close();
    }

    private final InterAddress address;
    private final HproseCodec  protocol;

    public HproseProtocolTest() throws Exception {
        this.address = new InterAddress("hprose://127.0.0.1:8080/default");
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "http");
        options.put("contextPath", "/rpc");
        this.protocol = new HproseCodec(new ProtocolConfig("hprose", "hprose", options), this.manager.context());
    }

    @Test
    public void callsDecodeUsingMetadataAndHaveIndependentIdsAndResponseEncoders() throws Exception {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup("RSF");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.addParameter("java.lang.String", "中文🙂");
        HttpRequest wire = this.protocol.encode(this.address, request);
        assertEquals("/rpc/RSF/Echo/1.0.0", wire.uri());
        HproseInvocation first = this.protocol.receive(wire), second = this.protocol.receive(wire);
        assertNotEquals(first.request().getRequestID(), second.request().getRequestID());
        assertEquals("中文🙂", first.request().getParameterValues().get(0));
        assertEquals("java.lang.String", first.request().getParameterTypes().get(0));
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(first.request().getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData("结果🙂");
        ResponsePayload decoded = this.protocol.decode(91, first.encoder().encode(response));
        assertEquals(91, decoded.getRequestID());
        assertEquals("结果🙂", decoded.getReturnData());
    }

    @Test
    public void functionListAndPathMismatchAreLocalHttpResponses() throws Exception {
        HproseInvocation functions = this.protocol.receive(new HttpRequest("POST", "/rpc", Collections.emptyMap(), new byte[] { 'z' }));
        assertNull(functions.request());
        assertEquals('F', functions.immediate().body()[0]);
        String[] names = new HproseReader(new ByteArrayInputStream(Arrays.copyOfRange(functions.immediate().body(), 1, functions.immediate().body().length))).unserialize(String[].class);
        assertTrue(Arrays.asList(names).contains("echo_echo"));
        assertEquals(404, this.protocol.receive(new HttpRequest("POST", "/rpc-other", Collections.emptyMap(), new byte[] { 'z' })).immediate().status());
    }

    @Test
    public void unicodeBusinessErrorsRemainFailedResponses() throws Exception {
        ResponsePayload error = new ResponsePayload();
        error.setStatus(ProtocolStatus.NotFound);
        error.addOption("message", "不存在🙂");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HproseUtils.parseResponse(7, error, bytes);
        ResponsePayload decoded = this.protocol.decode(7, new HttpResponse(200, Collections.emptyMap(), bytes.toByteArray()));
        assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
        assertEquals("不存在🙂", decoded.getOption("message"));
        ResponsePayload plain = this.protocol.decode(8, this.protocol.error(new IOException("plain error")));
        assertEquals(ProtocolStatus.InvokeError, plain.getStatus());
        assertEquals("plain error", plain.getOption("message"));
    }

    @Test
    public void malformedOrTruncatedSuccessfulResponsesAreRejected() throws Exception {
        for (byte[] body : new byte[][] { new byte[0], new byte[] { 'R', 'n' }, new byte[] { 'R', 'n', 'z', 'x' }, new byte[] { '?' } }) {
            try {
                this.protocol.decode(1, new HttpResponse(200, Collections.emptyMap(), body));
                fail();
            } catch (IOException expected) {
            }
        }
        assertNull(this.protocol.decode(1, new HttpResponse(200, Collections.emptyMap(), new byte[] { 'R', 'n', 'z' })).getReturnData());
    }

    @Test
    public void unifiedSessionCorrelatesReversedRepliesAndDoesNotWriteAcceptAcknowledgements() throws Exception {
        MemoryNetwork network = new MemoryNetwork();
        List<Payload> received = new ArrayList<>();
        ProtocolSession<HttpExchange> session = this.session(network, received, false);
        HttpExchange first = this.call("first"), second = this.call("second");
        session.receive(first);
        session.receive(second);
        RequestPayload one = (RequestPayload) received.get(0), two = (RequestPayload) received.get(1);
        ResponsePayload accepted = reply(one.getRequestID(), "ignored");
        accepted.setStatus(ProtocolStatus.Accept);
        session.send(accepted).get();
        assertTrue(network.writes.isEmpty());
        session.send(reply(two.getRequestID(), "second-reply")).get();
        session.send(reply(one.getRequestID(), "first-reply")).get();
        assertSame(second, network.writes.get(0));
        assertSame(first, network.writes.get(1));
        assertEquals("second-reply", this.protocol.decode(two.getRequestID(), second.response().get()).getReturnData());
        assertEquals("first-reply", this.protocol.decode(one.getRequestID(), first.response().get()).getReturnData());
    }

    @Test
    public void unifiedSessionFinishesExpiredRequestsAndRejectsLateReplies() throws Exception {
        MemoryNetwork network = new MemoryNetwork();
        List<Payload> received = new ArrayList<>();
        ProtocolSession<HttpExchange> session = this.session(network, received, false);
        HttpExchange exchange = this.call("expire");
        session.receive(exchange);
        RequestPayload request = (RequestPayload) received.get(0);
        request.complete(new TimeoutException("expired"));
        assertSame(exchange, network.writes.get(0));
        assertEquals(ProtocolStatus.InvokeError, this.protocol.decode(request.getRequestID(), exchange.response().get()).getStatus());
        assertNotNull(session.send(reply(request.getRequestID(), "late")).getCause());
        assertEquals(1, network.writes.size());
    }

    @Test
    public void unifiedSessionDrainReleasesEveryUnansweredExchange() throws Exception {
        MemoryNetwork network = new MemoryNetwork();
        List<Payload> received = new ArrayList<>();
        ProtocolSession<HttpExchange> session = this.session(network, received, false);
        session.receive(this.call("one"));
        session.receive(this.call("two"));
        session.prepareDrain();
        assertEquals(2, network.writes.size());
        for (HttpExchange exchange : network.writes) {
            assertEquals(ProtocolStatus.InvokeError, this.protocol.decode(0, exchange.response().get()).getStatus());
        }
        assertNotNull(session.send(reply(((RequestPayload) received.get(0)).getRequestID(), "late")).getCause());
    }

    @Test
    public void unifiedSessionCloseCompletesAllInboundRequests() throws Exception {
        MemoryNetwork network = new MemoryNetwork();
        List<Payload> received = new ArrayList<>();
        ProtocolSession<HttpExchange> session = this.session(network, received, false);
        session.receive(this.call("one"));
        session.receive(this.call("two"));
        IOException failure = new IOException("closed");
        session.closed(failure);
        assertTrue(network.writes.isEmpty());
        for (Payload message : received) {
            assertSame(failure, ((RequestPayload) message).completion().getCause());
        }
    }

    @Test
    public void unifiedSessionMapsOutboundCompletionAndFailureToPayloads() throws Exception {
        MemoryNetwork network = new MemoryNetwork();
        List<Payload> received = new ArrayList<>();
        ProtocolSession<HttpExchange> session = this.session(network, received, true);
        RequestPayload first = outgoing(20, "first");
        session.send(first).get();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HproseUtils.parseResponse(20, reply(20, "answer"), body);
        network.writes.get(0).respond(new HttpResponse(200, Collections.emptyMap(), body.toByteArray()));
        assertEquals("answer", ((ResponsePayload) received.get(0)).getReturnData());
        assertEquals(20, ((ResponsePayload) received.get(0)).getRequestID());
        assertTrue(first.completion().isDone());
        RequestPayload next = outgoing(21, "fail");
        session.send(next).get();
        IOException failure = new IOException("network closed");
        network.writes.get(1).complete(failure);
        assertSame(failure, ((ThrowPayload) received.get(1)).getThrowable());
        assertSame(failure, next.completion().getCause());
    }

    private ProtocolSession<HttpExchange> session(MemoryNetwork network, List<Payload> received, boolean outbound) {
        ProtocolContext context = new ProtocolContext(this.manager.context(), this.manager::schedule, this.address, outbound);
        ProtocolSession<HttpExchange> session = new HproseProtocol().create(new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc")), context, network, (id, payload) -> received.add(payload));
        session.connected();
        assertTrue(session.ready().isDone());
        return session;
    }

    private HttpExchange call(String value) throws Exception {
        return new HttpExchange(this.protocol.encode(this.address, outgoing(1, value)));
    }

    private static RequestPayload outgoing(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("RSF");
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

    private static RsfContext services() {
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(HproseProtocolTest.class.getClassLoader(), new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Collections.emptySet();
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 3000;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        RsfBindInfo<?> info = (RsfBindInfo<?>) Proxy.newProxyInstance(HproseProtocolTest.class.getClassLoader(), new Class<?>[] { RsfBindInfo.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBindID":
                    return "[RSF]Echo-1.0.0";
                case "getBindGroup":
                    return "RSF";
                case "getBindName":
                    return "Echo";
                case "getBindVersion":
                    return "1.0.0";
                case "getBindType":
                    return Echo.class;
                case "getAliasName":
                    return "echo";
                case "getServiceType":
                    return RsfServiceType.Provider;
                case "isShadow":
                    return false;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
        return (RsfContext) Proxy.newProxyInstance(HproseProtocolTest.class.getClassLoader(), new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return HproseProtocolTest.class.getClassLoader();
                case "getSettings":
                    return settings;
                case "getServiceIDs":
                    return Collections.singletonList("[RSF]Echo-1.0.0");
                case "getServiceInfo":
                    return info;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        });
    }

    private static ConnectorManager subscribedManager(RsfContext context, ReceivedListener receiver) {
        ConnectorManager manager = new ConnectorManager(context, new EndpointConnectorFactory());
        manager.init();
        manager.subscribe((channel, id, payload) -> {
            switch (payload.getType()) {
                case REQUEST:
                    receiver.onRequest(channel, id, (RequestPayload) payload);
                    break;
                case RESPONSE:
                    receiver.onResponse(channel, id, (ResponsePayload) payload);
                    break;
                case THROW:
                    receiver.onFailure(channel, id, (ThrowPayload) payload);
                    break;
            }
        });
        return manager;
    }
}

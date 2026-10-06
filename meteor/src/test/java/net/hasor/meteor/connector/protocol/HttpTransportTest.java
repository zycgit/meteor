/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real sockets with connector/protocol modules only: no framework, RPC or filter implementation. */
public class HttpTransportTest {
    private static final ReceivedListener RECEIVER  = new Inbox();
    private final        MetContext       context   = runtimeContext();
    private volatile     boolean          timersStopped;
    private final        List<Endpoint>   endpoints = new ArrayList<>();

    @After
    public void close() {
        for (Endpoint endpoint : this.endpoints) {
            endpoint.manager.close();
        }
    }

    private ConnectorConfig config(String schema, int port) throws Exception {
        return config(schema, port, TextProtocol.class);
    }

    private ConnectorConfig config(String schema, int port, Class<? extends TextProtocol> protocol) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("listenType", "http");
        options.put("protocol", protocol.getSimpleName().toLowerCase(Locale.ROOT));
        return new ConnectorConfig(schema, new InterAddress(schema + "://127.0.0.1:" + port + "/default"), options, Collections.singletonList(new ProtocolConfig(schema, schema, options.getOrDefault("protocol", schema), options)), true);
    }

    private Endpoint endpoint(ConnectorConfig config, ReceivedListener listener) {
        Endpoint endpoint = new Endpoint(config, listener);
        this.endpoints.add(endpoint);
        return endpoint;
    }

    private Future<MetChannel> connect(Endpoint endpoint, InterAddress target) {
        return endpoint.connect(target);
    }

    private int port() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            return server.getLocalPort();
        }
    }

    private Endpoint bind(Endpoint endpoint) throws Exception {
        endpoint.bind(endpoint.config.address()).get(2, TimeUnit.SECONDS);
        return endpoint;
    }

    private static RequestPayload request(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setSerializeType("Java");
        request.setClientTimeout(3000);
        request.addParameter("java.lang.String", value);
        return request;
    }

    private ResponsePayload response(ReceivedRequest exchange, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(exchange.id);
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("Java");
        response.setReturnType("java.lang.String");
        response.setReturnData(value);
        return response;
    }

    @Test
    public void unifiedPayloadSendRejectsLocalFailuresWithoutDisruptingRoundTrip() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        Endpoint client = endpoint(config("http", port()), inbox);
        MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNull(outgoing.getLocal());
        assertEquals(server.connector.getBindAddress(), outgoing.getRemote());
        Payload request = request(1, "request");
        assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
        ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(incoming);
        assertSame(client.connector, outgoing.getConnector());
        assertSame(server.connector, incoming.channel.getConnector());
        assertEquals(outgoing.getLocal().getHostPort(), incoming.channel.getRemote().getHostPort());
        assertEquals(outgoing.getRemote().getHostPort(), incoming.channel.getLocal().getHostPort());
        for (MetChannel channel : Arrays.asList(outgoing, incoming.channel)) {
            assertEquals("http", channel.getLocal().getSchema());
            assertEquals("http", channel.getRemote().getSchema());
        }
        Payload failure = new ThrowPayload(new IOException("local failure"));
        for (MetChannel channel : Arrays.asList(outgoing, incoming.channel)) {
            try {
                channel.sendData(failure).get(2, TimeUnit.SECONDS);
                fail("Failure notifications cannot be sent over the network");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof UnsupportedOperationException);
            }
            assertTrue(channel.isActive());
        }
        try {
            outgoing.sendData(response(incoming, "wrong direction")).get(2, TimeUnit.SECONDS);
            fail("Outgoing HTTP channels cannot send responses");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof UnsupportedOperationException);
        }
        Payload response = response(incoming, "response");
        assertSame(incoming.channel, incoming.channel.sendData(response).get(2, TimeUnit.SECONDS));
        assertEquals("response", inbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertTrue(requests.isEmpty());
        Future<MetChannel> closed = outgoing.close();
        assertSame(outgoing, closed.get(2, TimeUnit.SECONDS));
        assertFalse(outgoing.isActive());
        assertSame(closed, outgoing.close());
        assertSame(closed, outgoing.drainAndClose());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (incoming.channel.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(incoming.channel.isActive());
    }

    @Test
    public void bindsConfiguredEndpointAndReportsOccupiedPortThroughFuture() throws Exception {
        Endpoint endpoint = endpoint(config("http", port()), RECEIVER);
        MetListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
        assertEquals(endpoint.config.address(), first.getBindAddress());
        assertSame(first, endpoint.manager.bind(endpoint.config.name()).get());
        assertEquals(1, endpoint.manager.find(endpoint.config.name()).getListenList().size());
        first.close();
        MetListen rebound = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, rebound);
        assertTrue(rebound.isActive());
        try (ServerSocket occupied = new ServerSocket(0)) {
            Endpoint conflict = endpoint(config("http", occupied.getLocalPort()), RECEIVER);
            try {
                conflict.manager.bind(conflict.config.name()).get(2, TimeUnit.SECONDS);
                fail("Occupied port must fail through the future");
            } catch (ExecutionException expected) {
                assertNotNull(expected.getCause());
            }
        }
    }

    @Test
    public void exposesActualTypedListenerOnlyAfterBind() throws Exception {
        Endpoint connector = endpoint(config("http", port()), RECEIVER);
        assertNull(connector.connector);
        assertNull(connector.manager.find(connector.config.name()));
        connector.bind(connector.config.address()).get();
        MetListen listen = connector.connector.getListenList().get(0);
        assertEquals("http", listen.getType());
        assertTrue(listen.isActive());
        assertEquals(connector.config.address(), listen.getBindAddress());
        assertEquals(listen.getBindAddress(), connector.connector.getBindAddress());
        connector.bind(connector.config.address()).get();
        assertEquals(1, connector.connector.getListenList().size());
        assertSame(listen, connector.connector.getListenList().get(0));
        listen.close();
        assertFalse(listen.isActive());
        assertNull(connector.connector.getBindAddress());
        connector.connector.close();
        assertTrue(connector.connector.getListenList().isEmpty());
    }

    @Test
    public void httpPipelinedRepliesStayOrderedAndKeepAliveAcceptsAnotherRequest() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.payload = request;
                requests.add(exchange);
            }
        }));
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(2000);
            OutputStream output = socket.getOutputStream();
            output.write(http("first"));
            output.write(http("second"));
            output.flush();
            ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS), second = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            Future<MetChannel> secondWrite = second.channel.sendData(response(second, "second"));
            assertFalse(secondWrite.isDone()); // HTTP pipelining holds this reply until the first is written.
            first.channel.sendData(response(first, "first")).get(2, TimeUnit.SECONDS);
            assertSame(second.channel, secondWrite.get(2, TimeUnit.SECONDS));
            assertSame(first.channel, second.channel);
            assertEquals("first", readHttp(socket.getInputStream()));
            assertEquals("second", readHttp(socket.getInputStream()));
            output.write(http("third"));
            output.flush();
            ReceivedRequest third = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(third);
            third.channel.sendData(response(third, "third"));
            assertEquals("third", readHttp(socket.getInputStream()));
        }
    }

    @Test
    public void httpClientQueuesCallsAndReusesTheConnection() throws Exception {
        Set<String> peers = ConcurrentHashMap.newKeySet();
        Inbox received = new Inbox();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                peers.add(exchange.channel.getRemote().getHostPort());
                exchange.channel.sendData(response(exchange, (String) request.getParameterValues().get(0)));
            }
        }));
        // The local endpoint address is already occupied by the server: outgoing use must not bind it.
        Endpoint client = endpoint(config("http", server.connector.getBindAddress().getPort()), received);
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        for (int i = 1; i <= 10; i++) {
            received.expect(i);
            session.sendData(request(i, "http-" + i));
        }
        for (int i = 1; i <= 10; i++) {
            assertEquals("http-" + i, received.expect(i).get(2, TimeUnit.SECONDS).getReturnData());
            assertSame(session, received.sources.get((long) i));
        }
        assertEquals(1, peers.size());
    }

    @Test
    public void rpcCompletionReleasesWrittenHttpExchangeAndAllowsNextCall() throws Exception {
        Inbox received = new Inbox();
        AtomicInteger requests = new AtomicInteger();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                if (requests.incrementAndGet() > 1) {
                    exchange.channel.sendData(response(exchange, "next"));
                }
            }
        }));
        Endpoint client = bind(endpoint(config("http", port()), received));
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        RequestPayload first = request(1, "blocked");
        first.setClientTimeout(1);
        received.expect(1);
        Future<MetChannel> written = session.sendData(first);
        written.get(2, TimeUnit.SECONDS);
        first.complete(new TimeoutException("RPC timed out"));
        assertSame(session, written.get());
        try {
            received.expect(1).get(2, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof TimeoutException);
        }
        received.expect(2);
        session.sendData(request(2, "next"));
        assertEquals("next", received.expect(2).get(2, TimeUnit.SECONDS).getReturnData());
    }

    @Test
    public void cancellingQueuedHttpRequestDoesNotSendItOrBreakTheNextReply() throws Exception {
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        List<String> values = new CopyOnWriteArrayList<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                values.add((String) request.getParameterValues().get(0));
                arrivals.add(exchange);
            }
        }));
        Endpoint client = bind(endpoint(config("http", port()), received));
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        received.expect(1);
        session.sendData(request(1, "first"));
        ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        Future<MetChannel> cancelled = session.sendData(request(2, "cancelled"));
        assertTrue(cancelled.cancel());
        assertTrue(cancelled.isCancelled());
        received.expect(3);
        session.sendData(request(3, "third"));
        first.channel.sendData(response(first, "first"));
        ReceivedRequest third = arrivals.poll(2, TimeUnit.SECONDS);
        assertNotNull(third);
        third.channel.sendData(response(third, "third"));
        assertEquals("first", received.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertEquals("third", received.expect(3).get(2, TimeUnit.SECONDS).getReturnData());
        assertEquals(Arrays.asList("first", "third"), values);
    }

    @Test
    public void cancellingSendFutureRemovesQueuedRequestAndCloseFailsOtherPendingSends() throws Exception {
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        }));
        Endpoint client = bind(endpoint(config("http", port()), received));
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        Future<MetChannel> firstWrite = session.sendData(request(1, "blocked"));
        ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        assertSame(session, firstWrite.get(2, TimeUnit.SECONDS));
        BasicFuture<MetChannel> notified = new BasicFuture<>();
        firstWrite.onCompleted(done -> notified.completed(done.getResult()));
        assertSame(session, notified.get(2, TimeUnit.SECONDS));
        assertFalse(received.expect(1).isDone());
        Future<MetChannel> cancelled = session.sendData(request(2, "cancelled"));
        AtomicInteger notifications = new AtomicInteger();
        cancelled.onCancel(done -> notifications.incrementAndGet());
        assertTrue(cancelled.cancel());
        assertEquals(1, notifications.get());
        Future<MetChannel> pending = session.sendData(request(3, "closed before send"));
        session.close();
        try {
            pending.get(2, TimeUnit.SECONDS);
            fail("Queued send must fail on close");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
        try {
            session.sendData(request(4, "already closed")).get(2, TimeUnit.SECONDS);
            fail("Closed session must return a failed send future");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertTrue(arrivals.isEmpty());
        assertSame(session, firstWrite.get());
    }

    @Test
    public void queuedSendTimeoutAndConnectionFailureCompleteSendFuture() throws Exception {
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        }));
        Endpoint client = bind(endpoint(config("http", port()), received));
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        session.sendData(request(1, "blocked")).get(2, TimeUnit.SECONDS);
        assertNotNull(arrivals.poll(2, TimeUnit.SECONDS));
        RequestPayload queued = request(2, "expire before send");
        queued.setClientTimeout(50);
        try {
            Future<MetChannel> queuedWrite = session.sendData(queued);
            queued.complete(new TimeoutException("RPC timed out"));
            queuedWrite.get(2, TimeUnit.SECONDS);
            fail("Queued send must expire");
        } catch (ExecutionException expected) {
            assertTrue(String.valueOf(expected.getCause()), expected.getCause() instanceof TimeoutException);
        }
        server.connector.close();
        MetChannel missing = connect(client, new InterAddress("http", "127.0.0.1", port(), "default")).get();
        try {
            missing.sendData(request(3, "unreachable")).get(2, TimeUnit.SECONDS);
            fail("Failed connection must fail the send future");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
    }

    @Test
    public void incompatibleProtocolFactoryIsRejectedBeforeListening() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "http");
        options.put("protocol", "wirea");
        ConnectorConfig config = new ConnectorConfig("http", new InterAddress("http://127.0.0.1:" + port() + "/default"), options, Collections.singletonList(new ProtocolConfig("http", "http", options.getOrDefault("protocol", "http"), options)), true);
        try (ConnectorManager manager = subscribedManager(runtimeContext(config), RECEIVER)) {
            manager.bind("http").get();
            fail();
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("requires transport tcp"));
        }
    }

    @Test
    public void rpcCompletionReleasesOrderedSlotAndRejectsLateReply() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port(), ExpiringProtocol.class), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.payload = request;
                requests.add(exchange);
            }
        }));
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(http("expire"));
            socket.getOutputStream().write(http("next"));
            socket.getOutputStream().flush();
            ReceivedRequest expired = requests.poll(2, TimeUnit.SECONDS), next = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(expired);
            assertNotNull(next);
            next.channel.sendData(response(next, "next"));
            expired.payload.complete(new TimeoutException("RPC timed out"));
            assertTrue(readHttp(socket.getInputStream(), 500).contains("timed out"));
            assertEquals("next", readHttp(socket.getInputStream()));
            assertTrue(expired.channel.isActive());
            BasicFuture<Throwable> rejected = new BasicFuture<>();
            expired.channel.sendData(response(expired, "late")).onCompleted(done -> rejected.failed(new AssertionError("Late response written"))).onFailed(done -> rejected.completed(done.getCause()));
            assertTrue(rejected.get(2, TimeUnit.SECONDS) instanceof IllegalStateException);
        }
    }

    private byte[] http(String body) {
        return ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII);
    }

    private String readHttp(InputStream input) throws IOException {
        return readHttp(input, 200);
    }

    private String readHttp(InputStream input, int status) throws IOException {
        assertTrue(line(input).startsWith("HTTP/1.1 " + status));
        int length = -1;
        String line;
        while (!(line = line(input)).isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (length < 0) {
            throw new IOException("Missing content length");
        }
        byte[] body = new byte[length];
        new DataInputStream(input).readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    private String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != '\n') {
            if (b == -1) {
                throw new EOFException();
            }
            if (b != '\r') {
                bytes.write(b);
            }
        }
        return bytes.toString("US-ASCII");
    }

    @Test
    public void requestExchangeDoesNotScheduleConnectorTimers() throws Exception {
        Inbox received = new Inbox();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                channel.sendData(response(new ReceivedRequest(channel, id), "reply"));
            }
        }));
        Endpoint client = bind(endpoint(config("http", port()), received));
        MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        this.timersStopped = true;
        for (long id = 1; id <= 2; id++) {
            RequestPayload request = request(id, "no request timer");
            assertSame(session, session.sendData(request).get(2, TimeUnit.SECONDS));
            assertEquals("reply", received.expect(id).get(2, TimeUnit.SECONDS).getReturnData());
            request.completion().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void assembledReceiverHandlesEveryListenerAndOutgoingConnection() throws Exception {
        Inbox incoming = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.channel.sendData(response(exchange, (String) request.getParameterValues().get(0)));
            }
        };
        Endpoint server = endpoint(config("http", port()), incoming);
        MetListen firstListen = server.bind(server.config.address()).get(2, TimeUnit.SECONDS);
        MetListen secondListen = server.bind(config("http", port()).address()).get(2, TimeUnit.SECONDS);
        Inbox received = new Inbox();
        Endpoint client = endpoint(config("http", port()), received);
        MetChannel first = connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS));
        first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
        assertEquals("first", received.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        MetChannel second = connect(client, secondListen.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, second);
        second.sendData(request(2, "second")).get(2, TimeUnit.SECONDS);
        assertEquals("second", received.expect(2).get(2, TimeUnit.SECONDS).getReturnData());
        assertTrue(incoming.results.isEmpty());
    }

    @Test
    public void acceptedHttpChannelsSeparateIdenticalRequestIdsAndRejectDuplicateReplies() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port(), SameIdProtocol.class), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                assertEquals(request.getRequestID(), id);
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        try (Socket a = new Socket("127.0.0.1", server.connector.getBindAddress().getPort()); Socket b = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            a.setSoTimeout(2000);
            b.setSoTimeout(2000);
            a.getOutputStream().write(http("a"));
            ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
            b.getOutputStream().write(http("b"));
            ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(first.channel, second.channel);
            assertEquals(first.id, second.id);
            ResponsePayload unknown = response(first, "wrong");
            unknown.setRequestID(2);
            assertReplyRejected(first.channel, unknown);
            assertSame(second.channel, second.channel.sendData(response(second, "b")).get(2, TimeUnit.SECONDS));
            assertSame(first.channel, first.channel.sendData(response(first, "a")).get(2, TimeUnit.SECONDS));
            assertEquals("a", readHttp(a.getInputStream()));
            assertEquals("b", readHttp(b.getInputStream()));
            assertReplyRejected(first.channel, response(first, "duplicate"));
            try {
                first.channel.sendData(request(9, "reverse request")).get(2, TimeUnit.SECONDS);
                fail("HTTP accepted channel cannot initiate a request");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof UnsupportedOperationException);
            }
        }
    }

    @Test
    public void httpAcknowledgementsAndEncodingFailuresKeepOrderedResponses() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port(), AcknowledgementProtocol.class), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(2000);
            for (String value : Arrays.asList("normal", "message", "broken")) {
                socket.getOutputStream().write(http(value));
            }
            ReceivedRequest normal = requests.poll(2, TimeUnit.SECONDS);
            ReceivedRequest message = requests.poll(2, TimeUnit.SECONDS);
            ReceivedRequest broken = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(normal);
            assertNotNull(message);
            assertNotNull(broken);
            ResponsePayload ack = response(normal, "accept");
            ack.setStatus(ProtocolStatus.Accept);
            assertSame(normal.channel, normal.channel.sendData(ack).get(2, TimeUnit.SECONDS));
            Future<MetChannel> failedEncoding = broken.channel.sendData(response(broken, "broken"));
            ResponsePayload messageAck = response(message, "message-ack");
            messageAck.setStatus(ProtocolStatus.Accept);
            Future<MetChannel> acknowledged = message.channel.sendData(messageAck);
            assertReplyRejected(message.channel, response(message, "late"));
            assertFalse(acknowledged.isDone());
            assertFalse(failedEncoding.isDone());
            normal.channel.sendData(response(normal, "normal")).get(2, TimeUnit.SECONDS);
            assertEquals("normal", readHttp(socket.getInputStream()));
            assertEquals("message-ack", readHttp(socket.getInputStream()));
            assertTrue(readHttp(socket.getInputStream(), 500).contains("encode failed"));
            assertSame(message.channel, acknowledged.get(2, TimeUnit.SECONDS));
            assertSame(broken.channel, failedEncoding.get(2, TimeUnit.SECONDS));
            assertReplyRejected(broken.channel, response(broken, "late"));
        }
    }

    @Test
    public void channelClosureFailsQueuedHttpResponse() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(http("blocked"));
            socket.getOutputStream().write(http("queued"));
            ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
            ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            Future<MetChannel> writing = second.channel.sendData(response(second, "queued"));
            first.channel.close();
            try {
                writing.get(2, TimeUnit.SECONDS);
                fail("Closing the source channel must fail queued replies");
            } catch (ExecutionException expected) {
                assertNotNull(expected.getCause());
            }
            assertReplyRejected(first.channel, response(first, "late"));
        }
    }

    @Test(timeout = 10000)
    public void managerCloseReleasesUnansweredSlotsAndDrainsSubmittedHttpResponse() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = endpoint(config("http", port(), DrainProtocol.class), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        });
        ConnectorManager manager = server.manager;
        server.bind(server.config.address()).get(2, TimeUnit.SECONDS);
        ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(http("unsubmitted"));
            socket.getOutputStream().write(http("submitted"));
            ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
            ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            Future<MetChannel> writing = second.channel.sendData(response(second, "drained"));
            FutureTask<Void> closing = new FutureTask<>(manager::close, null);
            lifecycle.execute(closing);
            assertTrue(readHttp(socket.getInputStream(), 500).contains("closing"));
            assertEquals("drained", readHttp(socket.getInputStream()));
            assertSame(second.channel, writing.get(2, TimeUnit.SECONDS));
            closing.get(3, TimeUnit.SECONDS);
            assertTrue(first.channel.sendData(response(first, "late")).getCause() instanceof IllegalStateException);
            assertEquals(-1, socket.getInputStream().read());
        } finally {
            lifecycle.shutdown();
            assertTrue(lifecycle.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private void assertReplyRejected(MetChannel channel, ResponsePayload response) throws Exception {
        try {
            channel.sendData(response).get(2, TimeUnit.SECONDS);
            fail("Unknown or completed request must reject a reply");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    @Test(timeout = 10000)
    public void netaDecodesFragmentedChunkedRequestsAndReusesConnection() throws Exception {
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                String value = String.valueOf(request.getParameterValues().get(0));
                channel.sendData(response(new ReceivedRequest(channel, id), value));
            }
        }));
        try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
            socket.setSoTimeout(3000);
            byte[] request = ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + "3\r\nhel\r\n2\r\nlo\r\n0\r\nX-Trailer: yes\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            for (int offset = 0; offset < request.length; offset += 3) {
                socket.getOutputStream().write(request, offset, Math.min(3, request.length - offset));
            }
            assertEquals("hello", this.readHttp(socket.getInputStream()));
            socket.getOutputStream().write(this.http("next"));
            assertEquals("next", this.readHttp(socket.getInputStream()));
        }
    }

    @Test(timeout = 10000)
    public void netaDecodesChunkedResponseFromExternalHttpServer() throws Exception {
        Inbox inbox = new Inbox();
        Endpoint client = endpoint(config("http", port()), inbox);
        try (ServerSocket listener = new ServerSocket(0)) {
            InterAddress target = new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default");
            MetChannel channel = connect(client, target).get(2, TimeUnit.SECONDS);
            Future<MetChannel> sent = channel.sendData(request(1, "request"));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                assertSame(channel, sent.get(2, TimeUnit.SECONDS));
                InputStream input = peer.getInputStream();
                assertTrue(this.line(input).startsWith("POST "));
                int length = 0;
                String header;
                while (!(header = this.line(input)).isEmpty()) {
                    if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(header.substring(15).trim());
                    }
                }
                new DataInputStream(input).readFully(new byte[length]);
                byte[] response = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                for (byte value : response) {
                    peer.getOutputStream().write(value);
                }
                peer.getOutputStream().flush();
                assertEquals("hello world", inbox.expect(1).get(3, TimeUnit.SECONDS).getReturnData());
            }
        }
    }

    @Test(timeout = 10000)
    public void drainDoesNotWaitForResponseAfterRequestWasWritten() throws Exception {
        Inbox inbox = new Inbox();
        Endpoint client = endpoint(config("http", port()), inbox);
        try (ServerSocket listener = new ServerSocket(0)) {
            MetChannel channel = connect(client, new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default")).get(2, TimeUnit.SECONDS);
            RequestPayload request = request(1, "no response");
            request.setClientTimeout(30000);
            Future<MetChannel> sent = channel.sendData(request);
            try (Socket peer = listener.accept()) {
                assertSame(channel, sent.get(2, TimeUnit.SECONDS));
                Future<MetChannel> closed = channel.drainAndClose();
                assertSame(channel, closed.get(2, TimeUnit.SECONDS));
                assertSame(closed, channel.close());
                assertFalse(channel.isActive());
                assertNotNull(inbox.expect(1).getCause());
                try {
                    channel.sendData(request(2, "rejected")).get(2, TimeUnit.SECONDS);
                    fail("Closed channel must reject new writes");
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void drainWritesQueuedRequestWithoutWaitingForPreviousResponse() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("http", port()), new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        Endpoint client = endpoint(config("http", port()), new Inbox());
        MetChannel channel = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        channel.sendData(request(1, "unanswered")).get(2, TimeUnit.SECONDS);
        assertNotNull(requests.poll(2, TimeUnit.SECONDS));
        Future<MetChannel> queued = channel.sendData(request(2, "submitted"));
        Future<MetChannel> closed = channel.drainAndClose();
        assertSame(channel, queued.get(2, TimeUnit.SECONDS));
        assertSame(channel, closed.get(2, TimeUnit.SECONDS));
        assertFalse(channel.isActive());
    }

    @Test(timeout = 10000)
    public void unusedHttpChannelClosesWithoutOpeningASocket() throws Exception {
        Endpoint client = endpoint(config("http", port()), new Inbox());
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(200);
            MetChannel channel = connect(client, new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default")).get(2, TimeUnit.SECONDS);
            assertSame(channel, channel.drainAndClose().get(2, TimeUnit.SECONDS));
            try (Socket unexpected = listener.accept()) {
                fail("An unused HTTP channel must not open a physical connection");
            } catch (SocketTimeoutException expected) {
                assertFalse(channel.isActive());
            }
        }
    }

    private static class Inbox implements ReceivedListener {
        final Map<Long, MetChannel>                   sources = new ConcurrentHashMap<>();
        final Map<Long, BasicFuture<ResponsePayload>> results = new ConcurrentHashMap<>();

        BasicFuture<ResponsePayload> expect(long id) {
            return this.results.computeIfAbsent(id, key -> new BasicFuture<>());
        }

        public void onRequest(MetChannel channel, long id, RequestPayload request) {
            ReceivedRequest exchange = new ReceivedRequest(channel, id);
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(MetChannel channel, long id, ResponsePayload response) {
            assertEquals(response.getRequestID(), id);
            this.sources.put(id, channel);
            expect(id).completed(response);
        }

        public void onFailure(MetChannel channel, long id, ThrowPayload failure) {
            this.sources.put(id, channel);
            expect(id).failed(failure.getThrowable());
        }
    }

    public static class TextProtocol implements TestHttpCodec, ProtocolFactory<HttpExchange> {
        @Override
        public String scheme() {
            return this.name();
        }

        @Override
        public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
            return RouteMatch.REJECT;
        }

        public String transport() {
            return "http";
        }

        public Class<HttpExchange> messageType() {
            return HttpExchange.class;
        }

        public String name() {
            return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        private final AtomicLong requestIds = new AtomicLong();

        public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
            return new TestHttpSession(this, context, connection, messages);
        }

        public TestHttpInvocation receive(HttpRequest request) {
            RequestPayload decoded = request(this.requestIds.incrementAndGet(), new String(request.body(), StandardCharsets.UTF_8));
            return TestHttpInvocation.dispatch(decoded, response -> new HttpResponse(200, Collections.emptyMap(), String.valueOf(response.getReturnData()).getBytes(StandardCharsets.UTF_8)));
        }

        public HttpRequest encode(InterAddress target, RequestPayload request) {
            return new HttpRequest("POST", "/", Collections.emptyMap(), String.valueOf(request.getParameterValues().get(0)).getBytes(StandardCharsets.UTF_8));
        }

        public ResponsePayload decode(long id, HttpResponse response) {
            ResponsePayload decoded = new ResponsePayload();
            decoded.setRequestID(id);
            decoded.setStatus((short) response.status());
            decoded.setReturnData(new String(response.body(), StandardCharsets.UTF_8));
            return decoded;
        }

        public HttpResponse error(Throwable failure) {
            return new HttpResponse(500, Collections.emptyMap(), failure.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static MetContext runtimeContext(ConnectorConfig... configs) {
        ClassLoader loader = HttpTransportTest.class.getClassLoader();
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, new Class<?>[] { MetSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Arrays.asList(configs);
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 3000;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getSerializeCoder":
                    return "Java".equals(args[0]) ? new JavaSerializeCoder() : null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private static ConnectorManager subscribedManager(MetContext context, ReceivedListener receiver) {
        ConnectorManager manager = new ConnectorManager(context, new EndpointConnectorFactory());
        subscribe(manager, receiver);
        return manager;
    }

    private static void subscribe(ConnectorManager manager, ReceivedListener receiver) {
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
    }

    /** Local test setup using the production SPI factory and connector implementation. */
    private final class Endpoint {
        private final ConnectorConfig  config;
        private final ConnectorManager manager;
        private       MetConnector     connector;

        private Endpoint(ConnectorConfig config, ReceivedListener listener) {
            this.config = config;
            this.manager = new ConnectorManager(runtimeContext(config), new EndpointConnectorFactory()) {
                @Override
                public Cancellable schedule(Runnable task, long delayMillis) {
                    if (HttpTransportTest.this.timersStopped) {
                        throw new RejectedExecutionException("Test timer stopped");
                    }
                    return super.schedule(task, delayMillis);
                }
            };
            subscribe(this.manager, listener);
        }

        private Future<MetListen> bind(InterAddress address) {
            Future<MetListen> result = this.manager.bind(this.config.name());
            this.connector = this.manager.find(this.config.name());
            return result;
        }

        private Future<MetChannel> connect(InterAddress address) {
            Future<MetChannel> result = this.manager.connect(address);
            this.connector = this.manager.find(this.config.name());
            return result;
        }
    }

    public static class ExpiringProtocol extends TextProtocol {
        public TestHttpInvocation receive(HttpRequest request) {
            TestHttpInvocation invocation = super.receive(request);
            if ("expire".equals(invocation.request().getParameterValues().get(0))) {
                invocation.request().setClientTimeout(100);
            }
            return invocation;
        }
    }

    public static class SameIdProtocol extends TextProtocol {
        public TestHttpInvocation receive(HttpRequest request) {
            TestHttpInvocation invocation = super.receive(request);
            invocation.request().setRequestID(1);
            return invocation;
        }
    }

    public static class AcknowledgementProtocol extends TextProtocol {
        public TestHttpInvocation receive(HttpRequest request) {
            TestHttpInvocation invocation = super.receive(request);
            String value = (String) invocation.request().getParameterValues().get(0);
            invocation.request().setMessage("message".equals(value));
            if ("broken".equals(value)) {
                return TestHttpInvocation.dispatch(invocation.request(), response -> {
                    throw new IOException("encode failed");
                });
            }
            return invocation;
        }
    }

    public static class DrainProtocol extends TextProtocol {
        public TestHttpInvocation receive(HttpRequest request) {
            TestHttpInvocation invocation = super.receive(request);
            invocation.request().setClientTimeout(500);
            return invocation;
        }
    }

    private static final class ReceivedRequest {
        private final MetChannel     channel;
        private final long           id;
        private       RequestPayload payload;

        private ReceivedRequest(MetChannel channel, long id) {
            this.channel = channel;
            this.id = id;
        }
    }
}

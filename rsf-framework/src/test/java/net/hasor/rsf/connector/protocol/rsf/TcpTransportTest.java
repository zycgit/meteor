/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.protocol.EndpointConnectorFactory;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real sockets with connector/protocol modules only: no framework, RPC or filter implementation. */
public class TcpTransportTest {
    private static final ReceivedListener RECEIVER  = new Inbox();
    private final        RsfContext       context   = runtimeContext();
    private volatile     boolean          timersStopped;
    private final        List<Endpoint>   endpoints = new ArrayList<>();

    @After
    public void close() {
        for (Endpoint endpoint : this.endpoints) {
            endpoint.manager.close();
        }
    }

    private ConnectorConfig config(String schema, int port) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        return new ConnectorConfig(schema, new InterAddress(schema + "://127.0.0.1:" + port + "/default"), options);
    }

    private Endpoint endpoint(ConnectorConfig config, ReceivedListener listener) {
        Endpoint endpoint = new Endpoint(config, listener);
        this.endpoints.add(endpoint);
        return endpoint;
    }

    private Future<RsfChannel> connect(Endpoint endpoint, InterAddress target) {
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

    private RequestPayload request(long id, String value) {
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

    @Test(timeout = 10000)
    public void shutdownGatesListenersThenWritesAndDoesNotWaitForRpcReply() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        });
        ConnectorManager manager = server.manager;
        server.bind(server.config.address()).get(2, TimeUnit.SECONDS);
        Inbox inbox = new Inbox();
        Endpoint client = endpoint(config("rsf", port()), inbox);
        RsfChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        outgoing.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
        ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        InterAddress oldAddress = server.connector.getBindAddress();
        server.connector.closeBind();
        server.connector.closeBind();
        assertTrue(server.connector.getListenList().isEmpty());
        assertNull(server.connector.getBindAddress());
        try (Socket rejected = new Socket()) {
            try {
                rejected.connect(oldAddress.toSocketAddress(), 1000);
                fail("Closed listener must refuse new connections");
            } catch (ConnectException expected) {
                // Existing channels below must remain usable.
            }
        }
        Endpoint peer = bind(endpoint(config("rsf", port()), RECEIVER));
        RsfChannel newOutgoing = server.connector.connect(peer.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertTrue(newOutgoing.isActive());
        newOutgoing.close().get(2, TimeUnit.SECONDS);
        first.channel.sendData(response(first, "still-writable")).get(2, TimeUnit.SECONDS);
        assertEquals("still-writable", inbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        outgoing.sendData(request(2, "unsubmitted-reply")).get(2, TimeUnit.SECONDS);
        ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);
        ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        try {
            lifecycle.submit(manager::close).get(2, TimeUnit.SECONDS);
            assertFalse(second.channel.isActive());
            assertNotNull(server.connector.connect(server.config.address()).getCause());
            assertTrue(second.channel.sendData(response(second, "late")).getCause() instanceof IllegalStateException);
        } finally {
            lifecycle.shutdownNow();
        }
    }

    @Test
    public void unifiedPayloadSendRejectsLocalFailuresWithoutDisruptingRoundTrip() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        Endpoint server = bind(endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        Endpoint client = endpoint(config("rsf", port()), inbox);
        RsfChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotNull(outgoing.getLocal());
        assertEquals(server.connector.getBindAddress(), outgoing.getRemote());
        Payload request = request(1, "request");
        assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
        ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(incoming);
        assertSame(client.connector, outgoing.getConnector());
        assertSame(server.connector, incoming.channel.getConnector());
        assertEquals(outgoing.getLocal().getHostPort(), incoming.channel.getRemote().getHostPort());
        assertEquals(outgoing.getRemote().getHostPort(), incoming.channel.getLocal().getHostPort());
        for (RsfChannel channel : Arrays.asList(outgoing, incoming.channel)) {
            assertEquals("rsf", channel.getLocal().getSchema());
            assertEquals("rsf", channel.getRemote().getSchema());
        }
        Payload failure = new ThrowPayload(new IOException("local failure"));
        for (RsfChannel channel : Arrays.asList(outgoing, incoming.channel)) {
            try {
                channel.sendData(failure).get(2, TimeUnit.SECONDS);
                fail("Failure notifications cannot be sent over the network");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof UnsupportedOperationException);
            }
            assertTrue(channel.isActive());
        }
        Payload response = response(incoming, "response");
        assertSame(incoming.channel, incoming.channel.sendData(response).get(2, TimeUnit.SECONDS));
        assertEquals("response", inbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertTrue(requests.isEmpty());
        Future<RsfChannel> closed = outgoing.close();
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
        Endpoint endpoint = endpoint(config("rsf", port()), RECEIVER);
        RsfListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
        assertEquals(endpoint.config.address(), first.getBindAddress());
        assertSame(first, endpoint.manager.bind(endpoint.config.name()).get());
        assertEquals(1, endpoint.manager.find(endpoint.config.name()).getListenList().size());
        first.close();
        RsfListen rebound = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, rebound);
        assertTrue(rebound.isActive());
        try (ServerSocket occupied = new ServerSocket(0)) {
            Endpoint conflict = endpoint(config("rsf", occupied.getLocalPort()), RECEIVER);
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
        Endpoint connector = endpoint(config("rsf", port()), RECEIVER);
        assertNull(connector.connector);
        assertNull(connector.manager.find(connector.config.name()));
        connector.bind(connector.config.address()).get();
        RsfListen listen = connector.connector.getListenList().get(0);
        assertEquals("tcp", listen.getType());
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
    public void tcpConcurrentRepliesKeepRequestIdentity() throws Exception {
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                requests.add(exchange);
            }
        }));
        // The local endpoint address is already occupied by the server: outgoing use must not bind it.
        Endpoint client = endpoint(config("rsf", server.connector.getBindAddress().getPort()), received);
        RsfChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        for (int i = 1; i <= 20; i++) {
            received.expect(i);
            session.sendData(request(i, "value-" + i));
        }
        List<ReceivedRequest> exchanges = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ReceivedRequest exchange = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(exchange);
            exchanges.add(exchange);
        }
        Collections.reverse(exchanges);
        for (ReceivedRequest exchange : exchanges) {
            exchange.channel.sendData(response(exchange, "value-" + exchange.id)).get(2, TimeUnit.SECONDS);
        }
        for (int i = 1; i <= 20; i++) {
            assertEquals("value-" + i, received.expect(i).get(2, TimeUnit.SECONDS).getReturnData());
        }
    }

    @Test
    public void peerClosureFailsPendingRequestAndNewConnectionWorks() throws Exception {
        Inbox received = new Inbox();
        CountDownLatch arrived = new CountDownLatch(1);
        ConnectorConfig endpoint = config("rsf", port());
        Endpoint server = bind(endpoint(endpoint, new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrived.countDown();
            }
        }));
        Endpoint client = bind(endpoint(config("rsf", port()), received));
        RsfChannel first = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        received.expect(1);
        Future<RsfChannel> written = first.sendData(request(1, "pending"));
        assertSame(first, written.get(2, TimeUnit.SECONDS));
        BasicFuture<RsfChannel> notified = new BasicFuture<>();
        written.onCompleted(done -> notified.completed(done.getResult()));
        assertSame(first, notified.get(2, TimeUnit.SECONDS));
        assertTrue(arrived.await(2, TimeUnit.SECONDS));
        assertFalse(received.expect(1).isDone());
        server.connector.close();
        try {
            received.expect(1).get(2, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException expected) {
        }
        assertSame(first, written.get()); // A later response failure does not change a successful send.
        try {
            first.sendData(request(3, "closed")).get(2, TimeUnit.SECONDS);
            fail("Closed session send must fail through its future");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException || expected.getCause() instanceof IllegalStateException);
        }
        Endpoint replacement = bind(endpoint(endpoint, new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.channel.sendData(response(exchange, "reconnected"));
            }
        }));
        RsfChannel second = connect(client, replacement.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, second);
        received.expect(2);
        second.sendData(request(2, "next"));
        assertEquals("reconnected", received.expect(2).get(2, TimeUnit.SECONDS).getReturnData());
        client.connector.close();
        try {
            second.sendData(request(4, "event loop closed")).get(2, TimeUnit.SECONDS);
            fail("Closed connector must reject sends before event-loop submission");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void tcpConnectDoesNotCompleteUntilProtocolHandshake() throws Exception {
        try (ServerSocket silentPeer = new ServerSocket(0)) {
            Map<String, String> options = new HashMap<>();
            options.put("handshakeTimeout", "200");
            options.put("listenType", "tcp");
            options.put("protocol", "rsf");
            ConnectorConfig shortHandshake = new ConnectorConfig("rsf", config("rsf", port()).address(), options);
            Endpoint client = bind(endpoint(shortHandshake, new Inbox()));
            Future<RsfChannel> connection = connect(client, new InterAddress("rsf://127.0.0.1:" + silentPeer.getLocalPort() + "/default"));
            try (Socket accepted = silentPeer.accept()) {
                assertFalse(connection.isDone());
                try {
                    connection.get(2, TimeUnit.SECONDS);
                    fail();
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof TimeoutException);
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void cancellingHandshakeClosesOutgoingSocket() throws Exception {
        try (ServerSocket peer = new ServerSocket(0)) {
            peer.setSoTimeout(2000);
            Endpoint client = endpoint(config("rsf", 1), new Inbox());
            Future<RsfChannel> connecting = connect(client, new InterAddress("rsf", "127.0.0.1", peer.getLocalPort(), "default"));
            try (Socket accepted = peer.accept()) {
                accepted.setSoTimeout(2000);
                assertTrue(accepted.getInputStream().read() >= 0);
                assertFalse(connecting.isDone());
                assertTrue(connecting.cancel());
                byte[] bytes = new byte[128];
                while (accepted.getInputStream().read(bytes) != -1) {
                    // Discard the handshake already written before cancellation.
                }
                assertTrue(connecting.isCancelled());
            }
        }
    }

    @Test
    public void requestExchangeDoesNotScheduleConnectorTimers() throws Exception {
        Inbox received = new Inbox();
        Endpoint server = bind(endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                channel.sendData(response(new ReceivedRequest(channel, id), "reply"));
            }
        }));
        Endpoint client = bind(endpoint(config("rsf", port()), received));
        RsfChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
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
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.channel.sendData(response(exchange, (String) request.getParameterValues().get(0)));
            }
        };
        Endpoint server = endpoint(config("rsf", port()), incoming);
        RsfListen firstListen = server.bind(server.config.address()).get(2, TimeUnit.SECONDS);
        RsfListen secondListen = server.bind(config("rsf", port()).address()).get(2, TimeUnit.SECONDS);
        Inbox received = new Inbox();
        Endpoint client = endpoint(config("rsf", port()), received);
        RsfChannel first = connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS));
        first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
        assertEquals("first", received.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        RsfChannel second = connect(client, secondListen.getBindAddress()).get(2, TimeUnit.SECONDS);
        assertNotSame(first, second);
        second.sendData(request(2, "second")).get(2, TimeUnit.SECONDS);
        assertEquals("second", received.expect(2).get(2, TimeUnit.SECONDS).getReturnData());
        assertTrue(incoming.results.isEmpty());
    }

    @Test
    public void sourceChannelsSeparateIdenticalIdsFromDifferentClients() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Endpoint server = bind(endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                assertEquals(request.getRequestID(), id);
                requests.add(new ReceivedRequest(channel, id));
            }
        }));
        Inbox firstInbox = new Inbox(), secondInbox = new Inbox();
        Endpoint firstClient = endpoint(config("rsf", port()), firstInbox);
        Endpoint secondClient = endpoint(config("rsf", port()), secondInbox);
        RsfChannel firstClientChannel = connect(firstClient, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        RsfChannel secondClientChannel = connect(secondClient, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        firstClientChannel.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
        ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
        secondClientChannel.sendData(request(1, "second")).get(2, TimeUnit.SECONDS);
        ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first.channel, second.channel);
        assertEquals(first.id, second.id);
        assertSame(second.channel, second.channel.sendData(response(second, "second")).get(2, TimeUnit.SECONDS));
        assertSame(first.channel, first.channel.sendData(response(first, "first")).get(2, TimeUnit.SECONDS));
        assertEquals("first", firstInbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertEquals("second", secondInbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertSame(firstClientChannel, firstInbox.sources.get(1L));
        assertSame(secondClientChannel, secondInbox.sources.get(1L));
        firstClientChannel.sendData(request(2, "pending")).get(2, TimeUnit.SECONDS);
        assertNotNull(requests.poll(2, TimeUnit.SECONDS));
        server.connector.close();
        try {
            firstInbox.expect(2).get(2, TimeUnit.SECONDS);
            fail("Peer closure must fail the pending call");
        } catch (ExecutionException expected) {
            assertSame(firstClientChannel, firstInbox.sources.get(2L));
        }
    }

    @Test
    public void acceptedTcpChannelCanSendRequestsWhileBothDirectionsUseSameId() throws Exception {
        BlockingQueue<ReceivedRequest> serverRequests = new LinkedBlockingQueue<>();
        BlockingQueue<ReceivedRequest> clientRequests = new LinkedBlockingQueue<>();
        Inbox serverInbox = new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                serverRequests.add(new ReceivedRequest(channel, id));
            }
        };
        Inbox clientInbox = new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                clientRequests.add(new ReceivedRequest(channel, id));
            }
        };
        Endpoint server = bind(endpoint(config("rsf", port()), serverInbox));
        Endpoint client = endpoint(config("rsf", port()), clientInbox);
        RsfChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        outgoing.sendData(request(1, "client request")).get(2, TimeUnit.SECONDS);
        ReceivedRequest atServer = serverRequests.poll(2, TimeUnit.SECONDS);
        assertNotNull(atServer);
        assertSame(atServer.channel, atServer.channel.sendData(request(1, "server request")).get(2, TimeUnit.SECONDS));
        ReceivedRequest atClient = clientRequests.poll(2, TimeUnit.SECONDS);
        assertNotNull(atClient);
        assertSame(outgoing, atClient.channel);
        atClient.channel.sendData(response(atClient, "client response")).get(2, TimeUnit.SECONDS);
        atServer.channel.sendData(response(atServer, "server response")).get(2, TimeUnit.SECONDS);
        assertEquals("client response", serverInbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertEquals("server response", clientInbox.expect(1).get(2, TimeUnit.SECONDS).getReturnData());
        assertSame(atServer.channel, serverInbox.sources.get(1L));
        assertSame(outgoing, clientInbox.sources.get(1L));
    }

    @Test
    public void intermediateReplyAndConcurrentFinalRepliesKeepOnlyOneFinalResponse() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Endpoint server = bind(endpoint(config("rsf", port()), new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        }));
        Endpoint client = endpoint(config("rsf", port()), received);
        RsfChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        session.sendData(request(7, "call")).get(2, TimeUnit.SECONDS);
        ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(incoming);
        ResponsePayload wrong = response(incoming, "wrong");
        wrong.setRequestID(99);
        assertReplyRejected(incoming.channel, wrong);
        ResponsePayload accepted = response(incoming, "accepted");
        accepted.setStatus(ProtocolStatus.Accept);
        assertSame(incoming.channel, incoming.channel.sendData(accepted).get(2, TimeUnit.SECONDS));
        assertEquals(ProtocolStatus.Accept, received.expect(7).get(2, TimeUnit.SECONDS).getStatus());
        received.results.remove(7L);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        BlockingQueue<Future<RsfChannel>> writes = new LinkedBlockingQueue<>();
        try {
            for (int i = 0; i < 20; i++) {
                workers.execute(() -> writes.add(incoming.channel.sendData(response(incoming, "final"))));
            }
            int successful = 0;
            for (int i = 0; i < 20; i++) {
                Future<RsfChannel> writing = writes.poll(2, TimeUnit.SECONDS);
                assertNotNull(writing);
                try {
                    assertSame(incoming.channel, writing.get(2, TimeUnit.SECONDS));
                    successful++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof IllegalStateException);
                }
            }
            assertEquals(1, successful);
            assertEquals("final", received.expect(7).get(2, TimeUnit.SECONDS).getReturnData());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void messageAcknowledgementIsTerminalAndWriteFailureDoesNotKeepReplyPending() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxFrameSize", "1024");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        ConnectorConfig serverConfig = new ConnectorConfig("server", config("rsf", port()).address(), options);
        Endpoint server = bind(endpoint(serverConfig, new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        }));
        Endpoint client = endpoint(config("rsf", port()), received);
        RsfChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        RequestPayload message = request(1, "message");
        message.setMessage(true);
        session.sendData(message).get(2, TimeUnit.SECONDS);
        ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        ResponsePayload accepted = response(first, "ack");
        accepted.setStatus(ProtocolStatus.Accept);
        first.channel.sendData(accepted).get(2, TimeUnit.SECONDS);
        received.expect(1).get(2, TimeUnit.SECONDS);
        assertReplyRejected(first.channel, response(first, "duplicate"));
        session.sendData(request(2, "bad encoding")).get(2, TimeUnit.SECONDS);
        ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);
        ResponsePayload invalid = response(second, "invalid");
        invalid.setReturnData(new String(new char[8192]));
        try {
            second.channel.sendData(invalid).get(2, TimeUnit.SECONDS);
            fail("Oversized frame must fail the write future");
        } catch (ExecutionException expected) {
            assertNotNull(expected.getCause());
        }
        assertReplyRejected(second.channel, response(second, "late"));
    }

    @Test
    public void completedRequestReleasesCapacityAndOldCompletionCannotRemoveReusedId() throws Exception {
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxPendingRequests", "1");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        ConnectorConfig config = new ConnectorConfig("server", config("rsf", port()).address(), options);
        Endpoint server = bind(endpoint(config, new Inbox() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        }));
        Endpoint client = endpoint(config("rsf", port()), received);
        RsfChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
        RequestPayload firstRequest = request(1, "expire");
        firstRequest.setClientTimeout(31337);
        session.sendData(firstRequest).get(2, TimeUnit.SECONDS);
        ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        first.payload.complete(new TimeoutException("RPC timed out"));
        firstRequest.complete(new TimeoutException("RPC timed out"));
        assertReplyRejected(first.channel, response(first, "late"));
        RequestPayload nextRequest = request(2, "next");
        nextRequest.setClientTimeout(31337);
        session.sendData(nextRequest).get(2, TimeUnit.SECONDS);
        ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);
        second.channel.sendData(response(second, "next")).get(2, TimeUnit.SECONDS);
        received.expect(2).get(2, TimeUnit.SECONDS);
        received.results.remove(2L);
        session.sendData(request(2, "reused")).get(2, TimeUnit.SECONDS);
        ReceivedRequest reused = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(reused);
        second.payload.complete(new TimeoutException("late completion"));
        reused.channel.sendData(response(reused, "reused")).get(2, TimeUnit.SECONDS);
        assertEquals("reused", received.expect(2).get(2, TimeUnit.SECONDS).getReturnData());
    }

    private void assertReplyRejected(RsfChannel channel, ResponsePayload response) throws Exception {
        try {
            channel.sendData(response).get(2, TimeUnit.SECONDS);
            fail("Unknown or completed request must reject a reply");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    private static class Inbox implements ReceivedListener {
        final Map<Long, RsfChannel>                   sources = new ConcurrentHashMap<>();
        final Map<Long, BasicFuture<ResponsePayload>> results = new ConcurrentHashMap<>();

        BasicFuture<ResponsePayload> expect(long id) {
            return this.results.computeIfAbsent(id, key -> new BasicFuture<>());
        }

        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
            ReceivedRequest exchange = new ReceivedRequest(channel, id);
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
            assertEquals(response.getRequestID(), id);
            this.sources.put(id, channel);
            expect(id).completed(response);
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            this.sources.put(id, channel);
            expect(id).failed(failure.getThrowable());
        }
    }

    private static RsfContext runtimeContext(ConnectorConfig... configs) {
        ClassLoader loader = TcpTransportTest.class.getClassLoader();
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Arrays.asList(configs);
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 3000;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
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
        private       RsfConnector     connector;

        private Endpoint(ConnectorConfig config, ReceivedListener listener) {
            this.config = config;
            this.manager = new ConnectorManager(runtimeContext(config), new EndpointConnectorFactory()) {
                @Override
                public Cancellable schedule(Runnable task, long delayMillis) {
                    if (TcpTransportTest.this.timersStopped) {
                        throw new RejectedExecutionException("Test timer stopped");
                    }
                    return super.schedule(task, delayMillis);
                }
            };
            subscribe(this.manager, listener);
        }

        private Future<RsfListen> bind(InterAddress address) {
            Future<RsfListen> result = this.manager.bind(this.config.name());
            this.connector = this.manager.find(this.config.name());
            return result;
        }

        private Future<RsfChannel> connect(InterAddress address) {
            Future<RsfChannel> result = this.manager.connect(address);
            this.connector = this.manager.find(this.config.name());
            return result;
        }
    }

    private static final class ReceivedRequest {
        private final RsfChannel     channel;
        private final long           id;
        private       RequestPayload payload;

        private ReceivedRequest(RsfChannel channel, long id) {
            this.channel = channel;
            this.id = id;
        }
    }
}

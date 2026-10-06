/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises native routes over real sockets, including shared physical connection ownership. */
public class EndpointRoutingTest {
    @Test(timeout = 15000)
    public void httpPartitionsSharePortAndConnectionWithoutMixingReplies() throws Exception {
        try (Runtime endpoint = new Runtime("http", HttpA.class, HttpB.class); Socket socket = endpoint.socket()) {
            sendHttp(socket, "/a", "first");
            sendHttp(socket, "/b", "second");
            Delivery a = endpoint.take();
            Delivery b = endpoint.take();
            assertEquals("a", a.channel.getRemote().getSchema());
            assertEquals("b", b.channel.getRemote().getSchema());
            assertEquals(a.id, b.id); // Correlation belongs to the protocol view.
            assertNotEquals(a.channel.getChannelId(), b.channel.getChannelId());
            assertSame(a.channel.getConnector(), b.channel.getConnector());
            Future<MetChannel> later = b.channel.sendData(response(b.id, "second"));
            assertFalse(later.isDone());
            a.channel.sendData(response(a.id, "first")).get(3, TimeUnit.SECONDS);
            assertEquals("A:first", readHttp(socket, 200));
            assertEquals("B:second", readHttp(socket, 200));
            later.get(3, TimeUnit.SECONDS);
            a.channel.close().get(3, TimeUnit.SECONDS);
            assertFalse(a.channel.isActive());
            assertTrue(b.channel.isActive());
            sendHttp(socket, "/b", "still open");
            Delivery next = endpoint.take();
            assertSame(b.channel, next.channel);
            next.channel.sendData(response(next.id, "still open")).get(3, TimeUnit.SECONDS);
            assertEquals("B:still open", readHttp(socket, 200));
        }
    }

    @Test(timeout = 10000)
    public void unknownHttpRouteReturns404AndKeepAliveRemainsUsable() throws Exception {
        try (Runtime endpoint = new Runtime("http", HttpA.class, HttpB.class); Socket socket = endpoint.socket()) {
            sendHttp(socket, "/absent", "bad");
            assertEquals("", readHttp(socket, 404));
            sendHttp(socket, "/a?query=1", "good");
            Delivery request = endpoint.take();
            request.channel.sendData(response(request.id, "good")).get(3, TimeUnit.SECONDS);
            assertEquals("A:good", readHttp(socket, 200));
        }
    }

    @Test(timeout = 10000)
    public void closingSharedEndpointDrainsSubmittedResponseAcrossPartitions() throws Exception {
        try (Runtime endpoint = new Runtime("http", HttpA.class, HttpB.class); Socket socket = endpoint.socket()) {
            sendHttp(socket, "/a", "unanswered");
            sendHttp(socket, "/b", "submitted");
            endpoint.take();
            Delivery b = endpoint.take();
            Future<MetChannel> writing = b.channel.sendData(response(b.id, "submitted"));
            ExecutorService lifecycle = Executors.newSingleThreadExecutor();
            try {
                FutureTask<Void> closing = new FutureTask<>(endpoint.manager::close, null);
                lifecycle.execute(closing);
                assertEquals("closing", readHttp(socket, 503));
                assertEquals("B:submitted", readHttp(socket, 200));
                writing.get(3, TimeUnit.SECONDS);
                closing.get(3, TimeUnit.SECONDS);
                assertEquals(-1, socket.getInputStream().read());
            } finally {
                lifecycle.shutdownNow();
            }
        }
    }

    @Test(timeout = 10000)
    public void outboundSchemesSelectProtocolWithoutBindingLocally() throws Exception {
        try (Runtime server = new Runtime("http", HttpA.class, HttpB.class); Runtime client = new Runtime("http", HttpA.class, HttpB.class)) {
            int port = server.manager.bind("shared").get().getBindAddress().getPort();
            for (String scheme : Arrays.asList("a", "b")) {
                MetChannel channel = client.manager.connect(new InterAddress(scheme, "127.0.0.1", port, "test")).get();
                assertEquals(scheme, channel.getRemote().getSchema());
                assertTrue(client.manager.find("shared").getListenList().isEmpty());
                RequestPayload request = new RequestPayload();
                request.setRequestID(33);
                request.setTargetMethod("outbound");
                channel.sendData(request).get(3, TimeUnit.SECONDS);
                Delivery incoming = server.take();
                incoming.channel.sendData(response(incoming.id, "outbound")).get(3, TimeUnit.SECONDS);
                Delivery answer = client.take();
                assertEquals(33, answer.id);
                assertEquals(scheme.toUpperCase(Locale.ROOT) + ":outbound", ((ResponsePayload) answer.payload).getReturnData());
            }
        }
    }

    @Test(timeout = 10000)
    public void tcpSelectsFragmentedPrefixAndKeepsSelectedProtocol() throws Exception {
        try (Runtime endpoint = new Runtime("tcp", WireA.class, WireB.class); Socket socket = endpoint.socket()) {
            socket.getOutputStream().write('B');
            socket.getOutputStream().flush();
            assertNull(endpoint.messages.poll(100, TimeUnit.MILLISECONDS));
            socket.getOutputStream().write("Bhello\n".getBytes(StandardCharsets.UTF_8));
            Delivery request = endpoint.take();
            assertEquals("b", request.channel.getRemote().getSchema());
            request.channel.sendData(response(request.id, "reply")).get(3, TimeUnit.SECONDS);
            assertEquals("BBreply", line(socket.getInputStream()));
            socket.getOutputStream().write("AAlater\n".getBytes(StandardCharsets.UTF_8));
            assertSame(request.channel, endpoint.take().channel);
        }
    }

    @Test(timeout = 10000)
    public void tcpRejectsUnknownPrefixWithoutDeliveringRpcMessages() throws Exception {
        try (Runtime endpoint = new Runtime("tcp", WireA.class, WireB.class); Socket socket = endpoint.socket()) {
            socket.getOutputStream().write("XX\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(endpoint.messages.isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void udpPartitionsCompleteDatagramsAndClosingOneViewKeepsPeerUsable() throws Exception {
        try (Runtime endpoint = new Runtime("udp", WireA.class, WireB.class); DatagramSocket peer = new DatagramSocket()) {
            int port = endpoint.manager.bind("shared").get().getBindAddress().getPort();
            peer.setSoTimeout(3000);
            sendDatagram(peer, port, "AAone\n");
            Delivery a = endpoint.take();
            sendDatagram(peer, port, "BBtwo\n");
            Delivery b = endpoint.take();
            assertNotSame(a.channel, b.channel);
            a.channel.close().get(3, TimeUnit.SECONDS);
            assertTrue(b.channel.isActive());
            b.channel.sendData(response(b.id, "reply")).get(3, TimeUnit.SECONDS);
            DatagramPacket response = new DatagramPacket(new byte[1024], 1024);
            peer.receive(response);
            assertEquals("BBreply\n", new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8));
        }
    }

    @Test(timeout = 10000)
    public void ambiguousTcpProtocolIsRejected() throws Exception {
        try (Runtime endpoint = new Runtime("tcp", WireA.class, WireA.class); Socket socket = endpoint.socket()) {
            socket.getOutputStream().write("AA\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(endpoint.messages.isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void incompleteProtocolProbeCannotBufferBeyondLimit() throws Exception {
        try (Runtime endpoint = new Runtime("tcp", IncompleteProbe.class, IncompleteProbe.class); Socket socket = endpoint.socket()) {
            socket.getOutputStream().write(new byte[256]);
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(endpoint.messages.isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void outgoingTcpPreselectsTargetProtocolBeforeFirstWrite() throws Exception {
        try (Runtime server = new Runtime("tcp", WireA.class, WireB.class); Runtime client = new Runtime("tcp", WireA.class, WireB.class)) {
            int port = server.manager.bind("shared").get().getBindAddress().getPort();
            MetChannel channel = client.manager.connect(new InterAddress("b", "127.0.0.1", port, "test")).get(3, TimeUnit.SECONDS);
            assertTrue(client.manager.find("shared").getListenList().isEmpty());
            channel.sendData(response(1, "first write")).get(3, TimeUnit.SECONDS);
            assertEquals("b", server.take().channel.getRemote().getSchema());
        }
    }

    public static class IncompleteProbe extends WireA {
        public RouteMatch probe(ProtocolConfig config, byte[] prefix) {
            return RouteMatch.NEED_MORE;
        }
    }

    private static void sendDatagram(DatagramSocket peer, int port, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        peer.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private static void sendHttp(Socket socket, String path, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static String line(InputStream stream) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = stream.read()) != -1 && value != '\n') {
            if (value != '\r') {
                line.write(value);
            }
        }
        return line.toString("UTF-8");
    }

    private static String readHttp(Socket socket, int status) throws Exception {
        InputStream stream = socket.getInputStream();
        assertTrue(line(stream).startsWith("HTTP/1.1 " + status));
        int length = 0;
        String header;
        while (!(header = line(stream)).isEmpty()) {
            if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(header.substring(15).trim());
            }
        }
        byte[] body = new byte[length];
        new DataInputStream(stream).readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    private static final class Delivery {
        final MetChannel channel;
        final long       id;
        final Payload    payload;

        Delivery(MetChannel channel, long id, Payload payload) {
            this.channel = channel;
            this.id = id;
            this.payload = payload;
        }
    }

    private static final class Runtime implements AutoCloseable {
        final ConnectorManager        manager;
        final BlockingQueue<Delivery> messages = new LinkedBlockingQueue<>();

        Runtime(String transport, Class<?> a, Class<?> b) throws Exception {
            Map<String, String> options = new HashMap<>();
            options.put("listenType", transport);
            options.put("workerThread", "1");
            ConnectorConfig config = new ConnectorConfig("shared", new InterAddress(transport, "127.0.0.1", port(), "test"), options, Arrays.asList(new ProtocolConfig("a", (transport.equals("udp") ? "datagram" : "") + a.getSimpleName().toLowerCase(Locale.ROOT), Collections.singletonMap("contextPath", "/a")), new ProtocolConfig("b", (transport.equals("udp") ? "datagram" : "") + b.getSimpleName().toLowerCase(Locale.ROOT), Collections.singletonMap("contextPath", "/b"))), true);
            ClassLoader loader = this.getClass().getClassLoader();
            MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, new Class<?>[] { MetSettings.class }, (proxy, method, args) -> {
                if (method.getName().equals("getConnectorConfigs")) {
                    return Collections.singleton(config);
                }
                throw new UnsupportedOperationException(method.getName());
            });
            MetContext context = (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, (proxy, method, args) -> {
                if (method.getName().equals("getSettings")) {
                    return settings;
                }
                if (method.getName().equals("getClassLoader")) {
                    return loader;
                }
                throw new UnsupportedOperationException(method.getName());
            });
            this.manager = new ConnectorManager(context, new EndpointConnectorFactory());
            this.manager.init();
            this.manager.subscribe((channel, id, payload) -> this.messages.add(new Delivery(channel, id, payload)));
        }

        private static int port() throws Exception {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }

        Socket socket() throws Exception {
            int port = this.manager.bind("shared").get(3, TimeUnit.SECONDS).getBindAddress().getPort();
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            return socket;
        }

        Delivery take() throws Exception {
            Delivery message = this.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull("Expected routed message", message);
            return message;
        }

        public void close() {
            this.manager.close();
        }
    }

    public static class HttpA implements ProtocolFactory<HttpExchange>, TestHttpCodec {
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

        private String path;
        private long   ids;

        protected String marker() {
            return "A";
        }

        public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
            HttpA adapter = this instanceof HttpB ? new HttpB() : new HttpA();
            adapter.path = config.option("contextPath", "/");
            return new TestHttpSession(adapter, context, connection, messages);
        }

        public TestHttpInvocation receive(HttpRequest request) {
            RequestPayload payload = new RequestPayload();
            payload.setRequestID(++this.ids);
            return TestHttpInvocation.dispatch(payload, response -> new HttpResponse(200, Collections.emptyMap(), (this.marker() + ":" + response.getReturnData()).getBytes(StandardCharsets.UTF_8)));
        }

        public HttpRequest encode(InterAddress target, RequestPayload request) {
            return new HttpRequest("POST", this.path, Collections.emptyMap(), new byte[0]);
        }

        public ResponsePayload decode(long id, HttpResponse response) {
            return response(id, new String(response.body(), StandardCharsets.UTF_8));
        }

        public HttpResponse error(Throwable error) {
            return new HttpResponse(503, Collections.emptyMap(), "closing".getBytes(StandardCharsets.UTF_8));
        }
    }

    public static class HttpB extends HttpA {
        protected String marker() {
            return "B";
        }
    }

    public static class WireA implements ProtocolFactory<byte[]> {
        @Override
        public String scheme() {
            return this.name();
        }

        public String transport() {
            return "tcp";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public String name() {
            return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        protected String marker() {
            return "AA";
        }

        public RouteMatch probe(ProtocolConfig config, byte[] prefix) {
            if (prefix.length < 2) {
                return RouteMatch.NEED_MORE;
            }
            return this.marker().equals(new String(prefix, 0, 2, StandardCharsets.US_ASCII)) ? RouteMatch.MATCH : RouteMatch.REJECT;
        }

        public ProtocolSession<byte[]> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<byte[]> network, BiConsumer<Long, Payload> messages) {
            return new WireSession(this.marker(), network, messages);
        }
    }

    public static class WireB extends WireA {
        protected String marker() {
            return "BB";
        }
    }

    public static class DatagramWireA extends WireA {
        public String transport() {
            return "udp";
        }

    }

    public static class DatagramWireB extends WireB {
        public String transport() {
            return "udp";
        }

    }

    private static final class WireSession implements ProtocolSession<byte[]> {
        private final String                    marker;
        private final NetworkChannel<byte[]>    network;
        private final BiConsumer<Long, Payload> messages;
        private final BasicFuture<Void>         ready = new BasicFuture<>((Void) null);
        private final StringBuilder             text  = new StringBuilder();
        private       long                      ids;

        WireSession(String marker, NetworkChannel<byte[]> network, BiConsumer<Long, Payload> messages) {
            this.marker = marker;
            this.network = network;
            this.messages = messages;
        }

        public Future<Void> ready() {
            return this.ready;
        }

        public void connected() {
        }

        public void receive(byte[] bytes) {
            this.text.append(new String(bytes, StandardCharsets.UTF_8));
            int end;
            while ((end = this.text.indexOf("\n")) >= 0) {
                RequestPayload payload = new RequestPayload();
                payload.setRequestID(++this.ids);
                this.messages.accept(payload.getRequestID(), payload);
                this.text.delete(0, end + 1);
            }
        }

        public Future<Void> send(Payload payload) {
            return this.network.write((this.marker + ((ResponsePayload) payload).getReturnData() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        public void prepareDrain() {
        }

        public void closed(Throwable cause) {
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests UDP through production SPI discovery and real loopback datagrams. */
public class UdpConnectorTest {
    @Test(timeout = 10000)
    public void peersExchangeMessagesInBothDirections() throws Exception {
        try (Host server = new Host(); Host client = new Host()) {
            RsfListen listen = server.bind();
            RsfChannel first = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            RsfChannel second = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertNotEquals(first.getChannelId(), second.getChannelId());
            first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
            Delivery a = server.take();
            second.sendData(request(1, "second")).get(2, TimeUnit.SECONDS);
            Delivery b = server.take();
            assertNotSame(a.channel, b.channel);
            assertNotEquals(a.channel.getRemote(), b.channel.getRemote());
            assertEquals(first.getLocal().getPort(), a.channel.getRemote().getPort());
            a.channel.sendData(response(1, "one")).get(2, TimeUnit.SECONDS);
            assertEquals("one", ((ResponsePayload) client.take().payload).getReturnData());
            b.channel.sendData(response(1, "two")).get(2, TimeUnit.SECONDS);
            assertEquals("two", ((ResponsePayload) client.take().payload).getReturnData());
            a.channel.sendData(request(2, "reverse")).get(2, TimeUnit.SECONDS);
            Delivery reverse = client.take();
            assertSame(first, reverse.channel);
            reverse.channel.sendData(response(2, "back")).get(2, TimeUnit.SECONDS);
            assertEquals("back", ((ResponsePayload) server.take().payload).getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void closeBindRejectsNewPeersButExistingPeerStillWorks() throws Exception {
        try (Host server = new Host(); Host client = new Host()) {
            RsfListen listen = server.bind();
            RsfChannel outgoing = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "establish")).get(2, TimeUnit.SECONDS);
            Delivery accepted = server.take();
            server.manager.find(server.config.name()).closeBind();
            assertFalse(listen.isActive());
            assertTrue(server.manager.find(server.config.name()).getListenList().isEmpty());
            assertTrue(failure(server.manager.bind(server.config.name())) instanceof IllegalStateException);
            RsfChannel newcomer = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            newcomer.sendData(request(2, "rejected")).get(2, TimeUnit.SECONDS);
            assertNull(server.messages.poll(200, TimeUnit.MILLISECONDS));
            outgoing.sendData(request(3, "existing")).get(2, TimeUnit.SECONDS);
            assertSame(accepted.channel, server.take().channel);
            accepted.channel.sendData(response(3, "still writable")).get(2, TimeUnit.SECONDS);
            assertEquals("still writable", ((ResponsePayload) client.take().payload).getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void closingOneAcceptedPeerDoesNotCloseSharedSocket() throws Exception {
        try (Host server = new Host(); Host client = new Host()) {
            RsfListen listen = server.bind();
            RsfChannel first = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            RsfChannel second = client.manager.connect(listen.getBindAddress()).get(2, TimeUnit.SECONDS);
            first.sendData(request(1, "one")).get(2, TimeUnit.SECONDS);
            Delivery a = server.take();
            second.sendData(request(2, "two")).get(2, TimeUnit.SECONDS);
            Delivery b = server.take();
            a.channel.close().get(2, TimeUnit.SECONDS);
            assertFalse(a.channel.isActive());
            assertTrue(listen.isActive());
            second.sendData(request(3, "again")).get(2, TimeUnit.SECONDS);
            assertSame(b.channel, server.take().channel);
            b.channel.sendData(response(3, "alive")).get(2, TimeUnit.SECONDS);
            assertEquals("alive", ((ResponsePayload) client.take().payload).getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void jvmPeerObservesIndependentDatagramsAndMalformedPeerIsIsolated() throws Exception {
        try (Host server = new Host(); DatagramSocket good = new DatagramSocket(); DatagramSocket bad = new DatagramSocket()) {
            RsfListen listen = server.bind();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(good, target, packet(1, 1, "short"));
            Delivery first = server.take();
            send(good, target, packet(1, 2, "a longer independent datagram"));
            Delivery second = server.take();
            assertSame(first.channel, second.channel);
            assertEquals("short", ((RequestPayload) first.payload).getTargetMethod());
            assertEquals("a longer independent datagram", ((RequestPayload) second.payload).getTargetMethod());
            send(bad, target, new byte[] { 1 });
            first.channel.sendData(response(1, "reply")).get(2, TimeUnit.SECONDS);
            good.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            good.receive(received);
            assertArrayEquals(packet(2, 1, "reply"), Arrays.copyOf(received.getData(), received.getLength()));
            send(good, target, packet(1, 3, "healthy"));
            assertSame(first.channel, server.take().channel);
        }
    }

    @Test(timeout = 10000)
    public void inboundLimitRejectsWholeDatagramWithoutTruncatingIt() throws Exception {
        try (Host server = new Host(16, TestProtocol.class); DatagramSocket peer = new DatagramSocket()) {
            RsfListen listen = server.bind();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", listen.getBindAddress().getPort());
            send(peer, target, packet(1, 1, "ok"));
            RsfChannel channel = server.take().channel;
            send(peer, target, packet(1, 2, "this datagram exceeds the configured limit"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (channel.isActive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(channel.isActive());
            assertNull(server.messages.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(listen.isActive());
        }
    }

    @Test(timeout = 10000)
    public void managerCloseDrainsSubmittedRepliesAndReleasesPort() throws Exception {
        try (Host server = new Host(); DatagramSocket peer = new DatagramSocket()) {
            RsfListen listen = server.bind();
            int port = listen.getBindAddress().getPort();
            send(peer, new InetSocketAddress("127.0.0.1", port), packet(1, 1, "hello"));
            Delivery request = server.take();
            Future<RsfChannel> writing = request.channel.sendData(response(1, "drained"));
            server.manager.close();
            assertSame(request.channel, writing.get(2, TimeUnit.SECONDS));
            assertFalse(request.channel.isActive());
            assertFalse(listen.isActive());
            peer.setSoTimeout(2000);
            DatagramPacket received = new DatagramPacket(new byte[512], 512);
            peer.receive(received);
            assertArrayEquals(packet(2, 1, "drained"), Arrays.copyOf(received.getData(), received.getLength()));
            try (DatagramSocket rebound = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test(timeout = 10000)
    public void rejectsStreamProtocolAndInvalidDatagramLimitsBeforeBind() throws Exception {
        try (Host wrongProtocol = new Host(128, EndpointRoutingTest.WireA.class)) {
            assertTrue(failure(wrongProtocol.manager.bind(wrongProtocol.config.name())) instanceof IllegalArgumentException);
        }
        for (int size : new int[] { 0, 65508 }) {
            try (Host invalid = new Host(size, TestProtocol.class)) {
                assertTrue(failure(invalid.manager.bind(invalid.config.name())) instanceof IllegalArgumentException);
            }
        }
    }

    private static Throwable failure(Future<?> result) throws Exception {
        try {
            result.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected failure");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static void send(DatagramSocket socket, InetSocketAddress remote, byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, remote));
    }

    private static byte[] packet(int type, long id, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(9 + text.length).put((byte) type).putLong(id).put(text).array();
    }

    private static RequestPayload request(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setTargetMethod(value);
        return request;
    }

    private static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setReturnData(value);
        return response;
    }

    private static final class Delivery {
        private final RsfChannel channel;
        private final Payload    payload;

        private Delivery(RsfChannel channel, Payload payload) {
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class Host implements AutoCloseable {
        private final ConnectorConfig         config;
        private final ConnectorManager        manager;
        private final BlockingQueue<Delivery> messages = new LinkedBlockingQueue<>();

        private Host() throws Exception {
            this(65507, TestProtocol.class);
        }

        private Host(int size, Class<?> protocol) throws Exception {
            int port;
            try (DatagramSocket available = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                port = available.getLocalPort();
            }
            Map<String, String> options = new HashMap<>();
            options.put("listenType", "udp");
            options.put("protocol", protocol.getSimpleName().toLowerCase(Locale.ROOT));
            options.put("workerThread", "1");
            options.put("maxDatagramSize", String.valueOf(size));
            this.config = new ConnectorConfig("udp", new InterAddress("udp", "127.0.0.1", port, "default"), options);
            ClassLoader loader = getClass().getClassLoader();
            RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
                if ("getConnectorConfigs".equals(method.getName())) {
                    return Collections.singletonList(this.config);
                }
                throw new AssertionError(method);
            });
            RsfContext context = (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
                if ("getSettings".equals(method.getName())) {
                    return settings;
                }
                if ("getClassLoader".equals(method.getName())) {
                    return loader;
                }
                throw new AssertionError(method);
            });
            this.manager = new ConnectorManager(context, new EndpointConnectorFactory());
            this.manager.init();
            this.manager.subscribe((channel, id, payload) -> this.messages.add(new Delivery(channel, payload)));
        }

        private RsfListen bind() throws Exception {
            return this.manager.bind(this.config.name()).get(2, TimeUnit.SECONDS);
        }

        private Delivery take() throws Exception {
            Delivery message = this.messages.poll(2, TimeUnit.SECONDS);
            assertNotNull("Expected a decoded datagram", message);
            return message;
        }

        public void close() {
            this.manager.close();
        }
    }

    public static final class TestProtocol implements ProtocolFactory<byte[]> {
        public String transport() {
            return "udp";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public String name() {
            return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        public ProtocolSession<byte[]> create(ProtocolConfig config, ProtocolContext manager, NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
            return new ProtocolSession<byte[]>() {
                private final BasicFuture<Void> ready = new BasicFuture<>();

                public Future<Void> ready() {
                    return this.ready;
                }

                public void connected() {
                    this.ready.completed(null);
                }

                public void receive(byte[] datagram) throws Exception {
                    if (datagram.length < 9) {
                        throw new IOException("Incomplete datagram");
                    }
                    ByteBuffer bytes = ByteBuffer.wrap(datagram);
                    int type = bytes.get();
                    long id = bytes.getLong();
                    String value = new String(datagram, 9, datagram.length - 9, StandardCharsets.UTF_8);
                    if (type != 1 && type != 2) {
                        throw new IOException("Unknown datagram type");
                    }
                    messages.accept(id, type == 1 ? request(id, value) : response(id, value));
                }

                public Future<Void> send(Payload payload) {
                    if (payload instanceof RequestPayload) {
                        RequestPayload request = (RequestPayload) payload;
                        return connection.write(packet(1, request.getRequestID(), request.getTargetMethod()));
                    }
                    ResponsePayload response = (ResponsePayload) payload;
                    return connection.write(packet(2, response.getRequestID(), String.valueOf(response.getReturnData())));
                }

                public void prepareDrain() {
                }

                public void closed(Throwable cause) {
                    this.ready.failed(cause);
                }
            };
        }
    }
}

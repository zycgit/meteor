/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.RouteTestAccess;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import org.junit.jupiter.api.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** A small test-only multiplexed protocol; no Payload, MetChannel or RPC runtime. */
public class TcpExtensionTest {
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void flowControlIsPerStream() throws Exception {
        // 仅准备 TCP 传输配置，不安装 RPC 或应用协议插件。
        ConnectorConfig config = this.config(Map.of());
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 第二条流的 demand 只能放行自身，第一条仍等待自己的配额。
            Future<Integer> first = session.submit(1, 11);
            Future<Integer> second = session.submit(3, 33);
            session.request(3, 1);
            assertEquals(Integer.valueOf(33), second.get(2, TimeUnit.SECONDS));
            assertFalse(first.isDone(), "A stream without demand must stay pending");
            assertTrue(session.connection.isOpen());
            session.request(1, 1);
            assertEquals(Integer.valueOf(11), first.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellationIsPerStream() throws Exception {
        // 仅准备 TCP 传输配置，不安装 RPC 或应用协议插件。
        ConnectorConfig config = this.config(Map.of());
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 取消未获配额的流，其他流仍可完成一次收发。
            Future<Integer> cancelled = session.submit(5, 55);
            session.cancel(5);
            Future<Integer> next = session.submit(7, 77);
            session.request(7, 1);
            assertEquals(Integer.valueOf(77), next.get(2, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
            assertEquals(Collections.emptyMap(), responder.get().pending);
            assertTrue(session.connection.isOpen());
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void drainAndCloseIsIdempotent() throws Exception {
        // 仅准备 TCP 传输配置，不安装 RPC 或应用协议插件。
        ConnectorConfig config = this.config(Map.of());
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 在成功收发之后排空关闭，重复关闭返回同一完成信号。
            Future<Integer> result = session.submit(1, 11);
            session.request(1, 1);
            assertEquals(Integer.valueOf(11), result.get(2, TimeUnit.SECONDS));
            session.connection.drainAndClose().get(2, TimeUnit.SECONDS);
            assertFalse(session.connection.isOpen());
            assertSame(session.connection.close(), session.connection.drainAndClose());
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void transportStagesPreservePerStreamFlowControl() throws Exception {
        // 在两端传输流水线中安装异或编码，应用仍使用原始帧。
        XorPipeline.clients.set(0);
        XorPipeline.servers.set(0);
        XorPipeline.reads.set(0);
        XorPipeline.writes.set(0);
        Map<String, String> extensions = Map.of("transportPipeline", XorPipeline.class.getName());
        ConnectorConfig config = this.config(extensions);
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 第二条流的 demand 只能放行自身，第一条仍等待自己的配额。
            Future<Integer> first = session.submit(1, 11);
            Future<Integer> second = session.submit(3, 33);
            session.request(3, 1);
            assertEquals(Integer.valueOf(33), second.get(2, TimeUnit.SECONDS));
            assertFalse(first.isDone(), "A stream without demand must stay pending");
            assertTrue(session.connection.isOpen());
            session.request(1, 1);
            assertEquals(Integer.valueOf(11), first.get(2, TimeUnit.SECONDS));

            // 成功收发必须实际经过客户端、服务端及双向流水线阶段。
            assertEquals(1, XorPipeline.clients.get());
            assertEquals(1, XorPipeline.servers.get());
            assertTrue(XorPipeline.reads.get() > 0);
            assertTrue(XorPipeline.writes.get() > 0);
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void transportStagesPreserveCancellationIsolation() throws Exception {
        // 在两端传输流水线中安装异或编码，应用仍使用原始帧。
        XorPipeline.clients.set(0);
        XorPipeline.servers.set(0);
        XorPipeline.reads.set(0);
        XorPipeline.writes.set(0);
        Map<String, String> extensions = Map.of("transportPipeline", XorPipeline.class.getName());
        ConnectorConfig config = this.config(extensions);
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 取消未获配额的流，其他流仍可完成一次收发。
            Future<Integer> cancelled = session.submit(5, 55);
            session.cancel(5);
            Future<Integer> next = session.submit(7, 77);
            session.request(7, 1);
            assertEquals(Integer.valueOf(77), next.get(2, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
            assertEquals(Collections.emptyMap(), responder.get().pending);
            assertTrue(session.connection.isOpen());

            // 成功收发必须实际经过客户端、服务端及双向流水线阶段。
            assertEquals(1, XorPipeline.clients.get());
            assertEquals(1, XorPipeline.servers.get());
            assertTrue(XorPipeline.reads.get() > 0);
            assertTrue(XorPipeline.writes.get() > 0);
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void transportStagesPreserveIdempotentDrainAndClose() throws Exception {
        // 在两端传输流水线中安装异或编码，应用仍使用原始帧。
        XorPipeline.clients.set(0);
        XorPipeline.servers.set(0);
        XorPipeline.reads.set(0);
        XorPipeline.writes.set(0);
        Map<String, String> extensions = Map.of("transportPipeline", XorPipeline.class.getName());
        ConnectorConfig config = this.config(extensions);
        ClassLoader loader = this.getClass().getClassLoader();
        InterAddress address = config.address();
        TransportConfig transportConfig = transport(config);
        try (NetworkConnector<byte[]> server = this.connector(transportConfig, loader); //
             NetworkConnector<byte[]> client = this.connector(transportConfig, loader)) {
            // 将测试帧协议装入真实 TCP 连接，等待接收器就绪。
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            RouteTable<byte[]> routes = RouteTestAccess.activate(Collections.emptyMap(), null);
            NetworkListen listen = server.bind(address, routes, this.receiver(true, responder));
            int port = listen.getAddress().getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            Future<Void> connecting = client.connect(null, remote, routes, this.receiver(false, requester));
            connecting.get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            // 在成功收发之后排空关闭，重复关闭返回同一完成信号。
            Future<Integer> result = session.submit(1, 11);
            session.request(1, 1);
            assertEquals(Integer.valueOf(11), result.get(2, TimeUnit.SECONDS));
            session.connection.drainAndClose().get(2, TimeUnit.SECONDS);
            assertFalse(session.connection.isOpen());
            assertSame(session.connection.close(), session.connection.drainAndClose());

            // 成功收发必须实际经过客户端、服务端及双向流水线阶段。
            assertEquals(1, XorPipeline.clients.get());
            assertEquals(1, XorPipeline.servers.get());
            assertTrue(XorPipeline.reads.get() > 0);
            assertTrue(XorPipeline.writes.get() > 0);
        }
    }

    private ConnectorConfig config(Map<String, String> extensions) {
        Map<String, String> options = new HashMap<>(extensions);
        options.put("workerThread", "1");
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        ProtocolConfig mount = ProtocolConfig.newConfig("mux", "mux", options);
        return new ConnectorConfig("mux", address, options, List.of(mount), true);
    }

    private NetworkConnector<byte[]> connector(TransportConfig config, ClassLoader loader) {
        TransportPlan<byte[]> plan = new TcpTransportFactory().prepare(config, loader);
        return plan.create(TransportTestServices.newRuntimeServices(loader));
    }

    private ChannelListenerFactory<byte[]> receiver(boolean server, AtomicReference<MuxSession> session) {
        return connection -> {
            MuxSession created = new MuxSession(connection, server);
            session.set(created);
            return created;
        };
    }

    private static final class MuxSession implements ChannelListener<byte[]> {
        private static final int                                DATA    = 1;
        private static final int                                DEMAND  = 2;
        private static final int                                CANCEL  = 3;
        private final        NetworkChannel<byte[]>             connection;
        private final        boolean                            server;
        private final        BasicFuture<Void>                  ready   = new BasicFuture<>();
        private final        Map<Integer, Integer>              credits = new HashMap<>();
        private final        Map<Integer, Deque<Integer>>       pending = new ConcurrentHashMap<>();
        private final        Map<Integer, BasicFuture<Integer>> results = new HashMap<>();
        private final        byte[]                             frame   = new byte[9];
        private              int                                used;

        private MuxSession(NetworkChannel<byte[]> connection, boolean server) {
            this.connection = connection;
            this.server = server;
        }

        public Future<Void> ready() {
            return this.ready;
        }

        public void connected() {
            assertFalse(NetChannel.isCurrentThreadInPipeline());
            this.ready.completed(null);
        }

        private Future<Integer> submit(int stream, int value) {
            BasicFuture<Integer> result = new BasicFuture<>();
            this.connection.execute(() -> {
                this.results.put(stream, result);
                this.send(DATA, stream, value);
            });
            return result;
        }

        private void request(int stream, int count) {
            this.connection.execute(() -> this.send(DEMAND, stream, count));
        }

        private void cancel(int stream) {
            this.connection.execute(() -> {
                this.results.remove(stream).cancel();
                this.send(CANCEL, stream, 0);
            });
        }

        private void send(int type, int stream, int value) {
            byte[] bytes = ByteBuffer.allocate(9).put((byte) type).putInt(stream).putInt(value).array();
            this.connection.write(bytes).onFailed(done -> this.connection.close());
        }

        public void receive(byte[] bytes) {
            assertFalse(NetChannel.isCurrentThreadInPipeline());
            // TCP 不保证分包边界，累计满一帧后才交给测试协议。
            for (byte value : bytes) {
                this.frame[this.used++] = value;
                if (this.used == this.frame.length) {
                    ByteBuffer buffer = ByteBuffer.wrap(this.frame);
                    this.receive(buffer.get(), buffer.getInt(), buffer.getInt());
                    this.used = 0;
                }
            }
        }

        private void receive(int type, int stream, int value) {
            // 客户端按流号完成结果；服务端维护独立的配额和待发数据。
            if (!this.server) {
                this.results.remove(stream).completed(value);
                return;
            }
            if (type == CANCEL) {
                this.pending.remove(stream);
                this.credits.remove(stream);
                return;
            }
            if (type == DEMAND) {
                this.credits.merge(stream, value, Integer::sum);
            } else {
                this.pending.computeIfAbsent(stream, id -> new ArrayDeque<>()).addLast(value);
            }
            Deque<Integer> values = this.pending.get(stream);
            while (values != null && !values.isEmpty() && this.credits.getOrDefault(stream, 0) > 0) {
                this.credits.compute(stream, (id, credit) -> credit - 1);
                this.send(DATA, stream, values.removeFirst());
            }
            if (values != null && values.isEmpty()) {
                this.pending.remove(stream);
            }
        }

        public void closed(Throwable cause) {
            assertFalse(NetChannel.isCurrentThreadInPipeline());
            this.ready.failed(cause);
            for (BasicFuture<Integer> result : this.results.values()) {
                result.failed(cause);
            }
            this.results.clear();
            this.pending.clear();
        }
    }

    public static final class XorPipeline implements TransportPipeline {
        private static final AtomicInteger clients = new AtomicInteger();
        private static final AtomicInteger servers = new AtomicInteger();
        private static final AtomicInteger reads   = new AtomicInteger();
        private static final AtomicInteger writes  = new AtomicInteger();

        public void initialize(TransportConfig config, ProtoBuildContext stack) {
            AtomicInteger connections = servers;
            if (stack.getChannel().isClient()) {
                connections = clients;
            }
            connections.incrementAndGet();
            stack.addLast("xor", new XorDuplex());
        }
    }

    private static final class XorDuplex implements ProtoDuplex<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
        public ProtoStatus onMessage( //
                ProtoContext context, //
                boolean receiving, //
                ProtoRcvQueue<ByteBuf> input, //
                ProtoSndQueue<ByteBuf> decoded, //
                ProtoRcvQueue<ByteBuf> output, //
                ProtoSndQueue<ByteBuf> encoded) {
            ProtoRcvQueue<ByteBuf> source = receiving ? input : output;
            ProtoSndQueue<ByteBuf> target = receiving ? decoded : encoded;
            // 流水线只变换字节，保留队列背压和 ByteBuf 的释放职责。
            while (source.hasMore() && target.hasSlot()) {
                ByteBuf buffer = source.takeMessage();
                byte[] bytes = new byte[buffer.readableBytes()];
                try {
                    buffer.readBytes(bytes);
                } finally {
                    buffer.release();
                }
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] ^= 0x5a;
                }
                target.offerMessage(ByteBuf.wrap(bytes));
                AtomicInteger operations = XorPipeline.writes;
                if (receiving) {
                    operations = XorPipeline.reads;
                }
                operations.incrementAndGet();
            }
            return ProtoStatus.Next;
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import org.junit.Test;
import static org.junit.Assert.*;

/** A small test-only multiplexed protocol; no Payload, MetChannel or RPC runtime. */
public class TcpExtensionTest {
    @Test(timeout = 10000)
    public void flowControlAndCancellationArePerStream() throws Exception {
        this.roundTrip(false);
    }

    @Test(timeout = 10000)
    public void transportStagesWrapBothDirectionsBeforeApplicationFrames() throws Exception {
        XorPipeline.clients.set(0);
        XorPipeline.servers.set(0);
        XorPipeline.reads.set(0);
        XorPipeline.writes.set(0);
        this.roundTrip(true);
        assertEquals(1, XorPipeline.clients.get());
        assertEquals(1, XorPipeline.servers.get());
        assertTrue(XorPipeline.reads.get() > 0);
        assertTrue(XorPipeline.writes.get() > 0);
    }

    private void roundTrip(boolean pipeline) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "tcp");
        options.put("workerThread", "1");
        if (pipeline) {
            options.put("transportPipeline", XorPipeline.class.getName());
        }
        int availablePort;
        try (ServerSocket available = new ServerSocket(0)) {
            availablePort = available.getLocalPort();
        }
        InterAddress address = new InterAddress("mux", "127.0.0.1", availablePort, "default");
        ConnectorConfig config = new ConnectorConfig("mux", address, options, Collections.singletonList(new ProtocolConfig("mux", address.getSchema(), options.getOrDefault("protocol", address.getSchema()), options)), true);
        try (SocketTransport server = new SocketTransport(config, this.getClass().getClassLoader(), SoConfig.TCP()); SocketTransport client = new SocketTransport(config, this.getClass().getClassLoader(), SoConfig.TCP())) {
            AtomicReference<MuxSession> responder = new AtomicReference<>();
            NetListen listen = server.bind(address, stack -> this.attach(stack, address, server, true, responder));
            int port = ((InetSocketAddress) listen.getLocalAddr()).getPort();
            InterAddress remote = new InterAddress("mux", "127.0.0.1", port, "default");
            AtomicReference<MuxSession> requester = new AtomicReference<>();
            client.connect(remote, stack -> this.attach(stack, remote, client, false, requester)).get(2, TimeUnit.SECONDS);
            MuxSession session = requester.get();
            session.ready().get(2, TimeUnit.SECONDS);

            Future<Integer> first = session.submit(1, 11);
            Future<Integer> second = session.submit(3, 33);
            session.request(3, 1);
            assertEquals(Integer.valueOf(33), second.get(2, TimeUnit.SECONDS));
            assertFalse("A stream without demand must stay pending", first.isDone());
            assertTrue(session.connection.isOpen());
            session.request(1, 1);
            assertEquals(Integer.valueOf(11), first.get(2, TimeUnit.SECONDS));

            Future<Integer> cancelled = session.submit(5, 55);
            session.cancel(5);
            Future<Integer> next = session.submit(7, 77);
            session.request(7, 1);
            assertEquals(Integer.valueOf(77), next.get(2, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
            assertEquals(Collections.emptyMap(), responder.get().pending);
            assertTrue(session.connection.isOpen());
            session.connection.drainAndClose().get(2, TimeUnit.SECONDS);
            assertSame(session.connection.close(), session.connection.drainAndClose());
        }
    }

    private void attach(ProtoBuildContext stack, InterAddress address, SocketTransport transport, boolean server, AtomicReference<MuxSession> session) {
        try {
            TcpConnection.attach(stack, address, transport.nextExecutor(), 256, connection -> {
                MuxSession created = new MuxSession(connection, server);
                session.set(created);
                return created;
            });
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
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

        public void initialize(ConnectorConfig config, ProtoBuildContext stack) {
            (stack.getChannel().isClient() ? clients : servers).incrementAndGet();
            stack.addLast("xor", new XorDuplex());
        }
    }

    private static final class XorDuplex implements ProtoDuplex<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
        public ProtoStatus onMessage(ProtoContext context, boolean receiving, ProtoRcvQueue<ByteBuf> input, ProtoSndQueue<ByteBuf> decoded, ProtoRcvQueue<ByteBuf> output, ProtoSndQueue<ByteBuf> encoded) {
            ProtoRcvQueue<ByteBuf> source = receiving ? input : output;
            ProtoSndQueue<ByteBuf> target = receiving ? decoded : encoded;
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
                (receiving ? XorPipeline.reads : XorPipeline.writes).incrementAndGet();
            }
            return ProtoStatus.Next;
        }
    }
}

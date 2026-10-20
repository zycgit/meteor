/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.example.connector.protocol.RawSocketTestProtocol;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.payload.Payload;
import org.junit.Test;
import static org.junit.Assert.*;

/** 用可控的传输激活与协议握手验证端点连接结果的所有权。 */
public class ConnectorEndpointConnectionTest {
    @Test
    public void alreadyReadySessionWaitsForTransportActivation() throws Exception {
        // 协议在创建前已经就绪，但底层通道还没有交付 connected 事件。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            fixture.session.ready.completed(null);
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            assertFalse(connecting.isDone());
            assertEquals(0, fixture.sessionsCreated);
            assertEquals(0, fixture.session.activations);

            // 激活后先启动会话，再把已就绪的通道交给连接调用方。
            fixture.network.activate();
            assertNotNull(connecting.get(1, TimeUnit.SECONDS));
            assertEquals(1, fixture.session.activations);
        }
    }

    @Test
    public void transportFailureBeforeNetworkAttachmentKeepsItsCause() throws Exception {
        // 逻辑通道已经创建，传输在交付底层通道前报告连接失败。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            IOException failure = new IOException("Transport connection failed");
            fixture.network.connecting.failed(failure);

            // 同一个 ready 传回原始错误，尚未创建协议会话。
            ExecutionException actual = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertSame(failure, actual.getCause());
            assertEquals(0, fixture.sessionsCreated);
        }
    }

    @Test
    public void cancellationBeforeNetworkAttachmentClosesTheLateChannel() throws Exception {
        // 取消时尚未绑定底层通道，之后模拟迟到的绑定和激活。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            assertTrue(connecting.cancel());
            fixture.network.attach();
            fixture.network.activate();

            // 迟到通道不能被交付，也不能重新激活已取消的会话。
            assertTrue(connecting.isCancelled());
            assertFalse(fixture.network.channel.isOpen());
            assertEquals(0, fixture.sessionsCreated);
            assertEquals(0, fixture.session.activations);
        }
    }

    @Test
    public void endpointShutdownDrainsAPendingConnection() throws Exception {
        // 底层通道尚未到达，逻辑通道已经属于端点的关闭范围。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.endpoint.beginClose();
            List<Future<MetChannel>> closing = fixture.endpoint.drainChannels();

            // 没有会话和写入需要排空，关闭立即结束连接等待。
            assertEquals(1, closing.size());
            assertNotNull(closing.get(0).get(1, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals(0, fixture.sessionsCreated);
        }
    }

    @Test
    public void synchronousTransportFailureKeepsItsCause() throws Exception {
        // 发起底层连接时同步抛错，也必须结束已经创建的逻辑通道。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            IllegalStateException failure = new IllegalStateException("Transport initialization failed");
            fixture.network.connectionFailure = failure;
            Future<MetChannel> connecting = fixture.connect();

            // 错误通过 ready 交付，不创建协议会话，也不遗留待关闭的通道。
            ExecutionException actual = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertSame(failure, actual.getCause());
            assertEquals(0, fixture.sessionsCreated);
            assertTrue(fixture.endpoint.drainChannels().isEmpty());
        }
    }

    @Test
    public void sessionCreationFailureClosesTheActivatedConnection() throws Exception {
        // 会话延迟到网络激活时创建，在提供者创建会话的阶段注入失败。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            IllegalStateException failure = new IllegalStateException("Protocol session initialization failed");
            fixture.sessionFailure = failure;
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();

            // 创建失败保留原始原因，并释放已建立的底层连接。
            ExecutionException actual = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertSame(failure, actual.getCause());
            assertFalse(fixture.network.channel.isOpen());
            assertEquals(0, fixture.session.activations);
        }
    }

    @Test
    public void cancellationDuringHandshakeClosesTheChannel() throws Exception {
        // 物理连接已激活，但协议握手保持未完成。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();
            assertFalse(connecting.isDone());

            // 取消连接操作会关闭尚未交付的通道，并释放物理连接。
            assertTrue(connecting.cancel());
            assertFalse(fixture.network.channel.isOpen());
            assertEquals(1, fixture.session.closures);
        }
    }

    @Test
    public void handshakeFailureKeepsItsCauseAndClosesTheChannel() throws Exception {
        // 让协议在已激活的连接上报告握手失败。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();
            IOException failure = new IOException("Protocol handshake failed");
            fixture.session.ready.failed(failure);

            // 连接结果保留原始握手错误，失败通道随即关闭。
            ExecutionException actual = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertSame(failure, actual.getCause());
            assertFalse(fixture.network.channel.isOpen());
        }
    }

    @Test
    public void transportClosureBeforeActivationFailsTheConnection() throws Exception {
        // 已创建协议通道，但物理连接在激活之前关闭。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.channel.close();

            // 关闭事件直接结束就绪等待，不依赖后续 connected 或握手回调。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals(0, fixture.session.activations);
        }
    }

    @Test
    public void cancellationDuringHandshakeCompletionPreventsChannelDelivery() throws Exception {
        // 在握手完成通知中先取消外部操作，固定完成与取消交错的窗口。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();
            fixture.session.ready.onCompleted(done -> connecting.cancel());

            // 随后的 Channel 就绪通知不能覆盖取消，也不能遗留未交付的连接。
            fixture.session.ready.completed(null);
            assertTrue(connecting.isCancelled());
            assertNull(connecting.getResult());
            assertFalse(fixture.network.channel.isOpen());
        }
    }

    @Test
    public void cancelledProtocolHandshakeFailsTheConnection() throws Exception {
        // 取消发生在协议自身的握手 Future，而不是外部连接操作。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();
            fixture.session.ready.cancel();

            // 调用方收到握手取消错误，连接操作本身不被误标成用户取消。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof CancellationException);
            assertFalse(connecting.isCancelled());
            assertFalse(fixture.network.channel.isOpen());
        }
    }

    @Test
    public void queuedReadinessCannotDeliverACancelledConnection() throws Exception {
        // 握手完成通知已排入队列，在执行通知前取消连接操作。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.network.activate();
            fixture.network.channel.deferTasks = true;
            fixture.session.ready.completed(null);
            assertTrue(connecting.cancel());

            // 排队的就绪通知不能覆盖取消，底层连接也必须释放。
            fixture.network.channel.runTasks();
            assertTrue(connecting.isCancelled());
            assertNull(connecting.getResult());
            assertFalse(fixture.network.channel.isOpen());
        }
    }

    @Test
    public void rejectedActivationFailsThePreparedChannel() throws Exception {
        // 创建协议通道后关闭管理器，使迟到的物理激活被端点拒绝。
        try (ConnectionFixture fixture = new ConnectionFixture()) {
            Future<MetChannel> connecting = fixture.connect();
            fixture.network.attach();
            fixture.manager.close();
            fixture.network.activate();

            // 拒绝接入通过通道的失败通知传播，且不启动协议会话。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connecting.get(1, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(0, fixture.session.activations);
            assertFalse(fixture.network.channel.isOpen());
        }
    }

    private static final class ConnectionFixture implements AutoCloseable {
        private final ControlledSession         session = new ControlledSession();
        private final ControlledNetwork         network = new ControlledNetwork();
        private final ConnectorManager          manager;
        private final ConnectorEndpoint<byte[]> endpoint;
        private       int                       sessionsCreated;
        private       RuntimeException          sessionFailure;

        private ConnectionFixture() throws Exception {
            // 使用真实的端点组装对象，替换传输时序和协议握手，避免依赖线程调度或休眠。
            ProtocolConfig protocol = new ProtocolConfig("rawprotocol", "raw", Map.of("serializeType", "Java"));
            InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
            ConnectorConfig config = new ConnectorConfig("client", local, Map.of(), List.of(protocol), false);
            this.manager = EndpointFixture.manager(config);
            RouteMountIndex index = new RouteMountIndex(config.name(), protocol);
            ProtocolProvider<byte[]> provider = new RawSocketTestProtocol() {
                @Override
                public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
                        NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
                    ConnectionFixture.this.sessionsCreated++;
                    if (ConnectionFixture.this.sessionFailure != null) {
                        throw ConnectionFixture.this.sessionFailure;
                    }
                    return ConnectionFixture.this.session;
                }
            };
            ProtocolMountBinding<byte[]> binding = new ProtocolMountBinding<>(protocol, provider, index);
            Map<String, ProtocolMountBinding<byte[]>> bindings = Map.of(protocol.name(), binding);

            // 端点和通道仍由生产代码管理，测试夹具只提供可控的网络实现。
            TransportConfig transport = new TransportConfig(//
                    config.name(), local, false, Map.of(), Map.of(protocol.name(), protocol.options()));
            TransportPlan<byte[]> transportPlan = TransportPlan.direct(transport, (prepared, runtime) -> this.network);
            PlanEndpoint<byte[]> plan = new PlanEndpoint<>(config, transportPlan, false, bindings);
            this.manager.init();
            this.endpoint = new ConnectorEndpoint<>(plan, this.manager);
            this.endpoint.init();
        }

        private Future<MetChannel> connect() {
            return this.endpoint.connect("rawprotocol", EndpointFixture.address("raw"));
        }

        @Override
        public void close() {
            try {
                this.endpoint.closeTransport();
            } finally {
                this.manager.close();
            }
        }
    }

    private static final class ControlledSession implements ProtocolSession<byte[]> {
        private final BasicFuture<Void> ready = new BasicFuture<>();
        private       int               activations;
        private       int               closures;

        @Override
        public Future<Void> ready() {
            return this.ready;
        }

        @Override
        public void connected() {
            this.activations++;
        }

        @Override
        public void receive(byte[] message) {
        }

        @Override
        public Future<Void> send(Payload payload) {
            return new BasicFuture<>((Void) null);
        }

        @Override
        public void prepareDrain() {
        }

        @Override
        public void closed(Throwable cause) {
            this.closures++;
            this.ready.failed(cause);
        }
    }

    private static final class ControlledNetwork implements NetworkConnector<byte[]> {
        private final BasicFuture<Void>              connecting = new BasicFuture<>();
        private final ChannelListenerRelay<byte[]>   relay      = new ChannelListenerRelay<>(() -> {
        });
        private final ControlledChannel              channel    = new ControlledChannel(this.relay::closed);
        private       ChannelListenerFactory<byte[]> factory;
        private       RuntimeException               connectionFailure;

        private ControlledNetwork() {
            this.connecting.onCancel(done -> this.channel.close());
        }

        @Override
        public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                ChannelListenerFactory<byte[]> factory) {
            throw new UnsupportedOperationException("Connection fixture does not listen");
        }

        @Override
        public Future<Void> connect(String initial, InterAddress address, RouteTable<byte[]> routes,  //
                ChannelListenerFactory<byte[]> factory) {
            if (this.connectionFailure != null) {
                throw this.connectionFailure;
            }
            this.factory = factory;
            return this.connecting;
        }

        private void attach() throws Exception {
            this.relay.attach(this.factory.createListener(this.channel));
        }

        private void activate() {
            this.relay.connected();
            this.connecting.completed(null);
        }

        @Override
        public void close() {
            this.channel.close();
            this.channel.runTasks();
        }
    }

    private static final class ControlledChannel implements NetworkChannel<byte[]> {
        private final Consumer<Throwable>  termination;
        private final BasicFuture<Void>    closed = new BasicFuture<>();
        private final ArrayDeque<Runnable> tasks  = new ArrayDeque<>();
        private       boolean              open   = true;
        private       boolean              deferTasks;

        private ControlledChannel(Consumer<Throwable> termination) {
            this.termination = termination;
        }

        @Override
        public InterAddress getLocal() {
            return EndpointFixture.address("tcp");
        }

        @Override
        public InterAddress getRemote() {
            return EndpointFixture.address("tcp");
        }

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public Future<Void> write(byte[] message) {
            return new BasicFuture<>((Void) null);
        }

        @Override
        public void execute(Runnable action) {
            if (this.deferTasks) {
                this.tasks.addLast(action);
            } else {
                action.run();
            }
        }

        private void runTasks() {
            Runnable action;
            while ((action = this.tasks.pollFirst()) != null) {
                action.run();
            }
        }

        @Override
        public Future<Void> close() {
            if (this.open) {
                this.open = false;
                this.termination.accept(new IOException("Controlled transport closed"));
                this.closed.completed(null);
            }
            return this.closed;
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}

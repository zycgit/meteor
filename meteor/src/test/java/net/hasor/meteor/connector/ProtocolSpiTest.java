/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.tcp.TcpTransportFactory;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.example.connector.protocol.EndpointTestProtocols.HttpA;
import net.hasor.example.connector.protocol.RawSocketTestProtocol;
import net.hasor.example.connector.protocol.TextProtocol;
import net.hasor.neta.channel.ProtoBuildContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 验证 SPI 发现、配置准备和连接器延迟创建契约。 */
public class ProtocolSpiTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Before
    public void reset() {
        CustomTransport.created.set(0);
        CustomTransport.prepared.set(0);
        RetryPreparationTransport.attempts.set(0);
        AdmissionTransportFactory.creations.clear();
        CloseBarrierTransportFactory.creations.clear();
        CustomProtocol.configurations.clear();
        CountingTransportPipeline.reset();
        FailingOnceTransportPipeline.attempts.set(0);
    }

    @Test
    public void failedTransportPreparationCanBeRetried() throws Exception {
        // 第一次端点准备由传输扩展主动失败，保留同一个配置对象供后续重试。
        Class<?>[] transports = { RetryPreparationTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("custom-protocol");
        try (ConnectorManager manager = this.manager(loader, config)) {
            assertThrows(IllegalStateException.class, manager::init);

            // 失败不能被记录为已准备，第二次初始化必须重新进入传输配置逻辑。
            manager.init();
            assertEquals(2, RetryPreparationTransport.attempts.get());
            assertEquals("ready", config.protocol("custom").option("preparation", null));
        }
    }

    @Test
    public void preparingCustomSpiConfigurationDoesNotAllocateTransport() throws Exception {
        // 类加载器只暴露当前用例的两个 SPI，配置中保留混合大小写名称。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("CUSTOM-PROTOCOL");
        ProtocolConfig mount = config.protocol("custom");

        // 准备配置及初始化管理器不会分配网络资源，也不会复制挂载对象。
        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            assertEquals(0, CustomTransport.created.get());
            assertEquals(1, CustomTransport.prepared.get());
            assertSame(mount, config.protocol("custom"));
            assertEquals("ready", mount.option("preparation", null));
            assertEquals("Java", mount.option("serializeType", null));
        }
    }

    @Test
    public void transportPipelineIsPreparedBeforeTheNetworkConnectorIsAllocated() throws Exception {
        // 端点配置一个可观察构造与连接初始化次数的自定义传输管线。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        Map<String, String> extensions = Map.of(//
                "transportPipeline", CountingTransportPipeline.class.getName());
        ConnectorConfig config = config("custom-protocol", extensions);

        try (ConnectorManager manager = this.manager(loader, config)) {
            // 初始化只固定 provider 类型和构造器，不创建 provider、NetManager、executor 或 socket。
            manager.init();
            assertEquals(0, CountingTransportPipeline.created.get());
            assertEquals(0, CountingTransportPipeline.initialized.get());
            assertEquals(0, CustomTransport.created.get());
            assertNull(config.protocol("custom").option("transportPipeline", null));

            // 首次监听才创建网络连接器，物理连接复用启动时固定的 provider。
            manager.startInbound();
            InterAddress address = manager.getListenAddress("custom");
            try (Socket ignored = new Socket("127.0.0.1", address.getPort())) {
                assertTrue(CountingTransportPipeline.connected.await(3, TimeUnit.SECONDS));
            }
            assertEquals(1, CustomTransport.created.get());
            assertEquals(1, CountingTransportPipeline.created.get());
            assertEquals(1, CountingTransportPipeline.initialized.get());
        }
    }

    @Test
    public void invalidTransportPipelineFailsInitializationBeforeNetworkAllocation() throws Exception {
        // 配置项可解析为 Java 类型，但该类型不实现传输管线契约。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        Map<String, String> extensions = Map.of("transportPipeline", String.class.getName());
        ConnectorConfig config = config("custom-protocol", extensions);

        try (ConnectorManager manager = this.manager(loader, config)) {
            // 类型错误在端点计划编译阶段报告，不能推迟到 bind/connect 或创建网络资源。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Invalid transport pipeline"));
            assertEquals(0, CustomTransport.created.get());
            assertFalse(manager.isInitialized());
        }
    }

    @Test
    public void failedTransportPipelineConstructionUsesAFreshProviderOnRetry() throws Exception {
        // provider 第一次构造主动失败，用同一个端点计划重试传输激活。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        Map<String, String> extensions = Map.of(//
                "transportPipeline", FailingOnceTransportPipeline.class.getName());
        ConnectorConfig config = config("custom-protocol", extensions);

        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            assertEquals(0, FailingOnceTransportPipeline.attempts.get());

            // 首次激活失败不会把 provider 保存进端点，下一次激活重新执行构造。
            assertThrows(RuntimeException.class, manager::startInbound);
            assertEquals(1, FailingOnceTransportPipeline.attempts.get());
            manager.startInbound();
            assertEquals(2, FailingOnceTransportPipeline.attempts.get());
            assertNotNull(manager.getListenAddress("custom"));
        }
    }

    @Test
    public void incomingAndOutgoingConnectionsReusePreparedConnector() throws Exception {
        // 通过指定类加载器组合自定义传输和协议，保存原始挂载配置。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("CUSTOM-PROTOCOL");
        ProtocolConfig mount = config.protocol("custom");
        try (ConnectorManager manager = this.manager(loader, config); //
             ServerSocket server = new ServerSocket(0)) {
            // 主动连接首次创建传输，协议会话直接使用已准备的挂载配置。
            manager.init();
            InterAddress target = new InterAddress("custom", "127.0.0.1", server.getLocalPort(), "test");
            MetChannel channel = manager.connect(target).get(3, TimeUnit.SECONDS);
            assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                RequestPayload request = new RequestPayload();
                request.setRequestID(0);
                channel.sendData(request).get(3, TimeUnit.SECONDS);
                assertEquals(37, peer.getInputStream().read());

                // 同一个端点再开启监听时复用已经创建的传输。
                manager.startInbound();
                InterAddress boundAddress = manager.getListenAddress("custom");
                assertNotNull(boundAddress);
                assertEquals(1, CustomTransport.created.get());

                // 同一端点收到入站连接时也复用配置，不再次规范化或复制。
                try (Socket incoming = new Socket("127.0.0.1", boundAddress.getPort())) {
                    assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
                    assertEquals(1, CustomTransport.prepared.get());
                }
            }
        }
    }

    @Test
    public void closeWaitsUntilAdmittedConnectHasEnteredTheTransport() throws Exception {
        // 使用可控传输暂停 network.connect，固定连接已获准但尚未返回的窗口。
        Class<?>[] transports = { AdmissionTransportFactory.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("custom-protocol");
        ConnectorManager manager = this.manager(loader, config);
        ExecutorService lifecycle = Executors.newFixedThreadPool(2);
        AdmissionNetwork network = null;
        try {
            manager.init();
            InterAddress target = new InterAddress("custom", "127.0.0.1", 8123, "test");
            lifecycle.execute(() -> manager.connect(target));
            network = AdmissionTransportFactory.creations.poll(3, TimeUnit.SECONDS);
            assertNotNull(network);
            assertTrue(network.connecting.await(3, TimeUnit.SECONDS));

            // 关闭必须等待已准入的 connect 完成传输调用，不能提前释放同一传输。
            CountDownLatch closeStarted = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            lifecycle.execute(() -> {
                closeStarted.countDown();
                manager.close();
                closeReturned.countDown();
            });
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS));
            assertFalse(network.closed.await(200, TimeUnit.MILLISECONDS));

            // connect 返回后关闭继续执行，并最终释放传输资源。
            network.continueConnect.countDown();
            assertTrue(closeReturned.await(3, TimeUnit.SECONDS));
            assertEquals(0, network.closed.getCount());
        } finally {
            if (network != null) {
                network.continueConnect.countDown();
            }
            manager.close();
            lifecycle.shutdownNow();
        }
    }

    @Test
    public void concurrentCloseWaitsForTheFirstCloseToFinish() throws Exception {
        // 创建一个关闭阶段可暂停的传输，并通过公开连接入口触发端点的延迟初始化。
        Class<?>[] transports = { CloseBarrierTransportFactory.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("custom-protocol");
        ConnectorManager manager = this.manager(loader, config);
        ExecutorService lifecycle = Executors.newFixedThreadPool(2);
        CloseBarrierNetwork network = null;
        try {
            manager.init();
            InterAddress target = new InterAddress("custom", "127.0.0.1", 8123, "test");
            manager.connect(target);
            network = CloseBarrierTransportFactory.creations.poll(3, TimeUnit.SECONDS);
            assertNotNull(network);

            // 首个 close 到达传输释放阶段后，让第二个 close 并发进入同一完成屏障。
            CountDownLatch firstReturned = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            CountDownLatch secondReturned = new CountDownLatch(1);
            lifecycle.execute(() -> {
                manager.close();
                firstReturned.countDown();
            });
            assertTrue(network.closing.await(3, TimeUnit.SECONDS));
            lifecycle.execute(() -> {
                secondStarted.countDown();
                manager.close();
                secondReturned.countDown();
            });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            assertFalse(secondReturned.await(200, TimeUnit.MILLISECONDS));

            // 释放首个关闭后，两个调用者都只能在同一次资源清理完成后返回。
            network.continueClose.countDown();
            assertTrue(firstReturned.await(3, TimeUnit.SECONDS));
            assertTrue(secondReturned.await(3, TimeUnit.SECONDS));
        } finally {
            if (network != null) {
                network.continueClose.countDown();
            }
            manager.close();
            lifecycle.shutdownNow();
        }
    }

    @Test
    public void protocolMissingFromContextLoaderIsRejectedWithoutAllocatingTransport() throws Exception {
        // 当前类加载器未提供请求使用的协议。
        Class<?>[] transports = { CustomTransport.class };
        ClassLoader loader = this.loader(transports, new Class<?>[0]);
        ConnectorConfig config = config("custom-protocol");

        // 准备阶段报告未知协议，不创建真实传输。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Unknown protocol"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void incompatibleTransportIsRejectedBeforeCreatingNetworkResources() throws Exception {
        // HTTP 协议不能挂到 custom 字节传输上。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { HttpA.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("httpa");

        // 验证错误直接指出协议要求的传输，失败前不分配资源。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("requires transport http"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void incompatibleMessageContractIsRejectedBeforeCreatingNetworkResources() throws Exception {
        // 名称和传输匹配，但协议处理 HTTP 消息而传输提供字节数组。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { WrongMessageProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("wrong-message");

        // 消息类型不匹配在准备阶段失败。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("message type"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void transportNamesMustBeUniqueIgnoringCase() throws Exception {
        // 两个传输 SPI 仅以名称大小写区别。
        Class<?>[] transports = { CustomTransport.class, DuplicateTransport.class };
        ClassLoader loader = this.loader(transports, null);
        ConnectorConfig config = config("custom-protocol");

        // 发现阶段拒绝重复名称，不进入连接器创建。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, manager::init);
            assertTrue(failure.getMessage().contains("Duplicate transport SPI name"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void protocolNamesMustBeUniqueIgnoringCase() throws Exception {
        // 两个协议 SPI 仅以名称大小写区别。
        Class<?>[] protocols = { CustomProtocol.class, DuplicateProtocol.class };
        ClassLoader loader = this.loader(null, protocols);
        ConnectorConfig config = config("custom-protocol");

        // 发现阶段拒绝重复协议名。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, manager::init);
            assertTrue(failure.getMessage().contains("Duplicate protocol SPI name"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void blankProtocolNameIsRejectedDuringDiscovery() throws Exception {
        // SPI 提供者返回空白协议名称。
        Class<?>[] protocols = { BlankProtocol.class };
        ClassLoader loader = this.loader(null, protocols);
        ConnectorConfig config = config("custom-protocol");

        // 缺少名称时不能建立可选择的协议表。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Missing protocol SPI name"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test
    public void missingTransportIsRejectedDuringPreparation() throws Exception {
        // 协议存在，但类加载器没有暴露它依赖的传输。
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(new Class<?>[0], protocols);
        ConnectorConfig config = config("custom-protocol");

        // 错误包含缺失的传输名，失败不分配网络资源。
        try (ConnectorManager manager = this.manager(loader, config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Unknown transport: custom"));
            assertEquals(0, CustomTransport.created.get());
        }
    }

    @Test(timeout = 10000)
    public void restartedEndpointRejectsMessagesFromAnInboundConnectionAlreadyDraining() throws Exception {
        // 让旧入站连接完成 factory.create，并在端点离线后才触发有序的 connected 回调。
        LifecycleProtocol.created.set(0);
        LifecycleProtocol.received.set(0);
        Class<?>[] transports = { LifecycleTransportFactory.class };
        Class<?>[] protocols = { LifecycleProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("custom-protocol");
        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            manager.startInbound();
            LifecycleNetwork network = LifecycleTransportFactory.created;
            network.prepareAccepted();

            // 离线由传输层关闭尚未交付的旧连接；重新上线后，迟到消息仍不能创建协议会话。
            manager.stopInbound();
            assertFalse(network.channel.isOpen());
            assertEquals(0, network.channel.drains);
            network.connected();
            manager.startInbound();
            network.receive(config.protocol("custom").mountId(), new byte[] { 1 });
            assertEquals(0, LifecycleProtocol.created.get());
            assertEquals(0, LifecycleProtocol.received.get());
            assertEquals(0, network.channel.drains);
            network.finish();
        }
    }

    @Test(timeout = 10000)
    public void restartedEndpointDoesNotActivateAnInboundConnectionAlreadyDraining() throws Exception {
        // 让入站连接完成 factory.create，但暂不触发 connected，以固定接入与激活之间的窗口。
        LifecycleProtocol.created.set(0);
        LifecycleProtocol.received.set(0);
        Class<?>[] transports = { LifecycleTransportFactory.class };
        Class<?>[] protocols = { LifecycleProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("custom-protocol");
        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            manager.startInbound();
            LifecycleNetwork network = LifecycleTransportFactory.created;
            network.prepareAccepted();
            assertEquals(0, LifecycleProtocol.created.get());

            // 离线已由传输层关闭旧连接；重新上线后迟到的 connected 也不能创建协议会话。
            manager.stopInbound();
            assertFalse(network.channel.isOpen());
            assertEquals(0, network.channel.drains);
            manager.startInbound();
            network.connected();
            assertEquals(0, LifecycleProtocol.created.get());
            assertEquals(0, network.channel.drains);
            network.finish();
        }
    }

    private static ConnectorConfig config(String protocol) {
        return config(protocol, Collections.emptyMap());
    }

    private static ConnectorConfig config(String protocol, Map<String, String> extensions) {
        InterAddress localAddress = InterAddress.forBinding("custom", "127.0.0.1", 0, "test");
        ProtocolConfig mount = new ProtocolConfig("custom", "custom", protocol, Collections.emptyMap());
        Map<String, String> options = new LinkedHashMap<>();
        options.put("workerThread", "1");
        options.putAll(extensions);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);
        return new ConnectorConfig("test", localAddress, options, mounts, true);
    }

    private ConnectorManager manager(ClassLoader loader, ConnectorConfig config) {
        // 单元夹具仅暴露配置与类加载器，网络实现仍由真实 SPI 创建。
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getConnectorConfigs")) {
                return Collections.singletonList(config);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Class<?>[] settingTypes = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingTypes, settingsHandler);
        InvocationHandler contextHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getSettings")) {
                return settings;
            }
            if (StringUtils.equals(method.getName(), "getClassLoader")) {
                return loader;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Class<?>[] contextTypes = { MetContext.class };
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, contextTypes, contextHandler);

        // Manager 在 init 中使用上下文类加载器发现 SPI 并准备端点。
        return new ConnectorManager(context);
    }

    private ClassLoader loader(Class<?>[] transports, Class<?>[] protocols) throws Exception {
        // 只替换当前用例指定的 SPI 描述文件，其余资源沿用父加载器。
        Map<String, URL> descriptors = new HashMap<>();
        this.descriptor(descriptors, TransportFactory.class, transports);
        this.descriptor(descriptors, ProtocolFactory.class, protocols);
        return new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                URL descriptor = descriptors.get(name);
                if (descriptor == null) {
                    return super.getResources(name);
                }
                return Collections.enumeration(Collections.singletonList(descriptor));
            }
        };
    }

    private void descriptor(Map<String, URL> descriptors, Class<?> service, Class<?>[] providers) throws Exception {
        if (providers == null) {
            return;
        }
        Path descriptor = this.temporary.newFile().toPath();
        Files.write(descriptor, Arrays.stream(providers).map(Class::getName).toList());
        descriptors.put("META-INF/services/" + service.getName(), descriptor.toUri().toURL());
    }

    public static class CustomTransport implements TransportFactory<byte[]> {
        private static final AtomicInteger created  = new AtomicInteger();
        private static final AtomicInteger prepared = new AtomicInteger();

        public void configRoutes(Map<String, Map<String, String>> routes) {
            prepared.incrementAndGet();
            for (Map<String, String> options : routes.values()) {
                options.put("preparation", "ready");
            }
        }

        public boolean sharedRoutes() {
            return false;
        }

        public String name() {
            return "CUSTOM";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            TransportPlan<byte[]> delegate = new TcpTransportFactory().prepare(config, loader);
            return TransportPlan.direct(config, (prepared, runtime) -> {
                created.incrementAndGet();
                return delegate.create(runtime);
            });
        }
    }

    public static final class CountingTransportPipeline implements TransportPipeline {
        private static final AtomicInteger  created     = new AtomicInteger();
        private static final AtomicInteger  initialized = new AtomicInteger();
        private static       CountDownLatch connected   = new CountDownLatch(1);

        public CountingTransportPipeline() {
            created.incrementAndGet();
        }

        private static void reset() {
            created.set(0);
            initialized.set(0);
            connected = new CountDownLatch(1);
        }

        @Override
        public void initialize(TransportConfig config, ProtoBuildContext stack) {
            initialized.incrementAndGet();
            connected.countDown();
        }
    }

    public static final class FailingOnceTransportPipeline implements TransportPipeline {
        private static final AtomicInteger attempts = new AtomicInteger();

        public FailingOnceTransportPipeline() {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("First provider construction failed");
            }
        }

        @Override
        public void initialize(TransportConfig config, ProtoBuildContext stack) {
        }
    }

    public static class DuplicateTransport extends CustomTransport {
        public String name() {
            return "custom";
        }
    }

    public static final class RetryPreparationTransport extends CustomTransport {
        private static final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void configRoutes(Map<String, Map<String, String>> routes) {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Preparation failed");
            }
            super.configRoutes(routes);
        }
    }

    public static final class AdmissionTransportFactory extends CustomTransport {
        private static final BlockingQueue<AdmissionNetwork> creations = new LinkedBlockingQueue<>();

        @Override
        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            return TransportPlan.direct(config, (prepared, runtime) -> {
                AdmissionNetwork network = new AdmissionNetwork();
                creations.add(network);
                return network;
            });
        }
    }

    public static final class CloseBarrierTransportFactory extends CustomTransport {
        private static final BlockingQueue<CloseBarrierNetwork> creations = new LinkedBlockingQueue<>();

        @Override
        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            return TransportPlan.direct(config, (prepared, runtime) -> {
                CloseBarrierNetwork network = new CloseBarrierNetwork();
                creations.add(network);
                return network;
            });
        }
    }

    public static class CustomProtocol extends RawSocketTestProtocol {
        private static final BlockingQueue<ProtocolConfig> configurations = new LinkedBlockingQueue<>();

        public ProtocolSession<byte[]> createSession( //
                ProtocolConfig config, //
                ProtocolSessionContext context, //
                NetworkChannel<byte[]> connection, //
                ObjLongConsumer<Payload> messages) {
            configurations.add(config);
            return super.createSession(config, context, connection, messages);
        }

        public String name() {
            return "CUSTOM-PROTOCOL";
        }

        public String transportType() {
            return "custom";
        }
    }

    public static class DuplicateProtocol extends CustomProtocol {
        public String name() {
            return "custom-protocol";
        }
    }

    public static class BlankProtocol extends CustomProtocol {
        public String name() {
            return " ";
        }
    }

    public static class WrongMessageProtocol extends TextProtocol {
        public String name() {
            return "wrong-message";
        }

        public String transportType() {
            return "custom";
        }

    }

    public static final class LifecycleTransportFactory implements TransportFactory<byte[]> {
        private static LifecycleNetwork created;

        @Override
        public boolean sharedRoutes() {
            return false;
        }

        @Override
        public void configRoutes(Map<String, Map<String, String>> routes) {
        }

        @Override
        public String name() {
            return "custom";
        }

        @Override
        public Class<byte[]> messageType() {
            return byte[].class;
        }

        @Override
        public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
            return TransportPlan.direct(config, (prepared, runtime) -> {
                created = new LifecycleNetwork(prepared.address());
                return created;
            });
        }
    }

    public static final class LifecycleProtocol extends RawSocketTestProtocol {
        private static final AtomicInteger created  = new AtomicInteger();
        private static final AtomicInteger received = new AtomicInteger();

        @Override
        public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
                NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
            LifecycleProtocol.created.incrementAndGet();
            return new ProtocolSession<>() {
                private final BasicFuture<Void> ready = new BasicFuture<>();

                @Override
                public Future<Void> ready() {
                    return this.ready;
                }

                @Override
                public void connected() {
                    this.ready.completed(null);
                }

                @Override
                public void receive(byte[] message) {
                    LifecycleProtocol.received.incrementAndGet();
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
                    if (!this.ready.isDone()) {
                        this.ready.failed(cause);
                    }
                }
            };
        }

        @Override
        public String name() {
            return "custom-protocol";
        }

        @Override
        public String transportType() {
            return "custom";
        }
    }

    private static final class LifecycleNetwork implements NetworkConnector<byte[]> {
        private final InterAddress                   address;
        private       ChannelListenerFactory<byte[]> factory;
        private       ChannelListener<byte[]>        receiver;
        private       LifecycleListen                listen;
        private       LifecycleChannel               channel;

        private LifecycleNetwork(InterAddress address) {
            this.address = address;
        }

        @Override
        public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                ChannelListenerFactory<byte[]> factory) {
            this.factory = factory;
            this.listen = new LifecycleListen(this.address, this::closePending);
            return this.listen;
        }

        @Override
        public Future<Void> connect(InterAddress address, RouteTable<byte[]> routes, String initialMountId, //
                ChannelListenerFactory<byte[]> factory) {
            BasicFuture<Void> failed = new BasicFuture<>();
            failed.failed(new UnsupportedOperationException("Lifecycle fixture accepts inbound channels only"));
            return failed;
        }

        private void prepareAccepted() throws Exception {
            this.channel = new LifecycleChannel(this.address);
            this.receiver = this.factory.createListener(this.channel);
        }

        private void connected() {
            if (this.channel.isOpen()) {
                this.receiver.connected();
            }
        }

        @SuppressWarnings("unchecked")
        private void receive(String route, byte[] message) throws Exception {
            if (!this.channel.isOpen()) {
                return;
            }
            RouteReceiver<byte[]> routed = (RouteReceiver<byte[]>) this.receiver;
            routed.receive(route, message);
        }

        private void finish() {
            if (this.channel.isOpen()) {
                this.receiver.closed(null);
                this.channel.finish();
            }
        }

        private void closePending() {
            if (this.channel != null && this.channel.isOpen()) {
                this.receiver.closed(new IOException("Inbound listener stopped before connection activation"));
                this.channel.finish();
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class AdmissionNetwork implements NetworkConnector<byte[]> {
        private final CountDownLatch connecting      = new CountDownLatch(1);
        private final CountDownLatch continueConnect = new CountDownLatch(1);
        private final CountDownLatch closed          = new CountDownLatch(1);

        @Override
        public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                ChannelListenerFactory<byte[]> factory) {
            throw new UnsupportedOperationException("Admission fixture is outbound only");
        }

        @Override
        public Future<Void> connect(InterAddress address, RouteTable<byte[]> routes, String initialMountId, //
                ChannelListenerFactory<byte[]> factory) {
            this.connecting.countDown();
            try {
                this.continueConnect.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }

            BasicFuture<Void> result = new BasicFuture<>();
            result.failed(new IOException("Fixture does not open a real connection"));
            return result;
        }

        @Override
        public void close() {
            this.closed.countDown();
        }
    }

    private static final class CloseBarrierNetwork implements NetworkConnector<byte[]> {
        private final CountDownLatch closing       = new CountDownLatch(1);
        private final CountDownLatch continueClose = new CountDownLatch(1);

        @Override
        public NetworkListen bind(InterAddress address, RouteTable<byte[]> routes, //
                ChannelListenerFactory<byte[]> factory) {
            throw new UnsupportedOperationException("Close barrier fixture is outbound only");
        }

        @Override
        public Future<Void> connect(InterAddress address, RouteTable<byte[]> routes, String initialMountId, //
                ChannelListenerFactory<byte[]> factory) {
            BasicFuture<Void> result = new BasicFuture<>();
            result.failed(new IOException("Fixture does not open a real connection"));
            return result;
        }

        @Override
        public void close() {
            this.closing.countDown();
            try {
                this.continueClose.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class LifecycleListen implements NetworkListen {
        private final InterAddress address;
        private final Runnable     suspendAction;
        private       Runnable     closed;
        private       boolean      open = true;

        private LifecycleListen(InterAddress address, Runnable suspendAction) {
            this.address = address;
            this.suspendAction = suspendAction;
        }

        @Override
        public InterAddress getAddress() {
            return this.address;
        }

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public void onClose(Runnable action) {
            this.closed = action;
        }

        @Override
        public void suspend() {
            this.open = false;
            this.suspendAction.run();
        }

        @Override
        public void resume() {
            this.open = true;
        }

        @Override
        public void close() {
            if (this.open) {
                this.open = false;
                this.closed.run();
            }
        }
    }

    private static final class LifecycleChannel implements NetworkChannel<byte[]> {
        private final InterAddress      address;
        private final BasicFuture<Void> closed = new BasicFuture<>();
        private       int               drains;

        private LifecycleChannel(InterAddress address) {
            this.address = address;
        }

        @Override
        public InterAddress getLocal() {
            return this.address;
        }

        @Override
        public InterAddress getRemote() {
            return this.address;
        }

        @Override
        public boolean isOpen() {
            return !this.closed.isDone();
        }

        @Override
        public Future<Void> write(byte[] message) {
            return new BasicFuture<>((Void) null);
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public Future<Void> close() {
            this.finish();
            return this.closed;
        }

        @Override
        public Future<Void> drainAndClose() {
            this.drains++;
            return this.closed;
        }

        private void finish() {
            this.closed.completed(null);
        }
    }
}

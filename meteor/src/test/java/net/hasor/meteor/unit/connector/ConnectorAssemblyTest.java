/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.AbstractConnector;
import net.hasor.meteor.connector.AbstractMetListen;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.MetConnector;
import net.hasor.meteor.connector.MetConnectorFactory;
import net.hasor.meteor.connector.MetListen;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ReceivedListener;
import net.hasor.meteor.domain.MetException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorAssemblyTest {
    @Before
    public void reset() {
        CountingFactory.constructed = 0;
        CountingFactory.created = 0;
        CountingFactory.initialized = 0;
        CountingFactory.configuration = null;
        CountingFactory.target = null;
    }

    private ConnectorConfig config(String name) {
        InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
        Map<String, String> options = Collections.singletonMap("listenType", "custom");
        ProtocolConfig mount = new ProtocolConfig(name, "memory", "memory", options);
        return new ConnectorConfig(name, localAddress, options, Collections.singletonList(mount), true);
    }

    @Test(timeout = 10000)
    public void bindRejectsUninitializedManagerWithoutWaitingForAssemblyPreparation() throws Exception {
        // 阻塞工厂准备过程，让初始化保持在尚未完成的阶段。
        BlockingFactory.entered = new CountDownLatch(1);
        BlockingFactory.release = new CountDownLatch(1);
        ConnectorConfig config = this.config("outgoing");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        BasicFuture<Void> initialized = new BasicFuture<>();
        try (ConnectorManager manager = new ConnectorManager(managerContext, new BlockingFactory())) {
            try {
                workers.execute(() -> {
                    // 后台执行初始化，由工厂闩锁控制完成时机。
                    try {
                        manager.init();
                        initialized.completed(null);
                    } catch (Throwable failure) {
                        initialized.failed(failure);
                    }
                });
                assertTrue(BlockingFactory.entered.await(2, TimeUnit.SECONDS));

                // 初始化未完成时，本次绑定必须及时失败，不能等待工厂准备结束。
                BasicFuture<Throwable> rejected = new BasicFuture<>();
                workers.execute(() -> {
                    try {
                        assertFalse(manager.isInitialized());
                        Future<MetListen> operation = manager.bind(config.name());
                        rejected.completed(operation.getCause());
                    } catch (Throwable failure) {
                        rejected.failed(failure);
                    }
                });
                Throwable rejectedCause = rejected.get(2, TimeUnit.SECONDS);
                assertTrue(rejectedCause instanceof IllegalStateException);
                assertTrue(manager.protocols().isEmpty());
                assertFalse(initialized.isDone());

                // 放行初始化后恢复可用状态，失败操作没有提前创建连接器。
                BlockingFactory.release.countDown();
                initialized.get(2, TimeUnit.SECONDS);
                assertTrue(manager.isInitialized());
                assertEquals(0, CountingFactory.created);
                Future<MetChannel> connected = manager.connect(config.address());
                assertTrue(connected.getCause() instanceof UnsupportedOperationException);
                assertEquals(1, CountingFactory.initialized);
            } finally {
                BlockingFactory.release.countDown();
                workers.shutdown();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 10000)
    public void connectRejectsUninitializedManagerWithoutWaitingForAssemblyPreparation() throws Exception {
        // 阻塞工厂准备过程，让初始化保持在尚未完成的阶段。
        BlockingFactory.entered = new CountDownLatch(1);
        BlockingFactory.release = new CountDownLatch(1);
        ConnectorConfig config = this.config("outgoing");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        BasicFuture<Void> initialized = new BasicFuture<>();
        try (ConnectorManager manager = new ConnectorManager(managerContext, new BlockingFactory())) {
            try {
                workers.execute(() -> {
                    // 后台执行初始化，由工厂闩锁控制完成时机。
                    try {
                        manager.init();
                        initialized.completed(null);
                    } catch (Throwable failure) {
                        initialized.failed(failure);
                    }
                });
                assertTrue(BlockingFactory.entered.await(2, TimeUnit.SECONDS));

                // 初始化未完成时，本次连接必须及时失败，不能等待工厂准备结束。
                BasicFuture<Throwable> rejected = new BasicFuture<>();
                workers.execute(() -> {
                    try {
                        assertFalse(manager.isInitialized());
                        Future<MetChannel> operation = manager.connect(config.address());
                        rejected.completed(operation.getCause());
                    } catch (Throwable failure) {
                        rejected.failed(failure);
                    }
                });
                Throwable rejectedCause = rejected.get(2, TimeUnit.SECONDS);
                assertTrue(rejectedCause instanceof IllegalStateException);
                assertTrue(manager.protocols().isEmpty());
                assertFalse(initialized.isDone());

                // 放行初始化后恢复可用状态，失败操作没有提前创建连接器。
                BlockingFactory.release.countDown();
                initialized.get(2, TimeUnit.SECONDS);
                assertTrue(manager.isInitialized());
                assertEquals(0, CountingFactory.created);
                Future<MetChannel> connected = manager.connect(config.address());
                assertTrue(connected.getCause() instanceof UnsupportedOperationException);
                assertEquals(1, CountingFactory.initialized);
            } finally {
                BlockingFactory.release.countDown();
                workers.shutdown();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void configurationRejectsNullTransportBeforeConnectorCreation() {
        // 使用缺失的传输类型准备配置。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", null);
        InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
        ProtocolConfig mount = new ProtocolConfig("invalid", "memory", "memory", options);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);

        // 配置构建阶段即拒绝无效类型，不得创建连接器。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("invalid", localAddress, options, mounts, true);
        });
        assertTrue(failure.getMessage().contains("listenType"));
        assertEquals(0, CountingFactory.created);
    }

    @Test
    public void configurationRejectsEmptyTransportBeforeConnectorCreation() {
        // 使用空字符串的传输类型准备配置。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "");
        InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
        ProtocolConfig mount = new ProtocolConfig("invalid", "memory", "memory", options);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);

        // 配置构建阶段即拒绝无效类型，不得创建连接器。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("invalid", localAddress, options, mounts, true);
        });
        assertTrue(failure.getMessage().contains("listenType"));
        assertEquals(0, CountingFactory.created);
    }

    @Test
    public void configurationRejectsBlankTransportBeforeConnectorCreation() {
        // 使用纯空白的传输类型准备配置。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "  ");
        InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
        ProtocolConfig mount = new ProtocolConfig("invalid", "memory", "memory", options);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);

        // 配置构建阶段即拒绝无效类型，不得创建连接器。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("invalid", localAddress, options, mounts, true);
        });
        assertTrue(failure.getMessage().contains("listenType"));
        assertEquals(0, CountingFactory.created);
    }

    @Test
    public void addressOnlyConnectSelectsSettingsAndPreservesLocalConfiguration() throws Exception {
        // 本地配置保留传输、连接超时、线程数和 TLS 选项。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "CUSTOM");
        options.put("connectTimeout", "1234");
        options.put("workerThread", "3");
        options.put("tls.enabled", "true");
        InterAddress localAddress = new InterAddress("memory://localhost:1/default");
        ProtocolConfig mount = new ProtocolConfig("outgoing", "memory", "memory", options);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);
        ConnectorConfig config = new ConnectorConfig("outgoing", localAddress, options, mounts, true);
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());

            // 仅凭远端地址选择配置，大小写不同的 scheme 仍命中同一端点。
            InterAddress target = new InterAddress("MEMORY://localhost:2/default");
            Future<MetChannel> connected = manager.connect(target);
            assertTrue(connected.getCause() instanceof UnsupportedOperationException);
            assertSame(target, CountingFactory.target);
            ConnectorConfig selected = CountingFactory.configuration;
            assertEquals("outgoing", selected.name());
            assertEquals(config.address(), selected.address());
            assertEquals(1234, selected.connectTimeout());
            assertEquals(3, selected.integer("workerThread", 1));
            assertEquals("true", selected.option("tls.enabled", null));
            MetConnector connector = manager.find("outgoing");
            assertTrue(connector.getListenList().isEmpty());
        }
    }

    @Test
    public void laterConnectReusesSelectedConfigurationAndInitializedConnector() throws Exception {
        // 同一协议的两个远端地址共用一份本地端点配置。
        ConnectorConfig config = this.config("outgoing");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            InterAddress first = new InterAddress("memory://localhost:2/default");
            Future<MetChannel> firstConnection = manager.connect(first);
            assertTrue(firstConnection.getCause() instanceof UnsupportedOperationException);
            assertSame(first, CountingFactory.target);
            ConnectorConfig selected = CountingFactory.configuration;

            // 前次连接失败后再次连接另一地址，配置和连接器仍被复用。
            InterAddress another = new InterAddress("memory://localhost:3/default");
            Future<MetChannel> anotherConnection = manager.connect(another);
            assertTrue(anotherConnection.getCause() instanceof UnsupportedOperationException);
            assertSame(another, CountingFactory.target);
            assertSame(selected, CountingFactory.configuration);
            assertEquals(1, CountingFactory.created);
            assertEquals(1, CountingFactory.initialized);
        }
    }

    @Test
    public void configuredEndpointCollectionIsReadOnly() {
        // 初始化后通过公开查询取得端点配置集合。
        ConnectorConfig config = this.config("outgoing");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            Collection<ConnectorConfig> configurations = manager.configurations();

            // 外部删除必须失败，管理器仍保留完整配置。
            assertThrows(UnsupportedOperationException.class, configurations::clear);
            assertEquals(1, manager.configurations().size());
        }
    }

    @Test
    public void duplicateSchemesFailInitWithoutCreatingConnectors() throws Exception {
        // 两个端点声明仅大小写不同的 scheme，构成路由冲突。
        ConnectorConfig first = this.config("first");
        Map<String, String> options = Collections.singletonMap("listenType", "custom");
        ProtocolConfig protocol = new ProtocolConfig("second", "MEMORY", "MEMORY", options);
        InterAddress address = new InterAddress("MEMORY://localhost:2/default");
        List<ProtocolConfig> mounts = Collections.singletonList(protocol);
        ConnectorConfig second = new ConnectorConfig("second", address, options, mounts, true);
        MetContext managerContext = ConnectorTestSupport.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            // 初始化报告路由歧义，且没有创建任何端点。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("multiple endpoints"));
            assertFalse(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void missingConfiguredTransportFactoryFailsBeforeConnectorCreation() {
        // 配置指定工厂不支持的传输类型。
        Map<String, String> options = Collections.singletonMap("listenType", "unknown");
        ProtocolConfig protocol = new ProtocolConfig("missing", "memory", "memory", options);
        InterAddress address = this.config("missing").address();
        List<ProtocolConfig> mounts = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("missing", address, options, mounts, true);
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            // 不支持的传输在初始化阶段失败，不打开任何连接器。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("unknown"));
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void repeatedInitializationKeepsSuppliedFactoryWithoutCreatingEndpoints() {
        // 工厂声明混合大小写的传输类型，配置按相同类型匹配。
        ConnectorConfig config = this.config("first");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            // 重复初始化只准备管理器，不提前创建端点。
            manager.init();
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void configuredEndpointsAreCreatedOnlyWhenBound() throws Exception {
        // 两个不同协议的端点由同一个显式工厂装配。
        Map<String, String> firstOptions = Collections.singletonMap("listenType", "CUSTOM");
        ProtocolConfig firstProtocol = new ProtocolConfig("first", "memory", "memory", firstOptions);
        InterAddress firstAddress = this.config("first").address();
        List<ProtocolConfig> firstMounts = Collections.singletonList(firstProtocol);
        ConnectorConfig first = new ConnectorConfig("first", firstAddress, firstOptions, firstMounts, true);
        Map<String, String> secondOptions = Collections.singletonMap("listenType", "custom");
        ProtocolConfig secondProtocol = new ProtocolConfig("second", "other", "other", secondOptions);
        InterAddress secondAddress = new InterAddress("other", "localhost", 2, "default");
        List<ProtocolConfig> secondMounts = Collections.singletonList(secondProtocol);
        ConnectorConfig second = new ConnectorConfig("second", secondAddress, secondOptions, secondMounts, true);
        MetContext managerContext = ConnectorTestSupport.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            assertEquals(0, CountingFactory.created);

            // 首次绑定只创建被选中的端点，另一端点仍未实例化。
            manager.bind(first.name()).get(5, TimeUnit.SECONDS);
            MetConnector firstConnector = manager.find("first");
            assertSame(first, firstConnector.config());
            assertEquals(1, CountingFactory.created);
            assertNull(manager.find("second"));

            // 绑定第二个端点才完成它自己的创建和初始化。
            manager.bind(second.name()).get(5, TimeUnit.SECONDS);
            assertEquals(2, CountingFactory.created);
            assertEquals(2, CountingFactory.initialized);
        }
    }

    @Test
    public void repeatedBindReusesTheConfiguredListener() throws Exception {
        // 首次绑定创建一个端点及其监听器。
        ConnectorConfig config = this.config("first");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            MetListen first = manager.bind(config.name()).get(5, TimeUnit.SECONDS);

            // 再次绑定同一端点返回原监听器。
            MetListen repeated = manager.bind(config.name()).get(5, TimeUnit.SECONDS);
            assertSame(first, repeated);
            assertEquals(1, CountingFactory.created);
        }
    }

    @Test
    public void closingManagerStopsListenersAndClearsProtocols() throws Exception {
        // 准备一个已绑定的端点。
        ConnectorConfig config = this.config("first");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            MetListen listen = manager.bind(config.name()).get(5, TimeUnit.SECONDS);

            // 显式关闭释放监听器，并清除已装配的协议集合。
            manager.close();
            assertFalse(listen.isActive());
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void restartingManagerCreatesAFreshConnectorWithTheSuppliedFactory() throws Exception {
        // 第一次绑定后关闭管理器，形成需要重建连接器的状态。
        ConnectorConfig config = this.config("first");
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            MetListen first = manager.bind(config.name()).get(5, TimeUnit.SECONDS);
            manager.close();

            // 重启继续使用原工厂，端点在重新绑定时才重建。
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(1, CountingFactory.created);
            MetListen restarted = manager.bind(config.name()).get(5, TimeUnit.SECONDS);
            assertNotSame(first, restarted);
            assertEquals(2, CountingFactory.created);
        }
    }

    @Test
    public void unknownEndpointDoesNotFallBackToAnUnrelatedFactory() {
        // 初始化一个没有任何端点配置的管理器。
        MetContext managerContext = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();

            // 未知端点绑定必须失败，不能由现有工厂代替装配。
            Future<MetListen> bound = manager.bind("missing");
            Throwable failure = bound.getCause();
            assertTrue(failure instanceof IllegalArgumentException);
            assertTrue(failure.getMessage().contains("Unknown endpoint"));
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void unknownSchemeDoesNotOpenAConnection() {
        // 没有配置 memory 协议的管理器不能选择目标端点。
        MetContext managerContext = ConnectorTestSupport.sharedContext();
        InterAddress target = this.config("missing").address();
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();

            // 目标 scheme 不存在时向调用方报告协议错误。
            Future<MetChannel> connected = manager.connect(target);
            assertTrue(connected.getCause() instanceof MetException);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void suppliedAssemblerReceivesContextLoaderWithoutManagerSpiDiscovery() {
        // 类加载器拒绝 SPI 发现，显式装配器必须直接接收该加载器。
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                throw new AssertionError("Manager must not discover an assembler via SPI: " + name);
            }
        };
        CountingFactory factory = new CountingFactory() {
            @Override
            public Collection<String> listenTypes(ClassLoader actual) {
                assertSame(loader, actual);
                return super.listenTypes(actual);
            }
        };
        MetContext managerContext = ConnectorTestSupport.sharedContext(loader);

        // 初始化成功证明使用了显式工厂，且仍未创建端点。
        try (ConnectorManager manager = new ConnectorManager(managerContext, factory)) {
            manager.init();
            assertTrue(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void blankFactoryNameFailsInitialization() {
        // 工厂声明一个空白传输类型。
        MetContext managerContext = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(managerContext, new BlankFactory())) {
            // 初始化失败后管理器保持未就绪状态。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("listenType"));
            assertFalse(manager.isInitialized());
        }
    }

    @Test
    public void duplicateEndpointNamesFailBeforeCreatingAnyConnector() {
        // 两份不同协议配置复用同一个端点名称。
        ConnectorConfig first = this.config("duplicate");
        ProtocolConfig mount = new ProtocolConfig("other", "other", "other", Collections.emptyMap());
        InterAddress secondAddress = first.address();
        List<ProtocolConfig> secondMounts = Collections.singletonList(mount);
        Map<String, String> secondOptions = first.options();
        ConnectorConfig second = new ConnectorConfig(first.name(), secondAddress, secondOptions, secondMounts, true);
        MetContext managerContext = ConnectorTestSupport.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            // 重复名称在初始化阶段拒绝，尚未创建任何连接器。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Duplicate endpoint"));
            assertFalse(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void initializationFailurePreservesCleanupCauseAndAllowsFreshConnector() throws Exception {
        // 首次端点初始化与清理都失败，第二次创建交回正常工厂。
        ConnectorConfig config = this.config("retry");
        IllegalStateException initialization = new IllegalStateException("initialization failed");
        IllegalArgumentException cleanup = new IllegalArgumentException("cleanup failed");
        List<String> events = new ArrayList<>();
        CountingFactory factory = new CountingFactory() {
            private int attempts;

            @Override
            public MetConnector create(ConnectorConfig value, ConnectorManager manager) {
                // 仅第一次创建注入故障，后续创建验证失败恢复。
                if (this.attempts++ != 0) {
                    return super.create(value, manager);
                }
                return new EndpointFixture.Endpoint(value, manager, events) {
                    @Override
                    protected void initialize() {
                        throw initialization;
                    }

                    @Override
                    protected void doClose() {
                        this.events.add("cleanup");
                        throw cleanup;
                    }
                };
            }
        };
        MetContext managerContext = ConnectorTestSupport.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, factory)) {
            manager.init();

            // 调用方看到初始化原因，并保留清理失败的附加异常。
            Future<MetListen> failedBind = manager.bind(config.name());
            assertSame(initialization, failedBind.getCause());
            assertArrayEquals(new Throwable[] { cleanup }, initialization.getSuppressed());
            assertEquals(Collections.singletonList("cleanup"), events);
            assertNull(manager.find(config.name()));

            // 故障实例未被缓存，后续绑定可以创建全新端点。
            MetListen recovered = manager.bind(config.name()).get(5, TimeUnit.SECONDS);
            assertTrue(recovered.isActive());
            assertEquals(1, CountingFactory.created);
        }
    }

    public static class CountingFactory implements MetConnectorFactory {
        private static int             constructed;
        private static int             created;
        private static int             initialized;
        private static ConnectorConfig configuration;
        private static InterAddress    target;

        public CountingFactory() {
            CountingFactory.constructed++;
        }

        public Collection<String> listenTypes(ClassLoader loader) {
            return Collections.singletonList("CuStOm");
        }

        public MetConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            // 记录装配次数和选中的配置，供当前场景检查惰性创建行为。
            CountingFactory.created++;
            CountingFactory.configuration = connectorConfig;

            // 提供只观察生命周期的内存端点，不打开真实网络连接。
            return new AbstractConnector(connectorConfig, connectorManager) {
                protected void initialize() {
                    CountingFactory.initialized++;
                }

                protected Future<MetListen> listen(InterAddress address, ReceivedListener listener) {
                    String type = this.config.listenType();
                    MetListen listen = new AbstractMetListen(type, address, listener) {
                        private boolean active = true;

                        public boolean isActive() {
                            return this.active;
                        }

                        public void close() {
                            this.active = false;
                        }
                    };
                    return new BasicFuture<>(listen);
                }

                protected Future<MetChannel> openSession(InterAddress target, ReceivedListener listener) {
                    CountingFactory.target = target;
                    throw new UnsupportedOperationException();
                }

                protected void doClose() {
                }
            };
        }
    }

    public static final class BlockingFactory extends CountingFactory {
        private static CountDownLatch entered;
        private static CountDownLatch release;

        @Override
        public Collection<String> listenTypes(ClassLoader loader) {
            // 通知测试已进入工厂准备阶段，再等待显式放行。
            BlockingFactory.entered.countDown();
            try {
                if (!BlockingFactory.release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Assembly preparation was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }

            // 放行后返回正常传输类型，继续原有初始化流程。
            return super.listenTypes(loader);
        }
    }

    public static final class BlankFactory extends CountingFactory {
        public Collection<String> listenTypes(ClassLoader loader) {
            return Collections.singletonList(" ");
        }
    }
}

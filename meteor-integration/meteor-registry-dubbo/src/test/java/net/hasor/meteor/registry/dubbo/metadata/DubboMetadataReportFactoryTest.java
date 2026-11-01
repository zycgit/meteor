/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.curator.test.TestingServer;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 元数据工厂的接管范围和 SDK 缓存契约，不执行服务映射监听场景。 */
public class DubboMetadataReportFactoryTest {
    @Test
    public void foreignApplicationPreservesItsDelegate() {
        // 普通 Dubbo 应用没有 Meteor 私有标记，URL 不应被包装工厂改写。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, Map.of());
            AtomicReference<URL> received = new AtomicReference<>();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                received.set(address);
                return null;
            });
            factory.setApplicationModel(application);

            // 使用不需要建立连接的委托，直接观察同一个 URL 是否透传。
            URL address = metadataUrl(application, "zookeeper", "registry.example", 2181);
            assertNull(factory.getMetadataReport(address));
            assertSame(address, received.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void applicationWithoutConfigurationPreservesItsDelegate() {
        // 尚未配置应用对象的 SDK 作用域不视为 Meteor 私有会话。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = framework.newApplication();
            AtomicInteger delegated = new AtomicInteger();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                delegated.incrementAndGet();
                return null;
            });
            factory.setApplicationModel(application);
            URL address = metadataUrl(application, "zookeeper", "registry.example", 2181);

            // 缺少应用配置时不访问网络，仍交由原工厂决定行为。
            assertNull(factory.getMetadataReport(address));
            assertEquals(1, delegated.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void applicationWithoutParametersPreservesItsDelegate() {
        // 已有 ApplicationConfig 但参数 Map 为空时同样不接管。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, null);
            AtomicInteger delegated = new AtomicInteger();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                delegated.incrementAndGet();
                return null;
            });
            factory.setApplicationModel(application);
            URL address = metadataUrl(application, "zookeeper", "registry.example", 2181);
            assertNull(factory.getMetadataReport(address));
            assertEquals(1, delegated.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void falseApplicationMarkerPreservesItsDelegate() {
        // 出现同名参数但值不是 true，不能误判为当前扩展的独占会话。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = Map.of(DubboMetadataCertProvider.APPLICATION_MARKER, "false");
            ApplicationModel application = application(framework, parameters);
            AtomicInteger delegated = new AtomicInteger();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                delegated.incrementAndGet();
                return null;
            });
            factory.setApplicationModel(application);
            URL address = metadataUrl(application, "zookeeper", "registry.example", 2181);
            assertNull(factory.getMetadataReport(address));
            assertEquals(1, delegated.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void meteorApplicationUsingAnotherBackendPreservesItsDelegate() {
        // Meteor 标记仅与 ZooKeeper 共同触发接管，不改变其他元数据后端。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, ownParameters());
            AtomicReference<URL> received = new AtomicReference<>();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                received.set(address);
                return null;
            });
            factory.setApplicationModel(application);
            URL address = metadataUrl(application, "nacos", "registry.example", 8848);
            assertNull(factory.getMetadataReport(address));
            assertSame(address, received.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void foreignFactoryLeavesCacheOwnershipToTheDelegate() {
        // 包装工厂不能给其他应用擅自增加缓存，重复请求都应进入原工厂。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, Map.of());
            AtomicInteger delegated = new AtomicInteger();
            DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                delegated.incrementAndGet();
                return null;
            });
            factory.setApplicationModel(application);
            URL address = metadataUrl(application, "zookeeper", "registry.example", 2181);

            // 原工厂自行决定是否缓存，wrapper 不吞掉任何一次请求。
            factory.getMetadataReport(address);
            factory.getMetadataReport(address);
            assertEquals(2, delegated.get());
            factory.destroy();
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void meteorZookeeperFactoryOwnsItsScopedReport() throws Exception {
        // 本机嵌入式 ZooKeeper 只验证工厂创建结果，不创建服务映射 watcher。
        try (TestingServer server = new TestingServer()) {
            FrameworkModel framework = new FrameworkModel();
            try {
                ApplicationModel application = application(framework, ownParameters());
                AtomicInteger delegated = new AtomicInteger();
                DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> {
                    delegated.incrementAndGet();
                    return null;
                });
                factory.setApplicationModel(application);
                URL address = metadataUrl(application, "zookeeper", "127.0.0.1", server.getPort());
                try {
                    // 仅私有标记与 ZooKeeper 同时成立时使用会话隔离实现。
                    MetadataReport report = factory.getMetadataReport(address);
                    assertTrue(report instanceof DubboZookeeperMetadataReport);
                    assertEquals(0, delegated.get());
                } finally {
                    factory.destroy();
                }
            } finally {
                framework.destroy();
            }
        }
    }

    @Test
    public void sdkSpiWrapsTheStandardFactoryAndCachesItsOwnReport() throws Exception {
        // 从 SDK 正式 SPI 获得 zookeeper 工厂，验证生产资源描述中的 wrapper 真正生效。
        try (TestingServer server = new TestingServer()) {
            FrameworkModel framework = new FrameworkModel();
            try {
                ApplicationModel application = application(framework, ownParameters());
                MetadataReportFactory factory = application.getExtensionLoader(MetadataReportFactory.class) //
                        .getExtension("zookeeper");
                URL address = metadataUrl(application, "zookeeper", "127.0.0.1", server.getPort());
                try {
                    // 同一作用域的相同地址复用单个隔离 report，不重复创建连接与缓存。
                    MetadataReport first = factory.getMetadataReport(address);
                    MetadataReport repeated = factory.getMetadataReport(address);
                    assertTrue(factory instanceof DubboMetadataReportFactory);
                    assertTrue(first instanceof DubboZookeeperMetadataReport);
                    assertSame(first, repeated);
                } finally {
                    factory.destroy();
                }
            } finally {
                framework.destroy();
            }
        }
    }

    @Test
    public void distinctNamespacesDoNotShareTheSameReport() throws Exception {
        // 同一工厂保留 SDK 的 namespace 缓存维度，避免返回另一命名空间的连接对象。
        try (TestingServer server = new TestingServer()) {
            FrameworkModel framework = new FrameworkModel();
            try {
                ApplicationModel application = application(framework, ownParameters());
                DubboMetadataReportFactory factory = new DubboMetadataReportFactory(address -> null);
                factory.setApplicationModel(application);
                URL address = metadataUrl(application, "zookeeper", "127.0.0.1", server.getPort());
                try {
                    // 两个不同 namespace 的请求创建独立 report，仍不进行 watcher 场景验证。
                    MetadataReport first = factory.getMetadataReport(address.addParameter("namespace", "first"));
                    MetadataReport second = factory.getMetadataReport(address.addParameter("namespace", "second"));
                    assertNotSame(first, second);
                } finally {
                    factory.destroy();
                }
            } finally {
                framework.destroy();
            }
        }
    }

    @Test
    public void destroyingTheWrapperDestroysItsDelegate() {
        // 原工厂生命周期仍由 wrapper 编排，不能在释放自身缓存后遗漏 delegate。
        AtomicBoolean destroyed = new AtomicBoolean();
        MetadataReportFactory delegate = new MetadataReportFactory() {
            @Override
            public MetadataReport getMetadataReport(URL address) {
                return null;
            }

            @Override
            public void destroy() {
                destroyed.set(true);
            }
        };
        DubboMetadataReportFactory factory = new DubboMetadataReportFactory(delegate);
        factory.destroy();
        assertTrue(destroyed.get());
    }

    private static ApplicationModel application(FrameworkModel framework, Map<String, String> parameters) {
        ApplicationModel application = framework.newApplication();
        ApplicationConfig configuration = new ApplicationConfig(application);
        configuration.setName("metadata-report-test");
        configuration.setQosEnable(false);
        configuration.setParameters(parameters);
        application.getApplicationConfigManager().setApplication(configuration);
        return application;
    }

    private static Map<String, String> ownParameters() {
        return Map.of(DubboMetadataCertProvider.APPLICATION_MARKER, "true");
    }

    private static URL metadataUrl(ApplicationModel application, String backend, String host, int port) {
        return new URL(backend, host, port).setScopeModel(application) //
                .addParameter("file-cache", false).addParameter("timeout", 5000);
    }
}

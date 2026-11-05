/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.component.provider;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.component.provider.jdbc.JdbcProviderFactory;
import net.hasor.meteor.console.component.provider.nacos.NacosProviderFactory;
import net.hasor.meteor.console.component.provider.remote.RemoteProviderFactory;
import net.hasor.meteor.console.component.provider.zk.ZookeeperProviderFactory;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证容器发现工厂、按类型选取以及新连接的所有权。 */
public class ProviderFactoriesTest {
    @TempDir
    public Path temporary;

    @Test
    public void consoleBootDiscoversBuiltInFactoriesAndOpensJdbc() throws Exception {
        // 使用真实 H2 表结构；保留建表连接，直到管理连接关闭后再释放内存数据库。
        String address = "jdbc:h2:mem:provider-factories-" + UUID.randomUUID();
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(address);
        source.setUser("sa");
        source.setPassword("");
        var schema = this.getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql");
        try (var database = source.getConnection(); //
             var script = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
            RunScript.execute(database, script);

            // 只指定正式启动源，不手工绑定任何内置工厂。
            Boot boot = new Boot()//
                    .sources(ConsoleMain.class)//
                    .property("hasor.boot.web.connectors.http.enabled", false)//
                    .property("data.directory", this.temporary.resolve("console-data").toString());
            try (BootApplication application = boot.start()) {
                AppContext app = application.getAppContext();
                assertNotNull(app.getBindInfo(JdbcProviderFactory.class));
                assertNotNull(app.getBindInfo(NacosProviderFactory.class));
                assertNotNull(app.getBindInfo(ZookeeperProviderFactory.class));
                assertNotNull(app.getBindInfo(RemoteProviderFactory.class));

                // 聚合入口必须能按连接类型找到自动发现的 JDBC 工厂，并操作真实存储。
                ClusterProfile profile = new ClusterProfile();
                profile.setName("自动发现 JDBC");
                profile.setType("jdbc");
                profile.setDatabase("h2");
                profile.setAddress(address);
                profile.setUsername("sa");
                profile.setPassword("");
                ProviderFactories factories = app.getInstance(ProviderFactories.class);
                try (ProviderConnection connection = factories.open(profile, () -> "provider-token")) {
                    var cluster = connection.registry().getClusterAdmin();
                    assertEquals("jdbc", cluster.info().getProvider());
                    assertTrue(cluster.status().isReachable());
                    cluster.putMeta("source", "boot-discovery");
                    assertEquals(Map.of("source", "boot-discovery"), cluster.fetchMeta());
                }
            }
        }
    }

    @Test
    public void interfaceAndConcreteBindingsTransferIndependentConnectionsToCaller() throws Exception {
        // 两种绑定方式都应被发现；工厂只装饰真实内存管理端以观察打开和关闭。
        TrackingFactory interfaceFactory = new TrackingFactory("interface");
        TrackingFactory concreteFactory = new TrackingFactory("concrete");
        RegistryTokenProvider credentials = () -> "discovery-token";
        Hasor hasor = this.container().addModules(binder -> {
            binder.bindType(ProviderFactory.class).toInstance(interfaceFactory);
            binder.bindType(TrackingFactory.class).toInstance(concreteFactory);
        });
        AppContext application = hasor.build();
        try {
            ProviderFactories factories = application.getInstance(ProviderFactories.class);
            assertEquals(0, interfaceFactory.opened.get());
            assertEquals(0, concreteFactory.opened.get());

            // 首次打开才创建资源，凭据原样传给选中的工厂，各连接的数据独立。
            try (ProviderConnection first = factories.open(profile("interface"), credentials); //
                 ProviderConnection second = factories.open(profile("concrete"), credentials)) {
                assertEquals(1, interfaceFactory.opened.get());
                assertEquals(1, concreteFactory.opened.get());
                assertSame(credentials, interfaceFactory.credentials);
                assertSame(credentials, concreteFactory.credentials);
                first.registry().getClusterAdmin().putMeta("owner", "first");
                second.registry().getClusterAdmin().putMeta("owner", "second");

                // 容器只持有工厂，不拥有已转给调用方的连接。
                application.close();
                assertEquals(0, interfaceFactory.closed.get());
                assertEquals(0, concreteFactory.closed.get());
                assertEquals(Map.of("owner", "first"), first.registry().getClusterAdmin().fetchMeta());
                assertEquals(Map.of("owner", "second"), second.registry().getClusterAdmin().fetchMeta());
            }
            assertEquals(1, interfaceFactory.closed.get());
            assertEquals(1, concreteFactory.closed.get());
        } finally {
            if (application.isStart()) {
                application.close();
            }
        }
    }

    @Test
    public void blankFactoryNameRejectsContainerInitialization() {
        // 工厂名称用于按类型选取，空白名称不能成为一个可运行的注册项。
        TrackingFactory factory = new TrackingFactory("   ");
        Hasor hasor = this.container().addModules(binder -> {
            binder.bindType(ProviderFactory.class).toInstance(factory);
        });

        Exception failure = assertThrows(Exception.class, hasor::build);
        assertInstanceOf(IllegalArgumentException.class, ExceptionUtils.getRootCause(failure));
        assertEquals(0, factory.opened.get());
    }

    @Test
    public void duplicateFactoryNameRejectsContainerInitialization() {
        // 不允许两个工厂争用同一类型，避免绑定顺序决定实际后端。
        TrackingFactory first = new TrackingFactory("duplicate");
        TrackingFactory second = new TrackingFactory("duplicate");
        Hasor hasor = this.container().addModules(binder -> {
            binder.bindType(ProviderFactory.class).nameWith("first").toInstance(first);
            binder.bindType(ProviderFactory.class).nameWith("second").toInstance(second);
        });

        Exception failure = assertThrows(Exception.class, hasor::build);
        assertInstanceOf(IllegalArgumentException.class, ExceptionUtils.getRootCause(failure));
        assertEquals(0, first.opened.get());
        assertEquals(0, second.opened.get());
    }

    private Hasor container() {
        Hasor hasor = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            binder.bindType(ProviderFactories.class).asEagerSingleton();
        });
        hasor.addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false);
        return hasor;
    }

    private static ClusterProfile profile(String type) {
        ClusterProfile profile = new ClusterProfile();
        profile.setType(type);
        return profile;
    }

    public static final class TrackingFactory implements ProviderFactory {
        private final String                name;
        private final AtomicInteger         opened = new AtomicInteger();
        private final AtomicInteger         closed = new AtomicInteger();
        private       RegistryTokenProvider credentials;

        public TrackingFactory(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
            this.opened.incrementAndGet();
            this.credentials = credentials;
            MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
            storage.setTokenProvider(credentials);
            RegistryAdmin registry = storage.buildAdmin();
            return new ProviderConnection() {
                @Override
                public RegistryAdmin registry() {
                    return registry;
                }

                @Override
                public void close() throws Exception {
                    TrackingFactory.this.closed.incrementAndGet();
                    registry.close();
                }
            };
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.core.Module;
import net.hasor.example.OrderClient;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.OrderService;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HasorIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void classifiesAfterAllModulesAndInjectsConsumer() throws Throwable {
        // Meteor 模块先加载，提供者定义在后续模块，仍应识别为提供者。
        try (AppContext provider = application("provider", null, binder -> {
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
        }).build()) {
            MetContext server = provider.getInstance(MetContext.class);
            assertNotNull(provider.getInstance(InventoryService.class));
            assertEquals(MetServiceType.Provider, server.getServiceInfo(OrderService.class).getServiceType());
            assertEquals(MetServiceType.Consumer, server.getServiceInfo(InventoryService.class).getServiceType());
            String address = server.getListenAddress("rsf").toString();

            // 普通 Bean 的字段得到 MetClient 创建的接口代理。
            try (AppContext consumer = application("consumer", address, binder -> {
                binder.bindType(OrderClient.class).asEagerSingleton();
            }).build()) {
                OrderClient caller = consumer.getInstance(OrderClient.class);
                assertNotNull(consumer.getInstance(InventoryService.class));
                MetContext client = consumer.getInstance(MetContext.class);
                String serviceID = client.getServiceInfo(OrderService.class).getBindID();
                assertEquals(Set.of("rsf"), client.getUpdater().getSupportedProtocols(serviceID));
                assertEquals("ORDER-1001", caller.getOrder("ORDER-1001").getOrderId());
            }
        }
    }

    @Test
    void discoversProviderThroughRegistryBean() throws Throwable {
        // 注册配置 Bean 绑定具体子类型，适配器按可赋值类型收集扩展。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:hasor_registry;DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); // 初始化本场景独享的注册存储。
             InputStream stream = getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql"); // 使用产品建表脚本。
             Reader script = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
        }
        try (AppContext provider = application("provider", null, binder -> {
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
            binder.bindType(JdbcRegistryConfiguration.class).toInstance(registry(source));
        }).build(); // 提供者先完成服务登记，再启动消费者。
             AppContext consumer = application("consumer", null, binder -> {
                 binder.bindType(JdbcRegistryConfiguration.class).toInstance(registry(source));
             }).build()) {
            assertEquals("ORDER-1001", consumer.getInstance(OrderService.class).getOrder("ORDER-1001").getOrderId());
        }
    }

    @Test
    void startsAutomaticRegistryWithNativeDefaultScanPackages() throws Throwable {
        // 不额外设置扫描范围，扩展自身发布的管理接口不能被扫描器重复注册。
        Properties settings = new Properties();
        settings.setProperty("meteor.connectors.rsf.host", "127.0.0.1");
        settings.setProperty("meteor.connectors.rsf.port", "0");
        settings.setProperty("meteor.registry.online", "false");
        settings.setProperty("meteor.registry.management-token", "example-management-token");
        try (AppContext app = Hasor.create().registerShutdownHook(false).loadSettings(settings) //
                .addModules(new MeteorModule()).build()) {
            MetContext context = app.getInstance(MetContext.class);
            TargetAdmin admin = context.getExtension(TargetAdmin.class);
            var instance = admin.getInstanceAdminByAddress(context.getListenAddress("rsf"));
            assertEquals(context.getInstanceID(), instance.instance().getInstanceId());
        }
    }

    @Test
    void discoversProviderThroughAutomaticJdbcRegistryConfiguration() throws Throwable {
        // 只声明数据源 Bean 和配置文件，不提供 RegistryConfiguration Bean。
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:hasor_auto_registry;DB_CLOSE_DELAY=-1");
        try (Connection connection = database.getConnection(); //
             InputStream stream = getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql"); //
             Reader script = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
        }
        Properties settings = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/hasor-registry.properties")) {
            settings.load(stream);
        }
        try (AppContext provider = application("provider", null, binder -> {
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
            binder.bindType(DataSource.class).nameWith("registryDb").toInstance(database);
        }).loadSettings(settings).build(); //
             AppContext consumer = application("consumer", null, binder -> {
                 binder.bindType(DataSource.class).nameWith("registryDb").toInstance(database);
             }).loadSettings(settings).build()) {
            // 消费者通过共享存储发现提供者，使用真实网络调用。
            assertEquals("ORDER-1001", consumer.getInstance(OrderService.class).getOrder("ORDER-1001").getOrderId());
            MetContext target = provider.getInstance(MetContext.class);
            TargetAdmin admin = consumer.getInstance(MetContext.class).getExtension(TargetAdmin.class);
            var instance = admin.getInstanceAdminByAddress(target.getListenAddress("rsf"));
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());
        }
    }

    @Test
    void closesListenerWhenContainerCloses() throws Throwable {
        // 通过监听端口重绑确认容器拥有并关闭上下文。
        AppContext app = application("closing", null).build();
        int port = app.getInstance(MetContext.class).getListenAddress("rsf").getPort();
        app.close();
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(socket.isBound());
        }
    }

    @Test
    void rejectsAmbiguousProviders() {
        // 同一个接口的两个提供者应在启动时失败。
        Hasor builder = application("ambiguous", null, binder -> {
            binder.bindType(OrderService.class).nameWith("first").toInstance(new OrderServiceImpl());
            binder.bindType(OrderService.class).nameWith("second").toInstance(new OrderServiceImpl());
        });
        RuntimeException error = assertThrows(RuntimeException.class, builder::build);
        assertTrue(error.toString().contains("Multiple Meteor provider"));
    }

    @Test
    void publishesTheContainerInstance() throws Throwable {
        // 通过具体类绑定，接口注入与发布必须指向同一个容器实例。
        try (AppContext app = application("identity", null, binder -> {
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
        }).build()) {
            MetContext meteor = app.getInstance(MetContext.class);
            OrderService published = meteor.getServiceProvider(meteor.getServiceInfo(OrderService.class)).get();
            assertSame(app.getInstance(OrderServiceImpl.class), published);
            assertSame(published, app.getInstance(OrderService.class));
        }
    }

    @Test
    void acceptsInterfaceBindingBeforeMeteorModule() throws Throwable {
        // 原生接口绑定无需重新生成消费者，也不要求 Module 装载顺序。
        OrderService instance = new OrderServiceImpl();
        Properties scope = new Properties();
        scope.setProperty("hasor.loadPackages", "net.hasor.example.services");
        try (AppContext app = Hasor.create().registerShutdownHook(false).loadSettings(scope).addModules(binder -> {
            binder.bindType(OrderService.class).toInstance(instance);
            MetConfiguration config = new MetConfiguration();
            config.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            binder.bindType(MetConfiguration.class).toInstance(config);
        }).addModules(new MeteorModule()).build()) {
            MetContext meteor = app.getInstance(MetContext.class);
            assertSame(instance, meteor.getServiceProvider(meteor.getServiceInfo(OrderService.class)).get());
        }
    }

    @Test
    void readsScanPackagesFromNativeSettings() throws Throwable {
        // 和 Hasor 原生扫描一致，使用当前有效值，不合并较低优先级的扫描节点。
        try (AppContext app = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            Settings settings = binder.getSettings();
            settings.setSetting("hasor.loadPackages", "net.hasor.meteor", Settings.DefaultNameSpace);
            String scanScope = "net.hasor.example.services,net.hasor.example";
            settings.addSetting("hasor.loadPackages", scanScope, Settings.DefaultNameSpace);
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            binder.bindType(MetConfiguration.class).toInstance(configuration);
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
        }).addModules(new MeteorModule()).build()) {
            MetContext context = app.getInstance(MetContext.class);
            assertEquals(MetServiceType.Provider, context.getServiceInfo(OrderService.class).getServiceType());
            assertEquals(MetServiceType.Consumer, context.getServiceInfo(InventoryService.class).getServiceType());
            assertSame(app.getInstance(OrderServiceImpl.class), app.getInstance(OrderService.class));
            assertNull(context.getServiceInfo(ScannerTypes.Service.class));
        }
    }

    @Test
    void rejectsMultipleConfigurationBeans() {
        // 启动配置存在歧义时，在创建上下文之前失败。
        Hasor builder = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            binder.bindType(MetConfiguration.class).nameWith("first").toInstance(new MetConfiguration());
            binder.bindType(MetConfiguration.class).nameWith("second").toInstance(new MetConfiguration());
        }).addModules(new MeteorModule());
        RuntimeException error = assertThrows(RuntimeException.class, builder::build);
        assertTrue(error.toString().contains("Only one MetConfiguration"));
    }

    @Test
    void usesHasorScanScopeWhenMeteorPackagesAreAbsent() throws Throwable {
        // 只设置 Hasor 的原生扫描范围，不再重复声明 meteor.scan-packages。
        Properties properties = new Properties();
        properties.setProperty("hasor.loadPackages", "net.hasor.example.services.*");
        properties.setProperty("meteor.connectors.rsf.host", "127.0.0.1");
        properties.setProperty("meteor.connectors.rsf.port", "0");
        try (AppContext app = Hasor.create().registerShutdownHook(false).loadSettings(properties).addModules(new MeteorModule(), binder -> {
            binder.bindType(OrderServiceImpl.class).asEagerSingleton();
        }).build()) {
            MetContext context = app.getInstance(MetContext.class);
            assertEquals(MetServiceType.Provider, context.getServiceInfo(OrderService.class).getServiceType());
            assertEquals(MetServiceType.Consumer, context.getServiceInfo(InventoryService.class).getServiceType());
            assertSame(app.getInstance(OrderServiceImpl.class), app.getInstance(OrderService.class));
        }
    }

    private static JdbcRegistryConfiguration registry(JdbcDataSource source) {
        JdbcRegistryConfiguration config = new JdbcRegistryConfiguration().setDataSource(source);
        config.setRefreshMillis(50);
        return config;
    }

    private Hasor application(String name, String address, Module... modules) {
        Properties properties = new Properties();
        properties.setProperty("hasor.loadPackages", "net.hasor.example.services");
        properties.setProperty("meteor.application", name);
        properties.setProperty("meteor.unit", "framework");
        properties.setProperty("meteor.connectors.rsf.host", "127.0.0.1");
        properties.setProperty("meteor.connectors.rsf.port", "0");
        if (address != null) {
            try (InputStream input = getClass().getResourceAsStream("/hasor-consumer-address-book.xml")) {
                assertNotNull(input);
                String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Path book = Files.createTempFile(this.directory, "hasor-address-book-", ".xml");
                Files.writeString(book, xml.replace("${address}", address));
                properties.setProperty("meteor.address-book", book.toUri().toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Hasor.create().registerShutdownHook(false).loadSettings(properties) // 先安装 Meteor，再安装业务模块。
                .addModules(new MeteorModule()).addModules(modules);
    }
}

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
import net.hasor.example.OrderClient;
import net.hasor.example.ProviderMain;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.OrderService;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.Props;
import static org.junit.jupiter.api.Assertions.*;

class SolonIntegrationTest {
    @Test
    void injectsConsumerAndPublishesManagedProvider() throws Throwable {
        // 插件安装早于业务扫描，服务实现应在初始化阶段识别。
        AppContext provider = application("provider", null);
        AppContext consumer = null;
        try {
            provider.beanMake(OrderServiceImpl.class);
            provider.start();
            assertNotNull(provider.getBean(InventoryService.class));
            MetContext server = provider.getBean(MetContext.class);
            assertEquals(MetServiceType.Provider, server.getServiceInfo(OrderService.class).getServiceType());
            assertEquals(MetServiceType.Consumer, server.getServiceInfo(InventoryService.class).getServiceType());
            String address = provider.getBean(MetContext.class).getListenAddress("rsf").toString();

            // 字段异步注入由 Solon 完成，调用走两个容器间的 TCP。
            consumer = application("consumer", address);
            consumer.beanMake(OrderClient.class);
            consumer.start();
            OrderClient caller = consumer.getBean(OrderClient.class);
            assertNotNull(consumer.getBean(InventoryService.class));
            assertEquals("ORDER-1001", caller.getOrder("ORDER-1001").getOrderId());
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            provider.stop();
        }
    }

    @Test
    void discoversProviderThroughRegistryBean() throws Throwable {
        // 通过共享 H2 存储完成发布、订阅、真实调用，不设置直连地址。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:solon_registry;DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); InputStream stream = getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql"); Reader script = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
        }
        AppContext provider = application("provider", null);
        AppContext consumer = application("consumer", null);
        try {
            provider.beanMake(OrderServiceImpl.class);
            provider.putWrap(JdbcRegistryConfiguration.class, new BeanWrap(provider, JdbcRegistryConfiguration.class, registry(source)));
            consumer.putWrap(JdbcRegistryConfiguration.class, new BeanWrap(consumer, JdbcRegistryConfiguration.class, registry(source)));
            provider.start();
            consumer.start();

            assertEquals("ORDER-1001", consumer.getBean(OrderService.class).getOrder("ORDER-1001").getOrderId());
        } finally {
            consumer.stop();
            provider.stop();
        }
    }

    @Test
    void closesListenerWhenContainerCloses() throws Throwable {
        // 关闭 Solon 生命周期之后，原监听端口可重新绑定。
        AppContext app = application("closing", null);
        int port;
        try {
            app.start();
            port = app.getBean(MetContext.class).getListenAddress("rsf").getPort();
        } finally {
            app.stop();
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(socket.isBound());
        }
    }

    @Test
    void publishesTheContainerInstance() throws Throwable {
        // BeanWrap 托管实例由 Solon 创建，Meteor 只借用。
        AppContext app = application("identity", null);
        try {
            app.beanMake(OrderServiceImpl.class);
            app.start();
            MetContext meteor = app.getBean(MetContext.class);
            OrderService published = meteor.getServiceProvider(meteor.getServiceInfo(OrderService.class)).get();
            assertSame(app.getBean(OrderServiceImpl.class), published);
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsAmbiguousProviders() throws Throwable {
        // 两个独立的实现 Bean 不能被任意选择。
        AppContext app = application("ambiguous", null);
        try {
            app.putWrap("first", new BeanWrap(app, OrderService.class, new OrderServiceImpl()));
            app.putWrap("second", new BeanWrap(app, OrderService.class, new OrderServiceImpl()));
            assertThrows(IllegalStateException.class, app::start);
        } finally {
            app.stop();
        }
    }

    @Test
    void acceptsNativeConfigurationBean() throws Throwable {
        // 完整配置由原生 Bean 提供时保持应用名称和协议配置。
        AppContext app = application("unused", null);
        try {
            MetConfiguration config = new MetConfiguration();
            config.setApplicationName("custom");
            config.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            app.putWrap(MetConfiguration.class, new BeanWrap(app, MetConfiguration.class, config));
            app.start();
            assertEquals("custom", app.getBean(MetContext.class).getApplicationName());
        } finally {
            app.stop();
        }
    }

    @Test
    void requiresExplicitScopeForStandaloneContextWithoutApplication() {
        // 无启动类的独立 AppContext 没有框架默认包，需显式指定。
        AppContext app = new AppContext(null, getClass().getClassLoader(), new Props());
        assertThrows(IllegalArgumentException.class, () -> new MeteorPlugin().start(app));
    }

    @Test
    void usesSolonStartupPackageWhenMeteorPackagesAreAbsent() {
        // 不手动装载插件，由 META-INF/solon 自动发现。
        SolonApp app = Solon.start(ProviderMain.class, new String[0], boot -> {
            boot.cfg().setProperty("meteor.connectors.rsf.host", "127.0.0.1");
            boot.cfg().setProperty("meteor.connectors.rsf.port", "0");
        });
        try {
            MetContext meteor = app.context().getBean(MetContext.class);
            assertNotNull(meteor);
            assertTrue(meteor.isOnline());
            assertNotNull(meteor.getServiceInfo(OrderService.class));
            assertNotNull(meteor.getServiceInfo(InventoryService.class));
            assertNotNull(app.context().getBean(OrderService.class));
        } finally {
            Solon.stopBlock(false, 0);
        }
    }

    @Configuration
    public static class ProviderFactory {
        @Bean
        public OrderService orderService(InventoryService inventory) {
            assertNotNull(inventory);
            return new OrderServiceImpl();
        }
    }

    @Test
    void resolvesFactoryProviderThatDependsOnConsumer() throws Throwable {
        // @Bean 依赖尚未创建的消费接口，不能把延迟创建的实现误判为消费者。
        AppContext provider = application("factory-provider", null);
        AppContext consumer = null;
        try {
            provider.beanMake(ProviderFactory.class);
            provider.start();
            MetContext server = provider.getBean(MetContext.class);
            assertEquals(MetServiceType.Provider, server.getServiceInfo(OrderService.class).getServiceType());
            String address = server.getListenAddress("rsf").toString();
            consumer = application("factory-consumer", address);
            consumer.start();
            assertEquals("ORDER-1001", consumer.getBean(OrderService.class).getOrder("ORDER-1001").getOrderId());
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            provider.stop();
        }
    }

    private static JdbcRegistryConfiguration registry(JdbcDataSource source) {
        JdbcRegistryConfiguration config = new JdbcRegistryConfiguration().setDataSource(source);
        config.setRefreshMillis(50);
        return config;
    }

    private static AppContext application(String name, String address) throws Throwable {
        Props properties = new Props();
        properties.setProperty("meteor.application-name", name);
        properties.setProperty("meteor.unit", "framework");
        properties.setProperty("meteor.scan-packages", "net.hasor.example.services");
        properties.setProperty("meteor.connectors.rsf.host", "127.0.0.1");
        properties.setProperty("meteor.connectors.rsf.port", "0");
        if (address != null) {
            try {
                Path book = Files.createTempFile("solon-address-book-", ".xml");
                String xml = """
                        <address-book xmlns="https://www.hasor.net/schema/meteor-address-book"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:schemaLocation="https://www.hasor.net/schema/meteor-address-book https://www.hasor.net/rsf/schema/meteor-address-book.xsd">
                          <service group="solon-demo" name="%s" version="1.0">
                            <address>%s</address>
                          </service>
                        </address-book>
                        """.formatted(OrderService.class.getName(), address);
                Files.writeString(book, xml);
                properties.setProperty("meteor.address-book", book.toUri().toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        AppContext app = new AppContext(null, SolonIntegrationTest.class.getClassLoader(), properties);
        new MeteorPlugin().start(app);
        return app;
    }
}

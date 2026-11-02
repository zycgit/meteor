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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.example.OrderClient;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.OrderService;
import net.hasor.example.services.OrderServiceImpl;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class SpringIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMeteor(basePackages = "net.hasor.example.services")
    static class Enable {
    }

    @Test
    void injectsConsumerAndCallsFinalAopProviderOverTcp() {
        // 发布容器增强后的实例，另外一个接口没有实现，只能成为消费者。
        AtomicBoolean intercepted = new AtomicBoolean();
        try (AnnotationConfigApplicationContext provider = application("provider", null)) {
            provider.registerBean(OrderService.class, OrderServiceImpl::new);
            provider.registerBean("aop", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof OrderService) {
                        ProxyFactory proxy = new ProxyFactory(bean);
                        proxy.addAdvice((MethodInterceptor) invocation -> {
                            intercepted.set(true);
                            return invocation.proceed();
                        });
                        return proxy.getProxy();
                    }
                    return bean;
                }
            });
            provider.refresh();
            MetContext server = provider.getBean(MetContext.class);
            OrderService published = server.getServiceProvider(server.getServiceInfo(OrderService.class)).get();
            assertSame(provider.getBean(OrderService.class), published);
            assertNotNull(provider.getBean(InventoryService.class));
            assertEquals(MetServiceType.Provider, server.getServiceInfo(OrderService.class).getServiceType());
            assertEquals(MetServiceType.Consumer, server.getServiceInfo(InventoryService.class).getServiceType());
            String address = server.getListenAddress("rsf").toString();

            // 自动生成接口代理并完成业务 Bean 注入，调用必须经过网络。
            try (AnnotationConfigApplicationContext consumer = application("consumer", address)) {
                consumer.registerBean(OrderClient.class);
                consumer.refresh();
                OrderClient caller = consumer.getBean(OrderClient.class);
                assertNotNull(consumer.getBean(InventoryService.class));
                assertEquals("ORDER-1001", caller.getOrder("ORDER-1001").getOrderId());
                assertTrue(intercepted.get());
            }
        }
    }

    @Test
    void discoversProviderThroughRegistryBean() throws Exception {
        // 两个独立 Spring 容器共享 H2 注册存储，消费端不配置直连地址。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring_registry;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); InputStream stream = getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql"); Reader script = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, script);
        }
        try (AnnotationConfigApplicationContext provider = application("provider", null); AnnotationConfigApplicationContext consumer = application("consumer", null)) {
            provider.registerBean(OrderService.class, OrderServiceImpl::new);
            provider.registerBean(JdbcRegistryConfiguration.class, () -> registry(dataSource));
            consumer.registerBean(JdbcRegistryConfiguration.class, () -> registry(dataSource));
            provider.refresh();
            consumer.refresh();

            assertEquals("ORDER-1001", consumer.getBean(OrderService.class).getOrder("ORDER-1001").getOrderId());
        }
    }

    @Test
    void rejectsAmbiguousProvidersBeforeListening() {
        // 多实现不能任意挑选一个发布。
        try (AnnotationConfigApplicationContext app = application("ambiguous", null)) {
            app.registerBean("first", OrderService.class, OrderServiceImpl::new);
            app.registerBean("second", OrderService.class, OrderServiceImpl::new);
            assertThrows(IllegalStateException.class, app::refresh);
        }
    }

    @Test
    void closesListenerWhenContainerCloses() throws Exception {
        // 保存监听端口，关闭容器后验证端口可重新绑定。
        AnnotationConfigApplicationContext app = application("closing", null);
        app.refresh();
        int port = app.getBean(MetContext.class).getListenAddress("rsf").getPort();
        app.close();
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(socket.isBound());
        }
    }

    @Test
    void acceptsNativeConfigurationBean() {
        // 原生配置 Bean 支持完整 Meteor 配置，不由框架适配器二次覆盖。
        try (AnnotationConfigApplicationContext app = application("unused", null)) {
            app.registerBean(MetConfiguration.class, () -> {
                MetConfiguration config = new MetConfiguration().setApplicationName("custom");
                config.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                return config;
            });
            app.refresh();
            assertEquals("custom", app.getBean(MetContext.class).getApplicationName());
        }
    }

    @Test
    void mountsJsonRpcAndRestrictsProviderProtocol() {
        // 同时安装 TCP/RSF 与 HTTP/JSON-RPC，单个服务只发布 JSON-RPC。
        try (AnnotationConfigApplicationContext provider = application("http-provider", null)) {
            Map<String, Object> protocols = new LinkedHashMap<>();
            protocols.put("meteor.connectors.jsonrpc.host", "127.0.0.1");
            protocols.put("meteor.connectors.jsonrpc.port", "0");
            protocols.put("meteor.connectors.jsonrpc.context", "/rpc");
            protocols.put("meteor.services." + OrderService.class.getName() + ".protocols", "jsonrpc");
            protocols.put("meteor.services." + OrderService.class.getName() + ".aliases.jsonrpc", "orders");
            provider.getEnvironment().getPropertySources().addFirst(new MapPropertySource("protocols", protocols));
            provider.registerBean(OrderService.class, OrderServiceImpl::new);
            provider.refresh();
            MetContext server = provider.getBean(MetContext.class);
            assertNotNull(server.getListenAddress("rsf"));
            assertEquals(Set.of("jsonrpc"), server.getServiceInfo(OrderService.class).getBindProtocols());
            assertEquals("orders", server.getServiceInfo(OrderService.class).getAliasName("jsonrpc"));
            String address = server.getListenAddress("jsonrpc").toString();

            // 消费端安装相同协议和别名，通过实际 HTTP 端口调用。
            try (AnnotationConfigApplicationContext consumer = application("http-consumer", address)) {
                consumer.getEnvironment().getPropertySources().addFirst(new MapPropertySource("protocols", protocols));
                consumer.refresh();
                assertEquals("ORDER-1001", consumer.getBean(OrderService.class).getOrder("ORDER-1001").getOrderId());
            }
        }
    }

    @Test
    void stopsBeforeBusinessBeansAreDestroyedAndSupportsRestart() {
        // 使用原生 Spring 生命周期暂停和恢复入口。
        AtomicBoolean providerDestroyed = new AtomicBoolean();
        AnnotationConfigApplicationContext app = application("lifecycle", null);
        app.registerBean(OrderService.class, OrderServiceImpl::new);
        app.registerBean("destroyObserver", DisposableBean.class, () -> () -> {
            assertFalse(app.getBean(MetContext.class).isOnline());
            providerDestroyed.set(true);
        });
        app.refresh();
        app.stop();
        assertFalse(app.getBean(MetContext.class).isOnline());
        app.start();
        assertTrue(app.getBean(MetContext.class).isOnline());
        app.close();
        assertTrue(providerDestroyed.get());
    }

    @Test
    void ignoresUnavailableProtocolConfiguration() {
        // 不在 SPI 列表中的配置不安装协议，也不会影响已启用的 RSF。
        try (AnnotationConfigApplicationContext app = application("invalid", null)) {
            Map<String, Object> invalid = Map.of("meteor.connectors.missing.host", "127.0.0.1", "meteor.connectors.missing.port", "8001");
            app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("invalid", invalid));
            app.refresh();
            assertEquals(Set.of("rsf"), app.getBean(MetContext.class).runProtocols());
        }
    }

    @Test
    void releasesContextWhenBindingFails() {
        // 扩展已经分配资源，绑定非法协议时必须关闭该扩展。
        AtomicBoolean extensionClosed = new AtomicBoolean();
        try (AnnotationConfigApplicationContext app = application("binding-failure", null)) {
            app.registerBean(MetExtensionFactory.class, () -> context -> new MetExtension() {
                @Override
                public <T> T getService(Class<T> type) {
                    return null;
                }

                @Override
                public void beforeStart() {
                }

                @Override
                public void afterStart() {
                }

                @Override
                public void close() {
                    extensionClosed.set(true);
                }
            });
            String key = "meteor.services." + OrderService.class.getName() + ".protocols";
            Map<String, Object> invalid = Map.of(key, "missing");
            app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("invalid", invalid));
            assertThrows(RuntimeException.class, app::refresh);
            assertTrue(extensionClosed.get());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProviderFactory {
        @Bean
        OrderService orderService(InventoryService inventory) {
            assertNotNull(inventory);
            return new OrderServiceImpl();
        }
    }

    @Test
    void resolvesFactoryProviderThatDependsOnConsumer() {
        // 工厂方法依赖消费代理，服务装配不能触发提供者的提前实例化。
        try (AnnotationConfigApplicationContext provider = application("factory-provider", null)) {
            provider.register(ProviderFactory.class);
            provider.refresh();
            MetContext server = provider.getBean(MetContext.class);
            String address = server.getListenAddress("rsf").toString();
            try (AnnotationConfigApplicationContext consumer = application("factory-consumer", address)) {
                consumer.refresh();
                assertEquals("ORDER-1001", consumer.getBean(OrderService.class).getOrder("ORDER-1001").getOrderId());
            }
        }
    }

    private static JdbcRegistryConfiguration registry(JdbcDataSource dataSource) {
        JdbcRegistryConfiguration config = new JdbcRegistryConfiguration().setDataSource(dataSource);
        config.setRefreshMillis(50);
        return config;
    }

    private static AnnotationConfigApplicationContext application(String name, String address) {
        AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("meteor.application-name", name);
        properties.put("meteor.unit", "framework");
        properties.put("meteor.connectors.rsf.host", "127.0.0.1");
        properties.put("meteor.connectors.rsf.port", "0");
        if (address != null) {
            try {
                Path book = Files.createTempFile("spring-address-book-", ".xml");
                String xml = """
                        <address-book>
                          <service group="spring-demo" name="%s" version="1.0">
                            <address>%s</address>
                          </service>
                        </address-book>
                        """.formatted(OrderService.class.getName(), address);
                Files.writeString(book, xml);
                properties.put("meteor.address-book", book.toUri().toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("meteorTest", properties));
        app.register(Enable.class);
        return app;
    }
}

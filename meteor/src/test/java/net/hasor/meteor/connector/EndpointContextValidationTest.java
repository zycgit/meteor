/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointContextValidationTest {
    @Test
    public void schemeAliasCannotSupersedeUndeclaredLogicalNameContext() {
        // 复用已注册的 HTTP 协议 SPI，为协议名称和地址 scheme 分别设置标识。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/missing"), false));
        service.setBindContextPaths("A", new ContextPath(List.of("/orders"), false));

        // 逻辑名优先于地址别名，因此无效的逻辑名路径必须阻止发布。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("Context is not configured"));
            assertTrue(failure.getMessage().contains("/missing"));
        }
    }

    @Test
    public void repeatedNameSelectionSupersedesUndeclaredSchemeContext() {
        // 协议名称和 scheme 指向同一挂载，服务最后一次用原名选择有效路径。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/old-name"), false));
        service.setBindContextPaths("a", new ContextPath(List.of("/old-scheme"), false));
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/orders"), false));

        // 发布事件应使用最终有效选择，不再校验已被覆盖的路径。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            assertEquals(List.of("/orders"), protocol.contextPaths(service));
        }
    }

    @Test
    public void logicalNameContextTakesPriorityOverUndeclaredAliasContext() {
        // 协议仅声明订单路径，逻辑名和公开别名分别选择不同路径。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("a", new ContextPath(List.of("/missing"), false));

        // 逻辑名存在时不再使用别名，发布校验与实际路径选择保持一致。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            assertEquals(List.of("/orders"), protocol.contextPaths(service));
        }
    }

    @Test
    public void validAliasDoesNotHideAnEarlierUnknownProtocolSelector() {
        // 只配置一个已知协议，未知选择器不能被当成该协议的旧别名。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("missing-protocol", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("a", new ContextPath(List.of("/orders"), false));

        // 即使最后一个选择合法，先前引用的未知协议仍须拒绝。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unknown protocol"));
            assertTrue(failure.getMessage().contains("missing-protocol"));
        }
    }

    @Test
    public void contextSelectorOutsideServiceProtocolScopeIsRejected() {
        // 两个协议均已配置，服务仅绑定其中一个协议。
        ProtocolConfig first = new ProtocolConfig("httpa", "a", "httpa", Map.of("contextPath", "/orders"));
        ProtocolConfig second = new ProtocolConfig("httpb", "b", "httpb", Map.of("contextPath", "/inventory"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("HTTPB");
        service.setBindContextPaths("a", new ContextPath(List.of("/orders"), false));

        // 路径虽然已经声明，选择上下文也不能扩大服务的协议发布范围。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unbound protocol"));
            assertTrue(failure.getMessage().contains("a"));
        }
    }

    @Test
    public void logicalContextIsValidatedAgainstEveryEndpointMount() {
        // 同一逻辑协议跨两个端点挂载，但只有首个端点声明订单路径。
        ProtocolConfig first = new ProtocolConfig("httpa", "http", "httpa", //
                Map.of("contextPath", "/orders"));
        ProtocolConfig second = new ProtocolConfig("httpa", "https", "httpa", //
                Map.of("contextPath", "/inventory"));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));

        // 不能只校验最后写入索引的一个挂载；任一相关端点不支持该路径都必须失败。
        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("Context is not configured"));
        }
    }

    @Test
    public void sharedSchemeAliasAcrossLogicalProtocolsIsAmbiguous() {
        // 两个不同逻辑协议共享公开地址 scheme，HTTP 路由仍可通过各自路径区分。
        ProtocolConfig first = new ProtocolConfig("httpa", "http", "httpa", Map.of("contextPath", "/a"));
        ProtocolConfig second = new ProtocolConfig("httpb", "http", "httpb", Map.of("contextPath", "/b"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("http", new ContextPath(List.of("/a"), false));

        // 未给逻辑名时，地址别名无法决定服务属于哪一个协议。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("alias is ambiguous"));
        }
    }

    private ValidationRuntime runtime(ConnectorConfig... endpoints) {
        return new ValidationRuntime(Arrays.asList(endpoints));
    }

    private ConnectorConfig endpoint(ProtocolConfig... protocols) {
        return this.endpoint("http-contexts", protocols);
    }

    private ConnectorConfig endpoint(String name, ProtocolConfig... protocols) {
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "test");
        return new ConnectorConfig(name, address, Map.of(), List.of(protocols), false);
    }

    private static final class ValidationRuntime implements AutoCloseable {
        private final AtomicReference<MetBindInfo<?>> active = new AtomicReference<>();
        private final ConnectorManager      manager;

        private ValidationRuntime(Collection<ConnectorConfig> endpoints) {
            ClassLoader loader = EndpointContextValidationTest.class.getClassLoader();
            MetSettings settings = this.settings(loader, endpoints);
            MetContext context = this.context(loader, settings);
            this.manager = new ConnectorManager(context);
            this.manager.init();
        }

        private void register(MetBindInfo<?> service) {
            this.active.set(service);
            MetContextEvent event = new MetContextEvent(MetContextEventType.REGISTERED, service);
            this.manager.serviceEvent(event);
        }

        private MetSettings settings(ClassLoader loader, Collection<ConnectorConfig> endpoints) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (StringUtils.equals("getConnectorConfigs", method.getName())) {
                    return Collections.unmodifiableCollection(endpoints);
                }
                throw new UnsupportedOperationException(method.getName());
            };
            Class<?>[] interfaces = { MetSettings.class };
            return (MetSettings) Proxy.newProxyInstance(loader, interfaces, handler);
        }

        private MetContext context(ClassLoader loader, MetSettings settings) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (StringUtils.equals("getSettings", method.getName())) {
                    return settings;
                }
                if (StringUtils.equals("getClassLoader", method.getName())) {
                    return loader;
                }
                if (StringUtils.equals("getServiceInfo", method.getName())) {
                    return this.active.get();
                }
                throw new UnsupportedOperationException(method.getName());
            };
            Class<?>[] interfaces = { MetContext.class };
            return (MetContext) Proxy.newProxyInstance(loader, interfaces, handler);
        }

        @Override
        public void close() {
            this.manager.close();
        }
    }
}

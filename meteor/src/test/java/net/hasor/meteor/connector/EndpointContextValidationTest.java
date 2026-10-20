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
import net.hasor.example.connector.protocol.HttpEchoService;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointContextValidationTest {
    @Test
    public void publishedSchemeCannotSelectServiceContexts() {
        // 已注册的 HTTP 协议使用独立的公开地址前缀。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("A", new ContextPath(List.of("/orders"), false));

        // 地址前缀不是协议选择器，即使路径已经挂载也不能发布。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unknown protocol: a"));
        }
    }

    @Test
    public void repeatedNameSelectionReplacesAnEarlierContext() {
        // 协议仅挂载订单路径，服务先后按同一个协议名设置两个目录。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/old-name"), false));
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/orders"), false));

        // 发布事件应使用最终有效选择，不再校验已被覆盖的路径。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            assertEquals(List.of("/orders"), new RouteMountIndex(endpoint.name(), protocol).contextPaths(service));
        }
    }

    @Test
    public void validProtocolNameCannotHideAPublishedSchemeSelector() {
        // 直接构造的服务声明同时包含有效协议名和无效的地址前缀选择器。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("a", new ContextPath(List.of("/missing"), false));

        // 每个选择器都必须是协议名，不能因另一个选择器合法而忽略错误。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unknown protocol: a"));
        }
    }

    @Test
    public void validProtocolNameDoesNotHideAnEarlierUnknownSelector() {
        // 只配置一个已知协议，服务声明先引用一个未知协议名。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("missing-protocol", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/orders"), false));

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
        ProtocolConfig first = new ProtocolConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ProtocolConfig second = new ProtocolConfig("httpb", "b", Map.of("contextPath", "/inventory"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("HTTPB");
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));

        // 路径虽然已经声明，选择上下文也不能扩大服务的协议发布范围。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unbound protocol"));
            assertTrue(failure.getMessage().contains("httpa"));
        }
    }

    @Test
    public void namedContextIsValidatedAgainstEveryEndpointMount() {
        // 同一协议跨两个端点挂载，但只有首个端点声明订单路径。
        ProtocolConfig first = new ProtocolConfig("httpa", "http", //
                Map.of("contextPath", "/orders"));
        ProtocolConfig second = new ProtocolConfig("httpa", "https", //
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
    public void sharedPublishedSchemeIsStillAnUnknownContextSelector() {
        // 两个不同协议共享公开地址 scheme，HTTP 路由仍可通过各自路径区分。
        ProtocolConfig first = new ProtocolConfig("httpa", "http", Map.of("contextPath", "/a"));
        ProtocolConfig second = new ProtocolConfig("httpb", "http", Map.of("contextPath", "/b"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("http", new ContextPath(List.of("/a"), false));

        // 地址前缀不参与协议选择，不再产生所谓的别名歧义。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("unknown protocol: http"));
        }
    }

    @Test
    public void protocolNameSelectsContextsWhenAnotherProtocolUsesItAsScheme() {
        // 第一个协议的地址前缀与第二个协议名重名，各自挂载不同目录。
        ProtocolConfig first = new ProtocolConfig("httpa", "httpb", Map.of("contextPath", "/a"));
        ProtocolConfig second = new ProtocolConfig("httpb", "http", Map.of("contextPath", "/b"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("HTTPB");
        service.setBindContextPaths("HTTPB", new ContextPath(List.of("/b"), false));

        // 名字只选中第二个协议，不会校验第一个协议的目录。
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            assertEquals(List.of("/b"), new RouteMountIndex(endpoint.name(), second).contextPaths(service));
            assertTrue(new RouteMountIndex(endpoint.name(), first).contextPaths(service).isEmpty());
        }
    }

    @Test
    public void removedServiceReleasesItsProtocolDefinitionsAndEndpoints() {
        // 通过登记事件完成协议准备，再以相同服务对象发出移除事件。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            ProtocolMeta meta = service.getProtocolMeta("httpa");
            assertEquals(Set.of(endpoint.name()), meta.getEndpoints().keySet());
            ProtocolBinding binding = meta.getEndpoint(endpoint.name());
            assertEquals("http", binding.getScheme());
            assertNull(binding.getDefinition());

            // 外部仍持有旧服务描述时，也不能继续看到已撤销的协议发布状态。
            runtime.remove(service);
            assertNull(service.getProtocolMeta("httpa"));
        }
    }

    @Test
    public void aLaterEndpointFailureDoesNotCommitEarlierProtocolPreparation() {
        // 首个端点可以准备成功，第二个端点的显式无效配置使整次登记失败。
        ProtocolConfig first = new ProtocolConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ProtocolConfig invalid = new ProtocolConfig("httpa", "https", //
                Map.of("contextPath", "/orders", "failService", "true"));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", invalid);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            // 未进入统一提交阶段，先前准备的定义和端点都不能出现在服务上。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertEquals("Invalid test protocol definition", failure.getMessage());
            assertNull(service.getProtocolMeta("httpa"));
        }
    }

    @Test
    public void rejectingPrefixPublicationPreservesOtherProtocolAndLocalService() throws Exception {
        // 同一端点挂载两个协议，只有第一个协议拒绝当前服务。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").contextPath("/orders").option("rejectService", true);
        http.protocol("httpb").contextPath("/other");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> "local:" + value) //
                    .contextPathByPrefix("httpa", "/orders") //
                    .contextPathByPrefix("httpb", "/other").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            RouteMountIndex rejected = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpa"));
            RouteMountIndex accepted = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpb"));

            // 拒绝协议没有发布定义或目录入口，另一协议仍可接纳同一个服务。
            ProtocolMeta rejectedMeta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            assertNotNull(rejectedMeta);
            assertEquals(new ContextPath(List.of("/orders"), false), rejectedMeta.getContextPath());
            assertTrue(rejectedMeta.getEndpoints().isEmpty());
            assertNull(rejected.binding(service));
            assertTrue(rejected.contextPaths(service).isEmpty());
            assertNull(rejected.contextPath(service));
            assertFalse(rejected.accepts(service, rejected.match("/orders/echo")));
            ProtocolMeta acceptedMeta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpb");
            assertEquals(Set.of(endpoint.name()), acceptedMeta.getEndpoints().keySet());
            assertNull(acceptedMeta.getEndpoint(endpoint.name()).getDefinition());
            assertTrue(accepted.accepts(service, accepted.match("/other/echo")));

            // 协议拒绝不撤销本地注册，通过公开客户端仍可优先调用本地实现。
            context.start();
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            HttpEchoService local = context.getRsfClient().getRemote(service);
            assertEquals("local:value", local.echo("value"));
        }
    }

    @Test
    public void rejectingExactPublicationDoesNotExposeItsPathThroughAnotherProtocol() throws Exception {
        // 两个协议分别拥有服务精确地址，拒绝只影响所属协议的地址。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").option("rejectService", true);
        http.protocol("httpb");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value) //
                    .contextPathByExact("httpa", "/orders/echo") //
                    .contextPathByExact("httpb", "/other/echo").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            RouteMountIndex rejected = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpa"));
            RouteMountIndex accepted = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpb"));
            RouteMount rejectedPath = RouteMount.exact("/orders/echo");
            RouteMount acceptedPath = RouteMount.exact("/other/echo");

            // 被拒绝服务的精确地址既不属于自身协议，也不会串入另一个协议。
            ProtocolMeta rejectedMeta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            ContextPath original = new ContextPath(Set.of("/orders/echo"), true);
            assertEquals(original, rejectedMeta.getContextPath());
            assertTrue(rejectedMeta.getEndpoints().isEmpty());
            assertFalse(rejected.accepts(service, rejectedPath));
            assertFalse(accepted.accepts(service, rejectedPath));
            ProtocolBinding binding = accepted.binding(service);
            assertNotNull(binding);
            ContextPath effective = new ContextPath(Set.of("/other/echo"), true);
            assertEquals(effective, binding.getContextPath());
            assertEquals(effective, MetBindInfoWrap.unwrap(service).getProtocolMeta("httpb").getContextPath());
            assertEquals(Set.of("/other/echo"), accepted.exactPaths(service));
            assertTrue(accepted.accepts(service, acceptedPath));
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
        }
    }

    @Test
    public void laterEndpointAcceptsTheSameProtocolAfterAnEarlierEndpointRejectsIt() throws Exception {
        // 先配置拒绝端点，随后配置同名协议的可用端点。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("rejectService", true);
        configuration.connector("http").protocol("httpa").contextPath("/orders");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value).protocol("httpa").register();
            List<ConnectorConfig> endpoints = List.copyOf(context.getSettings().getConnectorConfigs());
            ConnectorConfig rejectedEndpoint = endpoints.get(0);
            ConnectorConfig acceptedEndpoint = endpoints.get(1);
            RouteMountIndex rejected = new RouteMountIndex( //
                    rejectedEndpoint.name(), rejectedEndpoint.protocol("httpa"));
            RouteMountIndex accepted = new RouteMountIndex( //
                    acceptedEndpoint.name(), acceptedEndpoint.protocol("httpa"));

            // 同一个协议只登记真正接受服务的端点，各端点独立判断路径接纳。
            ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta("HTTPA");
            assertEquals(Set.of(acceptedEndpoint.name()), meta.getEndpoints().keySet());
            assertNull(meta.getEndpoint(rejectedEndpoint.name()));
            assertNotNull(accepted.binding(service));
            assertTrue(rejected.contextPaths(service).isEmpty());
            assertFalse(rejected.accepts(service, rejected.match("/orders/echo")));
            assertEquals(List.of("/orders"), accepted.contextPaths(service));
            assertTrue(accepted.accepts(service, accepted.match("/orders/echo")));
        }
    }

    @Test
    public void laterEndpointRejectionPreservesAnEarlierEndpointsPublication() throws Exception {
        // 已接受服务的端点在前，拒绝同名协议的端点在后。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").contextPath("/orders");
        configuration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("rejectService", true);
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value).protocol("httpa").register();
            List<ConnectorConfig> endpoints = List.copyOf(context.getSettings().getConnectorConfigs());
            ConnectorConfig acceptedEndpoint = endpoints.get(0);
            ConnectorConfig rejectedEndpoint = endpoints.get(1);
            RouteMountIndex accepted = new RouteMountIndex( //
                    acceptedEndpoint.name(), acceptedEndpoint.protocol("httpa"));
            RouteMountIndex rejected = new RouteMountIndex( //
                    rejectedEndpoint.name(), rejectedEndpoint.protocol("httpa"));

            // 后续返回 null 不覆盖已有定义，也不会把被拒端点记入服务发布结果。
            ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            assertEquals(Set.of(acceptedEndpoint.name()), meta.getEndpoints().keySet());
            assertNull(meta.getEndpoint(rejectedEndpoint.name()));
            assertNotNull(accepted.binding(service));
            assertTrue(accepted.accepts(service, accepted.match("/orders/echo")));
            assertFalse(rejected.accepts(service, rejected.match("/orders/echo")));
        }
    }

    @Test
    public void contextCloseClearsEndpointResultsAndPreservesTheOriginalDeclaration() throws Exception {
        // 插件定义与原始路径声明分别保存在端点结果和协议组中。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").contextPath("/orders");
        Object definition = new Object();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value) //
                    .contextPathByPrefix("httpa", "/orders") //
                    .bindResource(definition).register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolMeta before = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            ContextPath original = new ContextPath(List.of("/orders"), false);
            assertEquals(original, before.getContextPath());
            assertSame(definition, before.getEndpoint(endpoint.name()).getDefinition());

            // 关闭只清理接受结果，调用方持有的绑定继续保存用户的原始配置声明。
            context.close();
            ProtocolMeta after = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            assertNotNull(after);
            assertEquals(original, after.getContextPath());
            assertTrue(after.getEndpoints().isEmpty());
            assertNull(after.getEndpoint(endpoint.name()));
        }
    }

    @Test
    public void sameProtocolKeepsEachEndpointsDefinitionAndEffectiveDefaultPathIndependent() throws Exception {
        // 同名协议在两个端点使用不同定义和默认路径，服务本身没有声明路径。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("definition", "orders-definition");
        configuration.connector("http").protocol("httpa") //
                .contextPath("/inventory").option("definition", "inventory-definition");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value).register();
            List<ConnectorConfig> endpoints = List.copyOf(context.getSettings().getConnectorConfigs());
            ConnectorConfig firstEndpoint = endpoints.get(0);
            ConnectorConfig secondEndpoint = endpoints.get(1);
            ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            ProtocolBinding first = meta.getEndpoint(firstEndpoint.name());
            ProtocolBinding second = meta.getEndpoint(secondEndpoint.name());

            // 原始声明保持未指定，两份接受结果分别保存自己的定义和有效路径。
            assertNull(meta.getContextPath());
            assertEquals(Set.of(firstEndpoint.name(), secondEndpoint.name()), meta.getEndpoints().keySet());
            assertNotSame(first, second);
            assertEquals("orders-definition", first.getDefinition());
            assertEquals("inventory-definition", second.getDefinition());
            assertEquals(new ContextPath(List.of("/orders"), false), first.getContextPath());
            assertEquals(new ContextPath(List.of("/inventory"), false), second.getContextPath());
            assertEquals("orders-definition", first.getOptions().get("definition"));
            assertEquals("inventory-definition", second.getOptions().get("definition"));
        }
    }

    @Test
    public void unselectedServiceUsesOnlyProtocolDefaultContext() throws Exception {
        // 协议允许多个上下文，服务未显式选择时只发布到默认路径。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/default").addContextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }).register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            RouteMountIndex routes = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpa"));

            ProtocolMeta publication = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            assertNull(publication.getContextPath());
            ContextPath effective = publication.getEndpoint(endpoint.name()).getContextPath();
            assertEquals(new ContextPath(List.of("/default"), false), effective);
            assertEquals(List.of("/default"), routes.contextPaths(service));
            assertEquals("/default", routes.contextPath(service));
        }
    }

    @Test
    public void explicitServiceContextsDoNotImplicitlyIncludeDefaultContext() throws Exception {
        // 同一协议的一次选择可以指定多个上下文，选择顺序也决定默认发布地址。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/default") //
                .addContextPath("/orders").addContextPath("/inventory");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .contextPathByPrefix("HTTPA", "/orders/", "/inventory").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            RouteMountIndex routes = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpa"));

            // 显式选择不含默认上下文，首个选择用于默认发布地址。
            assertEquals(List.of("/orders", "/inventory"), routes.contextPaths(service));
            assertEquals("/orders", routes.contextPath(service));
            assertFalse(routes.accepts(service, routes.match("/default")));
            assertTrue(routes.accepts(service, routes.match("/orders")));
            assertTrue(routes.accepts(service, routes.match("/inventory")));
            assertTrue(service.getBindProtocols().isEmpty());
            ContextPath selectedPaths = new ContextPath(List.of("/orders", "/inventory"), false);
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertEquals(selectedPaths, MetBindInfoWrap.unwrap(published).getProtocolMeta("httpa").getContextPath());
            assertEquals(selectedPaths, routes.binding(service).getContextPath());
        }
    }

    @Test
    public void exactContextSelectionUsesTheProtocolNameIgnoringCase() throws Exception {
        // 协议公开独立地址前缀，服务通过协议名称声明精确调用路径。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").addressScheme("public");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .protocol("HTTPA").contextPathByExact("HTTPA", "/orders/echo").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            RouteMountIndex routes = new RouteMountIndex(endpoint.name(), endpoint.protocol("httpa"));

            // 发布结果保存标准协议名，精确路径仍由该协议处理。
            assertEquals(Set.of("httpa"), service.getBindProtocols());
            assertEquals(Set.of("httpa"), MetBindInfoWrap.unwrap(service).getProtocolMetas().keySet());
            ContextPath exact = new ContextPath(Set.of("/orders/echo"), true);
            assertEquals(exact, MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa").getContextPath());
            assertEquals(exact, routes.binding(service).getContextPath());
            assertEquals(Set.of("/orders/echo"), routes.exactPaths(service));
            assertEquals("public", endpoint.protocol("httpa").scheme());
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
        private final ConnectorManager                manager;

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

        private void remove(MetBindInfo<?> service) {
            this.active.set(null);
            this.manager.serviceEvent(new MetContextEvent(MetContextEventType.REMOVED, service));
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

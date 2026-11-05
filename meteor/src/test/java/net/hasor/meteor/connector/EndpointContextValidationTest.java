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
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EndpointContextValidationTest {
    @Test
    public void endpointDescriptionRetainsExactContextSelection() {
        // 精确路径和目录前缀不能仅通过相同的路径字符串区分。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpa", "http", Map.of()));
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/tasks/run"), true));
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            ProtocolBinding binding = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());

            // 描述与有效路径来自同一个绑定，管理查询能够辨别实际路由策略。
            assertTrue(binding.getDescription().path("contextExact").asBoolean());
            assertEquals(Set.of("/tasks/run"), binding.getContextPath().getPaths());
        }
    }

    @Test
    public void preparedDescriptionsKeepEndpointDefinitionsIndependent() {
        // 相同协议的两个端点具有不同配置定义，描述也必须分别保存。
        ConnectorConfig first = this.endpoint("first", ProtocolConfig.newConfig("httpa", "http", //
                Map.of("definition", "first-definition")));
        ConnectorConfig second = this.endpoint("second", ProtocolConfig.newConfig("httpa", "http", //
                Map.of("definition", "second-definition")));
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        try (ValidationRuntime runtime = this.runtime(first, second)) {
            runtime.register(service);
            ProtocolMeta meta = service.getProtocolMeta("httpa");

            // 查询各自已提交的绑定，不按协议名选择第一个定义。
            assertEquals("first-definition", meta.getEndpoint("first").getDescription().path("definition").asText());
            assertEquals("second-definition", meta.getEndpoint("second").getDescription().path("definition").asText());
        }
    }

    @Test
    public void resourceReplacementCommitsANewDescriptionWithoutChangingThePreviousBinding() {
        // 首次发布后保留旧绑定，模拟在途调用仍持有该版本。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpa", "http", Map.of()));
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindResource("httpa", "original");
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            ProtocolBinding original = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());

            // 新资源完成准备后统一替换；旧绑定和旧描述保持不变。
            service.setBindResource("httpa", "replacement");
            runtime.register(service);
            ProtocolBinding current = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());
            assertNotSame(original, current);
            assertEquals("replacement", current.getDescription().path("definition").asText());
            assertEquals("original", original.getDescription().path("definition").asText());
        }
    }

    @Test
    public void clearedResourceDoesNotLeaveTheOldDescription() {
        // 清空资源声明后，重新准备结果不能沿用旧资源的描述。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpa", "http", Map.of()));
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindResource("httpa", "original");
        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            service.setBindResource("httpa", null);
            runtime.register(service);

            // 有效绑定仍然存在，其契约描述已经清除原资源的信息。
            ProtocolBinding current = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());
            assertNull(current.getDefinition());
            assertFalse(current.getDescription().has("definition"));
        }
    }

    @Test
    public void laterEndpointDescriptionFailurePreservesAllCommittedBindings() {
        // 第二个端点仅在描述新资源时失败，第一端点已经完成本轮准备。
        ConnectorConfig first = this.endpoint("first", ProtocolConfig.newConfig("httpa", "http", Map.of()));
        ConnectorConfig second = this.endpoint("second", ProtocolConfig.newConfig("httpa", "http", //
                Map.of("failDescription", "true")));
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindResource("httpa", "original");
        try (ValidationRuntime runtime = this.runtime(first, second)) {
            runtime.register(service);
            ProtocolBinding originalFirst = service.getProtocolMeta("httpa").getEndpoint("first");
            ProtocolBinding originalSecond = service.getProtocolMeta("httpa").getEndpoint("second");

            // 描述失败与其他发布准备失败一样，不能提交部分端点或替换旧描述。
            service.setBindResource("httpa", "description-error");
            assertThrows(IllegalArgumentException.class, () -> runtime.register(service));
            ProtocolMeta retained = service.getProtocolMeta("httpa");
            assertSame(originalFirst, retained.getEndpoint("first"));
            assertSame(originalSecond, retained.getEndpoint("second"));
            assertEquals("original", originalFirst.getDescription().path("definition").asText());
            assertEquals("original", originalSecond.getDescription().path("definition").asText());
        }
    }

    @Test
    public void exactRoutesUseEachEndpointsProtocolOptions() {
        // 同一协议挂到两个端点，使用不同配置并共享一个服务精确地址。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", //
                Map.of("contextPath", "/first", "routeMarker", "first"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpa", "https", //
                Map.of("contextPath", "/second", "routeMarker", "second"));
        ConnectorConfig firstEndpoint = this.endpoint("first", first);
        ConnectorConfig secondEndpoint = this.endpoint("second", second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/tasks/run"), true));

        try (ValidationRuntime runtime = this.runtime(firstEndpoint, secondEndpoint)) {
            // 真实登记链路编译精确路由，参数直接取自各自端点的 ProtocolConfig。
            runtime.register(service);
            RouteSelection firstRoute = runtime.manager.routeResolver(firstEndpoint.name()).apply("/tasks/run");
            RouteSelection secondRoute = runtime.manager.routeResolver(secondEndpoint.name()).apply("/tasks/run");

            // 相同服务地址不导致跨端点复用另一份配置，绑定只保留路径和定义。
            assertNotNull(firstRoute);
            assertNotNull(secondRoute);
            assertEquals("/first", firstRoute.options().get("contextPath"));
            assertEquals("first", firstRoute.options().get("routeMarker"));
            assertEquals("Java", firstRoute.options().get("serializeType"));
            assertEquals("/second", secondRoute.options().get("contextPath"));
            assertEquals("second", secondRoute.options().get("routeMarker"));
            assertEquals("Java", secondRoute.options().get("serializeType"));
        }
    }

    @Test
    public void removingOneSharedPublicationKeepsTheOtherServiceOnEachEndpoint() {
        // 两个端点配置不同的协议参数，两个服务共享同一协议和精确路径。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of("routeMarker", "plain"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpa", "https", Map.of("routeMarker", "secure"));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", second);
        ContextPath shared = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> removed = new ServiceDomain<>(Runnable.class);
        removed.setServiceType(MetServiceType.Provider);
        removed.setBindContextPaths("httpa", shared);
        ServiceDomain<AutoCloseable> retained = new ServiceDomain<>(AutoCloseable.class);
        retained.setServiceType(MetServiceType.Provider);
        retained.setBindContextPaths("httpa", shared);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(removed);
            runtime.register(retained);
            ProtocolMeta retainedMeta = retained.getProtocolMeta("httpa");
            RouteSelection plainRoute = runtime.manager.routeResolver("plain").apply("/orders/echo");
            RouteSelection secureRoute = runtime.manager.routeResolver("secure").apply("/orders/echo");
            assertNotNull(plainRoute);
            assertNotNull(secureRoute);

            // 撤销一个服务后，另一个服务仍保留两个端点上的独立绑定和路由参数。
            runtime.remove(removed);
            assertTrue(removed.getProtocolMeta("httpa").getEndpoints().isEmpty());
            assertSame(retainedMeta, retained.getProtocolMeta("httpa"));
            assertEquals(Set.of("plain", "secure"), retainedMeta.getEndpoints().keySet());
            assertSame(plainRoute, runtime.manager.routeResolver("plain").apply("/orders/echo"));
            assertSame(secureRoute, runtime.manager.routeResolver("secure").apply("/orders/echo"));
            assertNotSame(plainRoute, secureRoute);
            assertEquals("plain", plainRoute.options().get("routeMarker"));
            assertEquals("secure", secureRoute.options().get("routeMarker"));
        }
    }

    @Test
    public void replacingOneSharedPublicationKeepsTheOtherServiceOnTheOriginalPath() {
        // 两个服务共享精确路径，其中一个准备改用新的路径。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of());
        ConnectorConfig endpoint = this.endpoint(protocol);
        ContextPath shared = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> replaced = new ServiceDomain<>(Runnable.class);
        replaced.setServiceType(MetServiceType.Provider);
        replaced.setBindContextPaths("httpa", shared);
        ServiceDomain<AutoCloseable> retained = new ServiceDomain<>(AutoCloseable.class);
        retained.setServiceType(MetServiceType.Provider);
        retained.setBindContextPaths("httpa", shared);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(replaced);
            runtime.register(retained);
            ProtocolMeta retainedMeta = retained.getProtocolMeta("httpa");
            ContextPath replacement = new ContextPath(List.of("/orders/replacement"), true);

            // 本轮快照替换当前服务，另一个服务仍拥有原路径。
            replaced.setBindContextPaths("httpa", replacement);
            runtime.register(replaced);
            ProtocolBinding current = replaced.getProtocolMeta("httpa").getEndpoint(endpoint.name());
            assertSame(replacement, current.getContextPath());
            assertSame(retainedMeta, retained.getProtocolMeta("httpa"));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/replacement"));
        }
    }

    @Test
    public void consumerBindingsDoNotParticipateInInboundIndexRebuilds() {
        // 消费者可以声明出站精确路径，并完成协议绑定。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of());
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> consumer = new ServiceDomain<>(Runnable.class);
        consumer.setServiceType(MetServiceType.Consumer);
        consumer.setBindContextPaths("httpa", new ContextPath(List.of("/consumer/echo"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(consumer);
            assertNotNull(consumer.getProtocolMeta("httpa").getEndpoint(endpoint.name()));

            // 后续提供者触发索引重建，也不能把消费者的出站路径作为入站路由。
            ServiceDomain<AutoCloseable> provider = new ServiceDomain<>(AutoCloseable.class);
            provider.setServiceType(MetServiceType.Provider);
            provider.setBindContextPaths("httpa", new ContextPath(List.of("/provider/echo"), true));
            runtime.register(provider);
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/provider/echo"));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/consumer/echo"));
        }
    }

    @Test
    public void shadowBindingsDoNotParticipateInInboundIndexRebuilds() {
        // 隐藏服务保留自己的协议绑定，但不登记入站路由。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of());
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> hidden = new ServiceDomain<>(Runnable.class);
        hidden.setServiceType(MetServiceType.Provider);
        hidden.setShadow(true);
        hidden.setBindContextPaths("httpa", new ContextPath(List.of("/hidden/echo"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(hidden);
            assertNotNull(hidden.getProtocolMeta("httpa").getEndpoint(endpoint.name()));

            // 普通提供者引起的重建只读取可入站的已提交快照。
            ServiceDomain<AutoCloseable> visible = new ServiceDomain<>(AutoCloseable.class);
            visible.setServiceType(MetServiceType.Provider);
            visible.setBindContextPaths("httpa", new ContextPath(List.of("/visible/echo"), true));
            runtime.register(visible);
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/visible/echo"));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/hidden/echo"));
        }
    }

    @Test
    public void failedRedeclarationDoesNotAffectLaterIndexRebuilds() {
        // 先提交可入站服务，再准备一个不能发布的隐藏服务声明。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders/echo"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            RouteSelection previous = runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo");
            assertNotNull(previous);
            service.setShadow(true);
            service.setBindContextPaths("httpa", new ContextPath(List.of("/unconfigured"), false));
            assertThrows(IllegalArgumentException.class, () -> runtime.register(service));

            // 另一服务引起重建时仍使用旧发布快照，不读取失败的新声明或隐藏状态。
            ServiceDomain<AutoCloseable> other = new ServiceDomain<>(AutoCloseable.class);
            other.setServiceType(MetServiceType.Provider);
            other.setBindContextPaths("httpa", new ContextPath(List.of("/orders/other"), true));
            runtime.register(other);
            assertSame(previous, runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/other"));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/unconfigured"));
        }
    }

    @Test
    public void removingServiceClearsItsEndpointAndLeavesTheOtherEndpointUnchanged() {
        // 两个协议分别挂载到独立端点，两个服务使用相同路径。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of());
        ProtocolConfig second = ProtocolConfig.newConfig("httpb", "https", Map.of());
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", second);
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> removed = new ServiceDomain<>(Runnable.class);
        removed.setServiceType(MetServiceType.Provider);
        removed.addBindProtocol("httpa");
        removed.setBindContextPaths("httpa", path);
        ServiceDomain<AutoCloseable> retained = new ServiceDomain<>(AutoCloseable.class);
        retained.setServiceType(MetServiceType.Provider);
        retained.addBindProtocol("httpb");
        retained.setBindContextPaths("httpb", path);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(removed);
            runtime.register(retained);
            assertNotNull(runtime.manager.routeResolver("plain").apply("/orders/echo"));
            RouteSelection previous = runtime.manager.routeResolver("secure").apply("/orders/echo");
            assertNotNull(previous);
            ProtocolMeta retainedMeta = retained.getProtocolMeta("httpb");

            // 首个端点提交空索引；另一个端点无需更新，保持原来的绑定和索引。
            runtime.remove(removed);
            assertNull(runtime.manager.routeResolver("plain").apply("/orders/echo"));
            assertSame(previous, runtime.manager.routeResolver("secure").apply("/orders/echo"));
            assertSame(retainedMeta, retained.getProtocolMeta("httpb"));
            assertEquals("httpb", previous.protocolName());
        }
    }

    @Test
    public void switchingTheOnlyExactPublicationToPrefixClearsTheIndex() {
        // 当前端点只有一个服务提供精确路由。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders/echo"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));

            // 发布改成前缀后，空候选索引仍需提交，同时安装新的协议绑定。
            ContextPath prefix = new ContextPath(List.of("/orders"), false);
            service.setBindContextPaths("httpa", prefix);
            runtime.register(service);
            ProtocolBinding binding = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());
            assertSame(prefix, binding.getContextPath());
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void prefixPublicationKeepsExistingExactRoutesAndCommitsItsBinding() {
        // 端点已有精确路由，新服务只使用协议默认的目录前缀。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> exactService = new ServiceDomain<>(Runnable.class);
        exactService.setServiceType(MetServiceType.Provider);
        exactService.setBindContextPaths("httpa", new ContextPath(List.of("/tasks/run"), true));
        ServiceDomain<AutoCloseable> prefixService = new ServiceDomain<>(AutoCloseable.class);
        prefixService.setServiceType(MetServiceType.Provider);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(exactService);
            RouteSelection previous = runtime.manager.routeResolver(endpoint.name()).apply("/tasks/run");
            assertNotNull(previous);

            // 没有精确路由更新时，协议绑定仍需提交，已有服务的路由保持不变。
            runtime.register(prefixService);
            ProtocolMeta meta = prefixService.getProtocolMeta("httpa");
            ProtocolBinding binding = meta.getEndpoint(endpoint.name());
            assertEquals(new ContextPath(List.of("/orders"), false), binding.getContextPath());
            assertSame(previous, runtime.manager.routeResolver(endpoint.name()).apply("/tasks/run"));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders"));
        }
    }

    @Test
    public void switchingToPrefixPublicationRemovesOnlyTheServicesOldExactRoutes() {
        // 两个服务各自登记精确路由，随后只将其中一个改成目录前缀。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders/echo"), true));
        ServiceDomain<AutoCloseable> other = new ServiceDomain<>(AutoCloseable.class);
        other.setServiceType(MetServiceType.Provider);
        other.setBindContextPaths("httpa", new ContextPath(List.of("/tasks/run"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            runtime.register(other);
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
            RouteSelection retained = runtime.manager.routeResolver(endpoint.name()).apply("/tasks/run");
            assertNotNull(retained);

            // 虽然本轮没有新增精确路由，仍需准备并提交旧路由的撤销。
            ContextPath prefix = new ContextPath(List.of("/orders"), false);
            service.setBindContextPaths("httpa", prefix);
            runtime.register(service);
            ProtocolBinding binding = service.getProtocolMeta("httpa").getEndpoint(endpoint.name());
            assertSame(prefix, binding.getContextPath());
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
            assertSame(retained, runtime.manager.routeResolver(endpoint.name()).apply("/tasks/run"));
        }
    }

    @Test
    public void laterEndpointConflictDoesNotCommitAnEarlierEndpointsRouteRemoval() {
        // 首个端点只有 HTTPA，第二个还支持 HTTPB；另一个服务通过 HTTPA 占用目标路径。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpb", "http", Map.of("contextPath", "/other"));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", first, second);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders/echo"), true));
        ContextPath occupied = new ContextPath(List.of("/orders/occupied"), true);
        ServiceDomain<AutoCloseable> blocker = new ServiceDomain<>(AutoCloseable.class);
        blocker.setServiceType(MetServiceType.Provider);
        blocker.addBindProtocol("httpa");
        blocker.setBindContextPaths("httpa", occupied);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(service);
            runtime.register(blocker);
            ProtocolMeta previous = service.getProtocolMeta("httpa");
            RouteSelection plainRoute = runtime.manager.routeResolver("plain").apply("/orders/echo");
            RouteSelection secureRoute = runtime.manager.routeResolver("secure").apply("/orders/echo");
            assertNotNull(plainRoute);
            assertNotNull(secureRoute);

            // 首个端点准备撤销旧精确路由，第二个端点通过 HTTPB 发布时发生冲突。
            service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));
            service.setBindContextPaths("httpb", occupied);
            ProtocolMeta declared = service.getProtocolMeta("httpa");
            ProtocolMeta declaredSecond = service.getProtocolMeta("httpb");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("Conflicting exact service path"));

            // 失败前准备的撤销不会提交，两个协议的声明和两个端点的旧路由保持原样。
            assertSame(declared, service.getProtocolMeta("httpa"));
            assertSame(declaredSecond, service.getProtocolMeta("httpb"));
            assertSame(previous.getEndpoint("plain"), declared.getEndpoint("plain"));
            assertSame(previous.getEndpoint("secure"), declared.getEndpoint("secure"));
            assertSame(plainRoute, runtime.manager.routeResolver("plain").apply("/orders/echo"));
            assertSame(secureRoute, runtime.manager.routeResolver("secure").apply("/orders/echo"));
            RouteSelection retained = runtime.manager.routeResolver("secure").apply("/orders/occupied");
            assertEquals("httpa", retained.protocolName());
        }
    }

    @Test
    public void sameProtocolServiceKeyConflictsAtTheSameEndpointContext() {
        // 两个提供者的 Java 类型不同，但协议名称和默认目录相同。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpnamed", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> existing = this.namedService(Runnable.class, "Orders", null);
        ServiceDomain<AutoCloseable> candidate = this.namedService(AutoCloseable.class, "Orders", null);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(existing);
            ProtocolBinding accepted = existing.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertNotNull(accepted);

            // 相同入站身份必须在提交前拒绝，不能覆盖先前服务的有效绑定。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(candidate);
            });
            assertTrue(failure.getMessage().contains("Conflicting service route for protocol httpnamed"));
            assertTrue(failure.getMessage().contains(endpoint.name()));
            assertTrue(failure.getMessage().contains("Orders"));
            assertSame(accepted, existing.getProtocolMeta("httpnamed").getEndpoint(endpoint.name()));
            assertTrue(candidate.getProtocolMeta("httpnamed").getEndpoints().isEmpty());
        }
    }

    @Test
    public void intersectingPathSetsConflictForTheSameProtocolServiceKey() {
        // 两个精确路径集合只共享其中一个地址，也会形成同名服务歧义。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath firstPaths = new ContextPath(List.of("/orders/first", "/orders/shared"), true);
        ContextPath secondPaths = new ContextPath(List.of("/orders/shared", "/orders/second"), true);
        ServiceDomain<Runnable> existing = this.namedService(Runnable.class, "Orders", firstPaths);
        ServiceDomain<AutoCloseable> candidate = this.namedService(AutoCloseable.class, "Orders", secondPaths);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(existing);
            RouteSelection previous = runtime.manager.routeResolver(endpoint.name()).apply("/orders/shared");
            assertNotNull(previous);

            // 集合存在交集就拒绝整轮发布，候选独有路径也不能提前进入路由。
            assertThrows(IllegalArgumentException.class, () -> runtime.register(candidate));
            assertSame(previous, runtime.manager.routeResolver(endpoint.name()).apply("/orders/shared"));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/first"));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/second"));
            assertTrue(candidate.getProtocolMeta("httpnamed").getEndpoints().isEmpty());
        }
    }

    @Test
    public void distinctContextsAllowTheSameProtocolServiceKey() {
        // 同一端点的两个目录各自发布同名服务，调用路径可以唯一地区分它们。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpnamed", "http", //
                Map.of("contextPath", "/orders", "contextPaths", "[\"/inventory\"]"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ContextPath orders = new ContextPath(List.of("/orders"), false);
        ContextPath inventory = new ContextPath(List.of("/inventory"), false);
        ServiceDomain<Runnable> first = this.namedService(Runnable.class, "Service", orders);
        ServiceDomain<AutoCloseable> second = this.namedService(AutoCloseable.class, "Service", inventory);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(first);
            runtime.register(second);

            // 名称校验限定在所选目录，各自绑定保留自己的可达范围。
            ProtocolBinding firstBinding = first.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            ProtocolBinding secondBinding = second.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertEquals(orders, firstBinding.getContextPath());
            assertEquals(inventory, secondBinding.getContextPath());
            assertEquals("Service", firstBinding.getDefinition());
            assertEquals("Service", secondBinding.getDefinition());
        }
    }

    @Test
    public void exactAndDirectoryPathsDoNotConflictForTheSameProtocolServiceKey() {
        // 路径文本相同，但一个是精确地址，另一个是目录挂载。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpnamed", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> exact = this.namedService(Runnable.class, "Orders", //
                new ContextPath(List.of("/orders"), true));
        ServiceDomain<AutoCloseable> directory = this.namedService(AutoCloseable.class, "Orders", //
                new ContextPath(List.of("/orders"), false));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(exact);
            runtime.register(directory);

            // 匹配方式参与身份范围判断，登记目录不会替换精确路由。
            ProtocolBinding exactBinding = exact.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            ProtocolBinding directoryBinding = directory.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertTrue(exactBinding.getContextPath().isExact());
            assertFalse(directoryBinding.getContextPath().isExact());
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders"));
        }
    }

    @Test
    public void distinctEndpointsAllowTheSameProtocolServiceKeyAndPath() {
        // 两个端点各自只接受一个 Java 类型，即使名称和路径相同也不会相互影响。
        ProtocolConfig first = ProtocolConfig.newConfig("httpnamed", "http", //
                Map.of("rejectServiceType", AutoCloseable.class.getName()));
        ProtocolConfig second = ProtocolConfig.newConfig("httpnamed", "https", //
                Map.of("rejectServiceType", Runnable.class.getName()));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", second);
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> firstService = this.namedService(Runnable.class, "Orders", path);
        ServiceDomain<AutoCloseable> secondService = this.namedService(AutoCloseable.class, "Orders", path);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(firstService);
            runtime.register(secondService);

            // 仅已接受的端点参与名称占用，另一个端点的同名服务仍可正常发布。
            assertEquals(Set.of("plain"), firstService.getProtocolMeta("httpnamed").getEndpoints().keySet());
            assertEquals(Set.of("secure"), secondService.getProtocolMeta("httpnamed").getEndpoints().keySet());
            assertNotNull(runtime.manager.routeResolver("plain").apply("/orders/echo"));
            assertNotNull(runtime.manager.routeResolver("secure").apply("/orders/echo"));
        }
    }

    @Test
    public void consumerBindingDoesNotReserveAnInboundProtocolServiceKey() {
        // 消费者和提供者使用同一个协议名称及精确路径。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> consumer = this.namedService(Runnable.class, "Orders", path);
        consumer.setServiceType(MetServiceType.Consumer);
        ServiceDomain<AutoCloseable> provider = this.namedService(AutoCloseable.class, "Orders", path);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(consumer);
            assertNotNull(consumer.getProtocolMeta("httpnamed").getEndpoint(endpoint.name()));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));

            // 出站绑定不占用入站身份，后续提供者可以登记同名路由。
            runtime.register(provider);
            assertNotNull(provider.getProtocolMeta("httpnamed").getEndpoint(endpoint.name()));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void shadowBindingDoesNotReserveAnInboundProtocolServiceKey() {
        // 隐藏提供者已经准备协议绑定，但尚未对外提供服务。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> hidden = this.namedService(Runnable.class, "Orders", path);
        hidden.setShadow(true);
        ServiceDomain<AutoCloseable> visible = this.namedService(AutoCloseable.class, "Orders", path);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(hidden);
            assertNotNull(hidden.getProtocolMeta("httpnamed").getEndpoint(endpoint.name()));
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));

            // 隐藏绑定不预留名称，正常提供者可以发布相同的入站身份。
            runtime.register(visible);
            assertNotNull(visible.getProtocolMeta("httpnamed").getEndpoint(endpoint.name()));
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void replacingAServiceDoesNotConflictWithItsOwnProtocolServiceKey() {
        // 相同服务以相同名称和精确地址重新准备协议绑定。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> service = this.namedService(Runnable.class, "Orders", path);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            ProtocolBinding previous = service.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            RouteSelection route = runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo");
            assertNotNull(route);

            // 替换时只与其他服务比较，不会把自己的旧发布结果判定为冲突。
            runtime.register(service);
            ProtocolBinding current = service.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertNotSame(previous, current);
            assertEquals("Orders", current.getDefinition());
            assertSame(route, runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void laterEndpointServiceKeyConflictPreservesAllPublishedBindingsAndRoutes() {
        // 首个端点拒绝占位服务，第二个端点接受两个原本名称和路径都不同的提供者。
        ProtocolConfig first = ProtocolConfig.newConfig("httpnamed", "http", //
                Map.of("rejectServiceType", AutoCloseable.class.getName()));
        ProtocolConfig second = ProtocolConfig.newConfig("httpnamed", "https", Map.of());
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", second);
        ContextPath originalPath = new ContextPath(List.of("/orders/original"), true);
        ContextPath occupiedPath = new ContextPath(List.of("/orders/occupied"), true);
        ServiceDomain<Runnable> service = this.namedService(Runnable.class, "Original", originalPath);
        ServiceDomain<AutoCloseable> blocker = this.namedService(AutoCloseable.class, "Occupied", occupiedPath);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(service);
            runtime.register(blocker);
            ProtocolMeta previous = service.getProtocolMeta("httpnamed");
            ProtocolBinding plainBinding = previous.getEndpoint("plain");
            ProtocolBinding secureBinding = previous.getEndpoint("secure");
            RouteSelection plainRoute = runtime.manager.routeResolver("plain").apply("/orders/original");
            RouteSelection secureRoute = runtime.manager.routeResolver("secure").apply("/orders/original");
            RouteSelection occupiedRoute = runtime.manager.routeResolver("secure").apply("/orders/occupied");
            assertNotNull(plainRoute);
            assertNotNull(secureRoute);
            assertNotNull(occupiedRoute);

            // 首个端点可以改名换址，但第二个端点发现与占位服务身份冲突。
            service.setBindResource("httpnamed", "Occupied");
            service.setBindContextPaths("httpnamed", occupiedPath);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                runtime.register(service);
            });
            assertTrue(failure.getMessage().contains("Conflicting service route"));
            assertTrue(failure.getMessage().contains("secure"));

            // 整轮更新均不提交，两个端点的旧定义和路由仍然有效。
            ProtocolMeta retained = service.getProtocolMeta("httpnamed");
            assertSame(plainBinding, retained.getEndpoint("plain"));
            assertSame(secureBinding, retained.getEndpoint("secure"));
            assertEquals("Original", plainBinding.getDefinition());
            assertEquals("Original", secureBinding.getDefinition());
            assertEquals(originalPath, plainBinding.getContextPath());
            assertEquals(originalPath, secureBinding.getContextPath());
            assertSame(plainRoute, runtime.manager.routeResolver("plain").apply("/orders/original"));
            assertSame(secureRoute, runtime.manager.routeResolver("secure").apply("/orders/original"));
            assertSame(occupiedRoute, runtime.manager.routeResolver("secure").apply("/orders/occupied"));
            assertNull(runtime.manager.routeResolver("plain").apply("/orders/occupied"));
        }
    }

    @Test
    public void emptyProtocolServiceKeysDoNotConflict() {
        // 协议没有提供服务名称时，不应把空标识当成一个共享的入站身份。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> first = this.namedService(Runnable.class, null, path);
        ServiceDomain<AutoCloseable> second = this.namedService(AutoCloseable.class, null, path);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(first);
            runtime.register(second);

            // 两份绑定都被接纳，精确路由仍由同一个协议处理。
            ProtocolBinding firstBinding = first.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            ProtocolBinding secondBinding = second.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertNotNull(firstBinding);
            assertNotNull(secondBinding);
            assertNull(firstBinding.getDefinition());
            assertNull(secondBinding.getDefinition());
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void removingAServiceReleasesItsProtocolServiceKey() {
        // 原提供者占用一个名称和精确路径，替代者使用相同入站身份。
        ConnectorConfig endpoint = this.endpoint(ProtocolConfig.newConfig("httpnamed", "http", Map.of()));
        ContextPath path = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> removed = this.namedService(Runnable.class, "Orders", path);
        ServiceDomain<AutoCloseable> replacement = this.namedService(AutoCloseable.class, "Orders", path);

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(removed);
            runtime.remove(removed);
            assertTrue(removed.getProtocolMeta("httpnamed").getEndpoints().isEmpty());
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));

            // 移除操作同时释放名称，后续服务能够重新占用这条调用路径。
            runtime.register(replacement);
            ProtocolBinding binding = replacement.getProtocolMeta("httpnamed").getEndpoint(endpoint.name());
            assertEquals("Orders", binding.getDefinition());
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void publishedSchemeCannotSelectServiceContexts() {
        // 已注册的 HTTP 协议使用独立的公开地址前缀。
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "a", Map.of("contextPath", "/orders"));
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
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "a", Map.of("contextPath", "/orders"));
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
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "a", Map.of("contextPath", "/orders"));
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
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "a", Map.of("contextPath", "/orders"));
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
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "a", Map.of("contextPath", "/orders"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpb", "b", Map.of("contextPath", "/inventory"));
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
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", //
                Map.of("contextPath", "/orders"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpa", "https", //
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
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/a"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpb", "http", Map.of("contextPath", "/b"));
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
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "httpb", Map.of("contextPath", "/a"));
        ProtocolConfig second = ProtocolConfig.newConfig("httpb", "http", Map.of("contextPath", "/b"));
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
        ProtocolConfig protocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders/echo"), true));

        try (ValidationRuntime runtime = this.runtime(endpoint)) {
            runtime.register(service);
            ProtocolMeta meta = service.getProtocolMeta("httpa");
            assertEquals(Set.of(endpoint.name()), meta.getEndpoints().keySet());
            ProtocolBinding binding = meta.getEndpoint(endpoint.name());
            assertNull(binding.getDefinition());
            assertNotNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));

            // 外部仍持有旧服务描述时，也不能继续看到已撤销的协议发布状态。
            runtime.remove(service);
            assertTrue(service.getProtocolMeta("httpa").getEndpoints().isEmpty());
            assertNull(runtime.manager.routeResolver(endpoint.name()).apply("/orders/echo"));
        }
    }

    @Test
    public void aLaterEndpointFailureDoesNotCommitEarlierProtocolPreparation() {
        // 首个端点可以准备成功，第二个端点的显式无效配置使整次登记失败。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ProtocolConfig invalid = ProtocolConfig.newConfig("httpa", "https", //
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
    public void failedPreparationPreservesTheOriginalContextDeclaration() {
        // 服务声明精确地址，首个端点准备路由后，第二个端点准备失败。
        ProtocolConfig first = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/orders"));
        ProtocolConfig invalid = ProtocolConfig.newConfig("httpa", "https", //
                Map.of("contextPath", "/orders", "failService", "true"));
        ConnectorConfig plain = this.endpoint("plain", first);
        ConnectorConfig secure = this.endpoint("secure", invalid);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        ContextPath declared = new ContextPath(List.of("/orders/echo"), true);
        service.setBindContextPaths("httpa", declared);
        ProtocolMeta original = service.getProtocolMeta("httpa");

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            // 已准备的路径和定义都不提交，原始声明仍可供后续重新发布使用。
            assertThrows(IllegalArgumentException.class, () -> runtime.register(service));
            assertSame(original, service.getProtocolMeta("httpa"));
            assertSame(declared, original.getContextPath());
            assertTrue(original.getEndpoints().isEmpty());
            assertNull(runtime.manager.routeResolver("plain").apply("/orders/echo"));
            assertNull(runtime.manager.routeResolver("secure").apply("/orders/echo"));
        }
    }

    @Test
    public void allEndpointValidationsUseThePublishedProtocolMetadata() throws Exception {
        // 同名协议挂到两个端点，测试提供者记录校验时收到的元信息及端点数量。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("captureMeta", true);
        configuration.connector("http").protocol("httpa") //
                .contextPath("/inventory").option("captureMeta", true);

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> value).register();
            List<ConnectorConfig> endpoints = List.copyOf(context.getSettings().getConnectorConfigs());
            ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta("httpa");
            ProtocolBinding first = meta.getEndpoint(endpoints.get(0).name());
            ProtocolBinding second = meta.getEndpoint(endpoints.get(1).name());
            Map<?, ?> firstInput = (Map<?, ?>) first.getDefinition();
            Map<?, ?> secondInput = (Map<?, ?>) second.getDefinition();

            // 校验向同一候选对象逐个收集绑定，两个端点准备期间都未提前发布。
            assertSame(meta, firstInput.get("meta"));
            assertSame(meta, secondInput.get("meta"));
            assertEquals(0, firstInput.get("preparedEndpoints"));
            assertEquals(1, secondInput.get("preparedEndpoints"));
            assertEquals(false, firstInput.get("published"));
            assertEquals(false, secondInput.get("published"));
            assertEquals(2, meta.getEndpoints().size());
            assertNotSame(first, second);
        }
    }

    @Test
    public void laterEndpointRouteConflictPreservesTheExistingPublication() {
        // 已有服务只在第二个端点发布，另一个协议随后尝试占用相同精确地址。
        ProtocolConfig firstProtocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/a"));
        ProtocolConfig secondProtocol = ProtocolConfig.newConfig("httpb", "http", Map.of("contextPath", "/b"));
        ConnectorConfig plain = this.endpoint("plain", firstProtocol);
        ConnectorConfig secure = this.endpoint("secure", firstProtocol, secondProtocol);
        ContextPath declared = new ContextPath(List.of("/orders/echo"), true);
        ServiceDomain<Runnable> existing = new ServiceDomain<>(Runnable.class);
        existing.setServiceType(MetServiceType.Provider);
        existing.addBindProtocol("httpb");
        existing.setBindContextPaths("httpb", declared);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(existing);
            ProtocolMeta previous = existing.getProtocolMeta("httpb");
            ProtocolBinding accepted = previous.getEndpoint("secure");
            ServiceDomain<Runnable> candidate = new ServiceDomain<>(Runnable.class);
            candidate.setServiceType(MetServiceType.Provider);
            candidate.addBindProtocol("httpa");
            candidate.setBindContextPaths("httpa", declared);
            ProtocolMeta original = candidate.getProtocolMeta("httpa");

            // 首个端点准备成功后的冲突仍撤销整轮发布，旧绑定和旧精确路由保持原样。
            assertThrows(IllegalArgumentException.class, () -> runtime.register(candidate));
            assertSame(original, candidate.getProtocolMeta("httpa"));
            assertTrue(original.getEndpoints().isEmpty());
            assertNull(runtime.manager.routeResolver("plain").apply("/orders/echo"));
            RouteSelection current = runtime.manager.routeResolver("secure").apply("/orders/echo");
            assertEquals("httpb", current.protocolName());
            assertSame(previous, existing.getProtocolMeta("httpb"));
            assertSame(accepted, previous.getEndpoint("secure"));
        }
    }

    @Test
    public void failedRevalidationPreservesTheServicesPublishedBindingsAndRoutes() {
        // 服务先在两个端点发布；另一协议只在第二个端点占用新路径。
        ProtocolConfig firstProtocol = ProtocolConfig.newConfig("httpa", "http", Map.of("contextPath", "/a"));
        ProtocolConfig secondProtocol = ProtocolConfig.newConfig("httpb", "http", Map.of("contextPath", "/b"));
        ConnectorConfig plain = this.endpoint("plain", firstProtocol);
        ConnectorConfig secure = this.endpoint("secure", firstProtocol, secondProtocol);
        ContextPath originalPath = new ContextPath(List.of("/orders/original"), true);
        ContextPath occupiedPath = new ContextPath(List.of("/orders/occupied"), true);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        service.addBindProtocol("httpa");
        service.setBindContextPaths("httpa", originalPath);
        ServiceDomain<Runnable> blocker = new ServiceDomain<>(Runnable.class);
        blocker.setServiceType(MetServiceType.Provider);
        blocker.addBindProtocol("httpb");
        blocker.setBindContextPaths("httpb", occupiedPath);

        try (ValidationRuntime runtime = this.runtime(plain, secure)) {
            runtime.register(service);
            runtime.register(blocker);
            ProtocolMeta previous = service.getProtocolMeta("httpa");
            ProtocolBinding plainBinding = previous.getEndpoint("plain");
            ProtocolBinding secureBinding = previous.getEndpoint("secure");
            RouteSelection plainRoute = runtime.manager.routeResolver("plain").apply("/orders/original");
            RouteSelection secureRoute = runtime.manager.routeResolver("secure").apply("/orders/original");
            RouteSelection occupiedRoute = runtime.manager.routeResolver("secure").apply("/orders/occupied");

            // 重新准备同一服务时，首个端点成功，第二个端点因路径冲突失败。
            service.setBindContextPaths("httpa", occupiedPath);
            ProtocolMeta declared = service.getProtocolMeta("httpa");
            assertThrows(IllegalArgumentException.class, () -> runtime.register(service));

            // 新候选中的绑定不会覆盖旧结果，旧快照及两个端点的路由保持原样。
            assertSame(declared, service.getProtocolMeta("httpa"));
            assertSame(plainBinding, declared.getEndpoint("plain"));
            assertSame(secureBinding, declared.getEndpoint("secure"));
            assertSame(originalPath, previous.getContextPath());
            assertSame(plainBinding, previous.getEndpoint("plain"));
            assertSame(secureBinding, previous.getEndpoint("secure"));
            assertSame(originalPath, plainBinding.getContextPath());
            assertSame(originalPath, secureBinding.getContextPath());
            assertSame(plainRoute, runtime.manager.routeResolver("plain").apply("/orders/original"));
            assertSame(secureRoute, runtime.manager.routeResolver("secure").apply("/orders/original"));
            assertSame(occupiedRoute, runtime.manager.routeResolver("secure").apply("/orders/occupied"));
            assertNull(runtime.manager.routeResolver("plain").apply("/orders/occupied"));
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
                    .bindResource("httpa", definition).register();
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

    private <T> ServiceDomain<T> namedService(Class<T> type, String name, ContextPath path) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setServiceType(MetServiceType.Provider);
        service.addBindProtocol("httpnamed");
        service.setBindResource("httpnamed", name);
        if (path != null) {
            service.setBindContextPaths("httpnamed", path);
        }
        return service;
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

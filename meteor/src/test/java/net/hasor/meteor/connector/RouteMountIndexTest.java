/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.utils.ContextPathUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteMountIndexTest {
    @Test
    public void contextSelectionUsesCaseInsensitiveProtocolName() {
        // 声明名字和 scheme 不同的协议，并准备多个挂载目录。
        String declaredPaths = "[\"/first\",\"/latest\",\"/other\"]";
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", declaredPaths);
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpcwire", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 服务使用大小写不同的协议名选择自己的目录。
        service.setBindContextPaths("GRPC", new ContextPath(List.of("/first"), false));

        // 上下文读取及路由接受规则使用相同的协议名选择。
        assertEquals(List.of("/first"), routes.contextPaths(service));
        assertEquals("/first", routes.contextPath(service));
        RouteMount previous = routes.match("/first");
        RouteMount latest = routes.match("/latest");
        assertTrue(routes.accepts(service, previous));
        assertFalse(routes.accepts(service, latest));
    }

    @Test
    public void publishedSchemeDoesNotSelectServiceContexts() {
        // 准备两个可选目录，协议名与 scheme 使用不同标识。
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", "[\"/first\",\"/latest\"]");
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpcwire", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 直接构造的服务声明仅包含地址前缀，没有协议名选择。
        service.setBindContextPaths("grpcwire", new ContextPath(List.of("/first"), false));

        // 读取配置只识别 name，地址前缀不会覆盖默认目录。
        assertEquals(List.of("/"), routes.contextPaths(service));
        assertEquals("/", routes.contextPath(service));
    }

    @Test
    public void repeatedProtocolNameSelectionReplacesPreviousContexts() {
        // 准备三个可选目录，用于重复设置同一个协议名。
        String declaredPaths = "[\"/first\",\"/second\",\"/latest\"]";
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", declaredPaths);
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpcwire", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 同一个协议名再次调用后，覆盖之前的目录选择。
        service.setBindContextPaths("grpc", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("GRPC", new ContextPath(List.of("/latest"), false));

        // 最后一次协议名选择直接生效。
        assertEquals(List.of("/latest"), routes.contextPaths(service));
    }

    @Test
    public void publishedSchemeDoesNotBindAServiceToTheProtocol() {
        // 服务直接声明只在地址前缀对应的标识下发布。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpcwire", Map.of("contextPath", "/"));
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("grpcwire");

        // 地址前缀不参与协议绑定，不能取得该协议的上下文。
        assertFalse(routes.accepts(service.getBindProtocols()));
        assertTrue(routes.contextPaths(service).isEmpty());
        assertNull(routes.contextPath(service));
    }

    @Test
    public void longestDeclaredContextWinsWithinTheSameProtocol() {
        // 根挂载和更具体的挂载共享一个协议路由。
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/");
        ContextPathUtils.addContextPath(options, "/rpc");
        ContextPathUtils.addContextPath(options, "/rpc/internal");
        ProtocolConfig protocol = ProtocolConfig.newConfig("grpc", "grpc", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);

        // 按最长路径选择，查询参数不改变挂载匹配。
        RouteMount selected = routes.match("/rpc/internal/example.Echo/echo?source=test");
        assertNotNull(selected);
        assertEquals("/rpc/internal", selected.path());
    }

    @Test
    public void contextMatchingRequiresACompletePathSegment() {
        // /rpc 不能抢占 /rpcOther 下的请求。
        Map<String, String> options = Map.of("contextPath", "/rpc");
        ProtocolConfig protocol = ProtocolConfig.newConfig("grpc", "grpc", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);

        // 未匹配的请求保持无路由，不回退到默认 context。
        assertNull(routes.match("/rpcOther/example.Echo/echo"));
    }

    @Test
    public void compiledIndexDoesNotChangeWhenPreparationOptionsChange() {
        // 运行期索引保存当前端点已编译的目录，不再借用可变配置。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/original"));
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);

        // 后续配置准备只影响新编译的索引，已经运行的路由保持原有快照。
        protocol.setOptions(Map.of("contextPath", "/next"));
        assertEquals(List.of("/original"), routes.contextPaths());
        assertNotNull(routes.match("/original/echo"));
        assertNull(routes.match("/next/echo"));
    }

    @Test
    public void explicitContextsKeepTheConfiguredDefaultForOutgoingRequests() {
        // 服务声明多个已挂载目录，默认目录不是声明中的第一个。
        Map<String, String> options = Map.of("contextPath", "/default", "contextPaths", "[\"/other\"]");
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("grpc", new ContextPath(List.of("/other", "/default"), false));

        // 默认目录仍负责生成出站地址，不因服务声明顺序而改变。
        assertEquals("/default/example.Echo/echo", routes.requestPath(service, "example.Echo/echo"));
    }

    @Test
    public void outgoingRequestUsesTheFirstExplicitNonDefaultContext() {
        // 服务仅暴露额外目录，默认目录不在服务选择范围中。
        Map<String, String> options = Map.of("contextPath", "/default", "contextPaths", "[\"/orders\"]");
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", options);
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("grpc", new ContextPath(List.of("/orders"), false));

        // 生成地址必须使用服务已经选择的目录。
        assertEquals("/orders/example.Echo/echo", routes.requestPath(service, "example.Echo/echo"));
    }

    @Test
    public void exactServicePathDoesNotExposeADirectoryAtTheSameLocation() {
        // 相同文本分别表示协议目录与服务精确地址，二者不能互相扩大发布范围。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/echo"));
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("grpc", new ContextPath(List.of("/echo"), true));

        // 只有精确匹配入口能接受该服务，普通目录入口不能调用它。
        assertTrue(routes.accepts(service, RouteMount.exact("/echo")));
        assertFalse(routes.accepts(service, routes.match("/echo")));
    }

    @Test
    public void exactServicePathRejectsAnUndeclaredOutgoingMethod() {
        // 服务的精确声明只允许一个完整调用路径。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/rpc"));
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("grpc", new ContextPath(List.of("/echo"), true));

        // 未声明的请求不能回退到默认目录来绕过精确地址约束。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            routes.requestPath(service, "other");
        });
        assertTrue(failure.getMessage().contains("exact request path: /other"));
    }

    @Test
    public void outgoingRequestRejectsAServiceBoundToAnotherProtocol() {
        // 配置存在合法目录，但服务只绑定另外一个协议。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/rpc"));
        RouteMountIndex routes = new RouteMountIndex("unit", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("soap");

        // 地址生成必须先遵守服务的协议范围。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            routes.requestPath(service, "echo");
        });
        assertTrue(failure.getMessage().contains("does not expose protocol: grpc"));
    }

    @Test
    public void providerWithoutAcceptedPublicationHasNoInboundRoute() {
        // 本地提供者已经存在，但协议还没有接受它，声明目录不能直接开放网络入口。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/rpc"));
        RouteMountIndex routes = new RouteMountIndex("public-http", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);

        // 没有成功发布到当前端点时，前缀查询及服务接纳均不可见。
        assertFalse(routes.accepts(service));
        assertTrue(routes.contextPaths(service).isEmpty());
        assertFalse(routes.accepts(service, routes.match("/rpc")));
    }

    @Test
    public void providerIsRoutableOnlyOnTheEndpointThatAcceptedIt() {
        // 同名协议的两个端点拥有相同目录，服务只在其中一个端点通过校验。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/rpc"));
        RouteMountIndex accepted = new RouteMountIndex("accepted-http", protocol);
        RouteMountIndex rejected = new RouteMountIndex("rejected-http", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Provider);
        ContextPath effective = new ContextPath(List.of("/rpc"), false);
        ProtocolBinding binding = new ProtocolBinding("http", Map.of(), effective, null);
        ProtocolMeta meta = new ProtocolMeta(service, null, Map.of("accepted-http", binding));
        service.replaceProtocolPublication(Map.of("grpc", meta));

        // 接受结果依据真实端点，而非只要同名协议存在就允许入站。
        assertSame(binding, accepted.binding(service));
        assertTrue(accepted.accepts(service));
        assertTrue(accepted.accepts(service, accepted.match("/rpc")));
        assertNull(rejected.binding(service));
        assertFalse(rejected.accepts(service));
        assertFalse(rejected.accepts(service, rejected.match("/rpc")));
    }

    @Test
    public void consumerPathSelectionDoesNotRequireProviderPublication() {
        // 消费者只有出站声明，不必先在本机发布协议定义和监听端点。
        ProtocolConfig protocol = new ProtocolConfig("grpc", "http", Map.of("contextPath", "/rpc"));
        RouteMountIndex routes = new RouteMountIndex("outgoing-http", protocol);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setServiceType(MetServiceType.Consumer);
        service.addBindProtocol("GRPC");

        // 出站路径按消费者声明生成，不因缺少提供者元数据而被拒绝。
        assertTrue(routes.accepts(service));
        assertEquals("/rpc/echo", routes.requestPath(service, "echo"));
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.domain.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证初始化时构建的路由表保持稳定，并按协议名称或请求路径选择协议。 */
public class RouteTableTest {
    @Test
    public void routeSnapshotIsImmutable() {
        // 用可变映射创建路由表，再修改原始映射。
        Map<String, RouteDefinition<String>> routes = new LinkedHashMap<>();
        routes.put("first", this.route("/first"));
        RouteTable<String> table = this.table(routes);
        routes.put("second", this.route("/second"));

        // 路由表保留创建时的外层快照，并拒绝调用方修改只读视图。
        assertFalse(table.contains("second"));
        assertThrows(UnsupportedOperationException.class, () -> table.definitions().clear());
    }

    @Test
    public void resolveProtocolReturnsExplicitProtocol() {
        // 准备多个挂载，并按协议名显式选择其中一个。
        RouteTable<String> table = this.table(Map.of( //
                "first", this.route("/first"), //
                "second", this.route("/second")));

        // 显式协议名忽略大小写，返回端点内已配置的规范名称。
        assertEquals("second", table.resolveProtocol("SECOND"));
    }

    @Test
    public void resolveProtocolRejectsUnknownProtocol() {
        // 准备一个不包含目标协议的路由表。
        RouteTable<String> table = this.table(Map.of("known", this.route("/known")));

        // 显式选择未知协议时立即报告配置错误。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            table.resolveProtocol("unknown");
        });
        assertEquals("Unknown protocol: unknown", failure.getMessage());
    }

    @Test
    public void resolveProtocolUsesOnlyConfiguredProtocol() {
        // 只配置一个协议挂载，不提供显式选择。
        RouteTable<String> table = this.table(Map.of("only", this.route("/only")));

        // 唯一挂载可以安全作为固定选择。
        assertEquals("only", table.resolveProtocol(null));
    }

    @Test
    public void resolveProtocolLeavesMultipleProtocolsUnresolved() {
        // 配置多个协议挂载，不提供显式选择。
        RouteTable<String> table = this.table(Map.of( //
                "first", this.route("/first"), //
                "second", this.route("/second")));

        // 多挂载端点需要后续路由，不能在此处猜测协议。
        assertNull(table.resolveProtocol(null));
    }

    @Test
    public void staticRouterUsesLongestPath() {
        // 准备具有父子关系的两个目录挂载。
        Map<String, RouteDefinition<String>> routes = new LinkedHashMap<>();
        routes.put("parent", this.route("/rpc"));
        routes.put("nested", this.route("/rpc/admin"));
        RouteTable<String> table = this.table(routes);

        // 请求同时匹配父子目录时选择最长路径。
        assertEquals("nested", table.select("/rpc/admin/users").protocolName());
    }

    @Test
    public void staticRouterUsesCompleteParentPath() {
        // 准备父目录挂载和其下的普通请求路径。
        RouteTable<String> table = this.table(Map.of("parent", this.route("/rpc")));

        // 目录边界完整时命中父目录。
        assertEquals("parent", table.select("/rpc/users").protocolName());
    }

    @Test
    public void staticRouterRejectsPartialPathSegment() {
        // 只声明容易与名称前缀混淆的目录挂载。
        RouteTable<String> table = this.table(Map.of("nested", this.route("/rpc/admin")));

        // 路径段尚未结束时不能按字符串前缀误命中。
        assertNull(table.select("/rpc/administrator"));
    }

    @Test
    public void staticRouterIgnoresQueryParameters() {
        // 准备两个目录，查询参数中故意包含另一个目录文本。
        RouteTable<String> table = this.table(Map.of( //
                "orders", this.route("/orders"), //
                "admin", this.route("/admin")));

        // 只使用 URI 的路径部分进行匹配。
        RouteSelection selected = table.select("/orders?id=/admin");
        assertEquals("orders", selected.protocolName());
    }

    @Test
    public void dynamicRouterTakesPriorityOverConfiguredRoutes() {
        // 同时提供静态目录和端点级动态路由器。
        RouteSelection dynamic = new RouteSelection("dynamic", RouteMount.exact("/dynamic"), Map.of());
        Map<String, RouteDefinition<String>> routes = Map.of("configured", this.route("/configured"));
        RouteTable<String> table = new RouteTable<>(routes, uri -> dynamic);

        // 运行时路由表优先委托给精确动态路由器。
        assertSame(dynamic, table.select("/configured"));
    }

    private RouteTable<String> table(Map<String, RouteDefinition<String>> routes) {
        return new RouteTable<>(routes, null);
    }

    private RouteDefinition<String> route(String contextPath) {
        ProtocolConfig config = ProtocolConfig.newConfig("test", "test", Map.of("contextPath", contextPath));
        RouteMountIndex mounts = new RouteMountIndex("unit", config);
        return new RouteDefinition<>(Map.of(), mounts, message -> RouteProbeResult.MATCH);
    }
}

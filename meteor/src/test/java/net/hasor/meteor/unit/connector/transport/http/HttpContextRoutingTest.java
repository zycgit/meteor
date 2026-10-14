/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.ContextPaths;
import net.hasor.meteor.connector.transport.http.HttpConnectorFactory;
import net.hasor.meteor.utils.ContextPathUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpContextRoutingTest {
    @Test
    public void extraContextsDoNotChangeTheSingleProtocolDefault() {
        // 额外挂载不构成新的协议，不应将原来的根默认路径改为协议名。
        Map<String, String> options = new LinkedHashMap<>();
        ContextPathUtils.addContextPath(options, "/internal");
        new HttpConnectorFactory().configRoutes(Map.of("grpc", options));

        // 默认路径保持在前，额外路径仅作为另一个入口。
        assertEquals("/", options.get("contextPath"));
        assertEquals(List.of("/", "/internal"), ContextPathUtils.contextPaths(options));
    }

    @Test
    public void extraContextCannotOccupyAnotherProtocolMount() {
        // 同一端口的两个协议不能占用规范化后相同的路径。
        Map<String, String> grpc = new LinkedHashMap<>();
        grpc.put("contextPath", "/rpc");
        ContextPathUtils.addContextPath(grpc, "/shared/");
        Map<String, String> hprose = new LinkedHashMap<>();
        hprose.put("contextPath", "/shared");

        // 冲突在配置阶段报告，不等待首个请求才暴露路由歧义。
        var factory = new HttpConnectorFactory();
        var failure = assertThrows(IllegalArgumentException.class, () -> {
            factory.configRoutes(Map.of("grpc", grpc, "hprose", hprose));
        });
        assertTrue(failure.getMessage().contains("Duplicate HTTP directory mount: /shared"));
    }

    @Test
    public void longestDeclaredContextWinsWithinTheSameProtocol() {
        // 根挂载和更具体的挂载共享一个协议路由。
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/");
        ContextPathUtils.addContextPath(options, "/rpc");
        ContextPathUtils.addContextPath(options, "/rpc/internal");
        ContextPaths contexts = new ContextPaths(options);

        // 按最长路径选择，查询参数不改变挂载匹配。
        ContextPaths.Mount selected = contexts.match("/rpc/internal/example.Echo/echo?source=test");
        assertNotNull(selected);
        assertEquals("/rpc/internal", selected.path());
    }

    @Test
    public void contextMatchingRequiresACompletePathSegment() {
        // /rpc 不能抢占 /rpcOther 下的请求。
        Map<String, String> options = Map.of("contextPath", "/rpc");
        ContextPaths contexts = new ContextPaths(options);

        // 未匹配的请求保持无路由，不回退到默认 context。
        assertNull(contexts.match("/rpcOther/example.Echo/echo"));
    }

    @Test
    public void repeatedPathsAreDeduplicatedAfterNormalization() {
        // 末尾斜杠不代表新挂载点。
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/rpc/");
        ContextPathUtils.addContextPath(options, "/rpc");
        ContextPathUtils.addContextPath(options, "/rpc/");

        // 路由只保留一个有效路径。
        assertEquals(List.of("/rpc"), ContextPathUtils.contextPaths(options));
    }

    @Test
    public void pathsContainingCommasRemainOneContext() {
        // 路径列表按结构编码，合法路径中的逗号不会成为分隔符。
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/rpc");
        ContextPathUtils.addContextPath(options, "/release,v2");

        // 编码再读取仍是两个完整路径。
        assertEquals(List.of("/rpc", "/release,v2"), ContextPathUtils.contextPaths(options));
    }

    @Test
    public void queryParametersCannotBePartOfAMount() {
        // 挂载配置只能描述路径，查询参数属于单次请求。
        assertThrows(IllegalArgumentException.class, () -> ContextPathUtils.normalize("/rpc?service=echo"));
    }

    @Test
    public void malformedAdditionalContextOptionsFailDuringPreparation() {
        // 直接通过 option 设置列表时，同样不能混入非路径值。
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPaths", "[\"/rpc\",42]");

        // 启动前拒绝错误配置，不将数字强制转成一个路径。
        var factory = new HttpConnectorFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.configRoutes(Map.of("grpc", options)));
    }
}

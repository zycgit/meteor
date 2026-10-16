/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ContextPath;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证公开配置及服务发布入口，不启动网络监听器。 */
public class ServiceContextPublicationTest {
    @Test
    public void additionalContextsLeaveAutomaticDefaultAtRoot() throws Exception {
        // 单个 HTTP 协议增加可选上下文，不改变原本自动确定的根路径默认值。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").addContextPath("/orders/");

        try (MetContext context = configuration.buildContext()) {
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig protocol = endpoint.protocol("httpa");
            assertEquals("/", protocol.contextPath());
            assertEquals(List.of("/", "/orders"), protocol.contextPaths());
        }
    }

    @Test
    public void additionalContextsPreserveExplicitDefaultAndDeduplicatePaths() throws Exception {
        // 显式默认路径与额外路径分别设置，归一化后的重复路径只挂载一次。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/default/").addContextPath("/orders/").addContextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig protocol = endpoint.protocol("httpa");
            assertEquals("/default", protocol.contextPath());
            assertEquals(List.of("/default", "/orders"), protocol.contextPaths());
        }
    }

    @Test
    public void editingAdditionalContextsDoesNotChangeAnExistingContext() throws Exception {
        // 构建拥有默认和额外上下文的首个配置快照。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ProtocolSettings editable = configuration.connector("http").protocol("httpa");
        editable.contextPath("/default/").addContextPath("/orders/");

        try (MetContext before = configuration.buildContext()) {
            ConnectorConfig originalEndpoint = before.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig original = originalEndpoint.protocol("httpa");

            // 修改默认路径并添加上下文后重建，旧快照仍保留原来的路径集合。
            editable.contextPath("/later").addContextPath("/inventory");
            try (MetContext after = configuration.buildContext()) {
                ConnectorConfig changedEndpoint = after.getSettings().getConnectorConfigs().iterator().next();
                ProtocolConfig changed = changedEndpoint.protocol("httpa");
                assertEquals(List.of("/later", "/orders", "/inventory"), changed.contextPaths());
                assertEquals(List.of("/default", "/orders"), original.contextPaths());
            }
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
            ProtocolConfig protocol = endpoint.protocol("httpa");

            assertTrue(service.getBindContextPaths().isEmpty());
            assertEquals(List.of("/default"), protocol.contextPaths(service));
            assertEquals("/default", protocol.contextPath(service));
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
            ProtocolConfig protocol = endpoint.protocol("httpa");

            // 显式选择不含默认上下文，首个选择用于默认发布地址。
            assertEquals(List.of("/orders", "/inventory"), protocol.contextPaths(service));
            assertEquals("/orders", protocol.contextPath(service));
            assertFalse(protocol.accepts(service, protocol.mountIndex().get("/default")));
            assertTrue(protocol.accepts(service, protocol.mountIndex().get("/orders")));
            assertTrue(protocol.accepts(service, protocol.mountIndex().get("/inventory")));
            assertTrue(service.getBindProtocols().isEmpty());
            ContextPath selectedPaths = new ContextPath(List.of("/orders", "/inventory"), false);
            Map<String, ContextPath> selected = Map.of("httpa", selectedPaths);
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertEquals(selected, published.getBindContextPaths());
        }
    }

    @Test
    public void laterSelectionReplacesOnlyTheSameProtocolsContexts() throws Exception {
        // 两个协议分别选择路径，同一协议再次选择时仅替换它自己的上下文集合。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa") //
                .contextPath("/first").addContextPath("/orders").addContextPath("/inventory");
        http.protocol("httpb").contextPath("/second").addContextPath("/shared");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .contextPathByPrefix("httpa", "/orders").contextPathByPrefix("httpb", "/shared") //
                    .contextPathByPrefix("HTTPA", "/inventory").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();

            // 只替换目标协议的上下文，另一协议的选择及服务协议范围保持不变。
            assertEquals(List.of("/inventory"), endpoint.protocol("httpa").contextPaths(service));
            assertEquals(List.of("/shared"), endpoint.protocol("httpb").contextPaths(service));
            assertTrue(service.getBindProtocols().isEmpty());
        }
    }

    @Test
    public void unknownContextProtocolIsRejectedDuringSelection() throws Exception {
        // 上下文选择引用未配置的协议，公开配置入口必须立即拒绝。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            // 选择时立即拒绝未知协议，也不会留下服务登记。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.contextPathByPrefix("missing", "/orders");
            });

            assertTrue(failure.getMessage().contains("missing"));
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void undeclaredContextIsRejectedAndTheSameServiceCanBeCorrected() throws Exception {
        // 选择协议未声明的路径会导致发布失败，已有别名也随该次登记一起撤销。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/default").addContextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });
            service.aliasName("task", "orders").contextPathByPrefix("httpa", "/missing");

            // 发布失败清除服务和别名，错误中包含未声明路径。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
            assertTrue(failure.getMessage().contains("/missing"));
            assertNull(context.getServiceInfo(Runnable.class));
            assertNull(context.getServiceInfo("task", "orders"));

            // 失败不占用服务或别名；修正同一构建器后可以正常发布。
            MetBindInfo<Runnable> corrected = service.contextPathByPrefix("httpa", "/orders").register();
            MetBindInfo<?> published = context.getServiceInfo("task", "orders");
            assertEquals(corrected.getBindID(), published.getBindID());
        }
    }

    @Test
    public void nonHttpProtocolRejectsServiceContextDuringRegistration() throws Exception {
        // RSF 没有 HTTP 上下文语义，不能静默忽略服务显式指定的路径。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });
            service.contextPathByPrefix("rsf", "/orders");

            // 登记时报告协议不支持路径，服务不可见。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);

            assertTrue(failure.getMessage().contains("rsf"));
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void contextSelectionOutsideServiceProtocolScopeIsRejectedDuringRegistration() throws Exception {
        // 路径选择不能隐式扩展服务通过 protocol 指定的协议范围。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").contextPath("/first");
        http.protocol("httpb").contextPath("/second");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });
            service.protocol("httpa").contextPathByPrefix("httpb", "/second");

            // 登记拒绝范围之外的协议，不能留下已发布服务。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);

            assertTrue(failure.getMessage().contains("httpb"));
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void logicalContextSelectionAppliesToEveryMountedAddressScheme() throws Exception {
        // 同一逻辑协议分别通过普通和安全地址公开，并声明相同的可选目录。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .addressScheme("http").contextPath("/default").addContextPath("/orders");
        configuration.connector("http").protocol("httpa") //
                .addressScheme("https").contextPath("/default").addContextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            // 服务只按逻辑协议选择一次目录，该选择应覆盖这个协议的全部端点挂载。
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .contextPathByPrefix("httpa", "/orders").register();

            // 服务保存逻辑协议身份，端点的公开地址 scheme 不参与上下文归属。
            assertEquals(Set.of("httpa"), service.getBindContextPaths().keySet());
            for (ConnectorConfig endpoint : context.getSettings().getConnectorConfigs()) {
                assertEquals(List.of("/orders"), endpoint.protocol("httpa").contextPaths(service));
            }
        }
    }

    @Test
    public void logicalProtocolNameTakesPriorityOverAnotherMountAlias() throws Exception {
        // 第一个协议的地址别名与第二个协议的逻辑名相同。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").addressScheme("httpb").contextPath("/first");
        http.protocol("httpb").addressScheme("public").contextPath("/second");

        try (MetContext context = configuration.buildContext()) {
            // 选择 httpb 必须解释为逻辑协议名，不能同时把 httpa 的地址别名纳入范围。
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .protocol("httpb").contextPathByPrefix("httpb", "/second").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();

            assertEquals(Set.of("httpb"), service.getBindProtocols());
            assertTrue(endpoint.protocol("httpa").contextPaths(service).isEmpty());
            assertEquals(List.of("/second"), endpoint.protocol("httpb").contextPaths(service));
        }
    }

    @Test
    public void uniqueAddressSchemeAliasSelectsOnlyItsPublishedScheme() throws Exception {
        // 地址别名只对应一个逻辑协议时，服务入口仍允许用别名选择。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").addressScheme("public");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .protocol("public").register();

            assertEquals(Set.of("public"), service.getBindProtocols());
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertTrue(endpoint.protocol("httpa").accepts(service.getBindProtocols()));
        }
    }

    @Test
    public void sharedAddressSchemeAliasCannotSelectAServiceProtocol() throws Exception {
        // 同一公开地址 scheme 挂载两个不同逻辑协议时，别名不足以确定服务范围。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").addressScheme("http").contextPath("/first");
        http.protocol("httpb").addressScheme("http").contextPath("/second");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.protocol("http");
            });
            assertTrue(failure.getMessage().contains("alias is ambiguous"));
        }
    }

    @Test
    public void tcpConnectorCannotDeclareHttpContexts() {
        // HTTP 上下文不适用于 TCP，不能接受配置后静默忽略。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf").contextPath("/orders");

        // 正式构建入口应立即报告不兼容的传输类型。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::buildContext);
        assertTrue(failure.getMessage().contains("Transport does not support HTTP contexts: tcp"));
    }

    @Test
    public void invalidAdditionalContextIsRejectedByPublicConfiguration() {
        // 含查询参数的字符串不是上下文路径。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ProtocolSettings protocol = configuration.connector("http").protocol("httpa");

        assertThrows(IllegalArgumentException.class, () -> protocol.addContextPath("/orders?tenant=1"));
    }
}

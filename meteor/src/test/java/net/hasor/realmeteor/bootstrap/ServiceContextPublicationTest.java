/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import java.util.List;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
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
            assertEquals("/", protocol.option("contextPath", null));
            assertEquals("[\"/orders\"]", protocol.option("contextPaths", null));
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
            assertEquals("/default", protocol.option("contextPath", null));
            assertEquals("[\"/orders\"]", protocol.option("contextPaths", null));
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
                ConnectorConfig updated = after.getSettings().getConnectorConfigs().iterator().next();
                ProtocolConfig changed = updated.protocol("httpa");
                assertEquals("/later", changed.option("contextPath", null));
                assertEquals("[\"/orders\",\"/inventory\"]", changed.option("contextPaths", null));
                assertEquals("/default", original.option("contextPath", null));
                assertEquals("[\"/orders\"]", original.option("contextPaths", null));
            }
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
                    .contextPathByPrefix("httpa", "/missing").contextPathByPrefix("httpb", "/shared") //
                    .contextPathByPrefix("HTTPA", "/inventory").register();

            // 被覆盖的未声明路径不再导致登记失败，原有服务协议范围保持不变。
            assertTrue(service.getBindProtocols().isEmpty());
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
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
    public void namedProtocolContextSelectionPublishesWhenAddressSchemesDiffer() throws Exception {
        // 同一协议分别通过普通和安全地址公开，并声明相同的可选目录。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .addressScheme("http").contextPath("/default").addContextPath("/orders");
        configuration.connector("http").protocol("httpa") //
                .addressScheme("https").contextPath("/default").addContextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            // 服务按协议名称选择一次目录，该选择应覆盖这个协议的全部端点挂载。
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .protocol("HTTPA").contextPathByPrefix("HTTPA", "/orders").register();

            // 服务保存统一协议名称，端点的公开地址 scheme 不参与上下文归属。
            assertEquals(Set.of("httpa"), service.getBindProtocols());
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            List<ConnectorConfig> endpoints = List.copyOf(context.getSettings().getConnectorConfigs());
            assertEquals("http", endpoints.get(0).protocol("httpa").scheme());
            assertEquals("https", endpoints.get(1).protocol("httpa").scheme());
        }
    }

    @Test
    public void protocolNameDoesNotSelectAnotherMountAddressScheme() throws Exception {
        // 第一个协议的地址前缀与第二个协议的名称相同。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").addressScheme("httpb").contextPath("/first");
        http.protocol("httpb").addressScheme("public").contextPath("/second");

        try (MetContext context = configuration.buildContext()) {
            // 选择 httpb 只匹配协议名称，不将 httpa 的地址前缀纳入范围。
            MetBindInfo<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    }) //
                    .protocol("httpb").contextPathByPrefix("httpb", "/second").register();

            // 登记不检查第一个协议的 /first 目录，协议名对应的发布仍成功。
            assertEquals(Set.of("httpb"), service.getBindProtocols());
            MetBindInfo<?> published = context.getServiceInfo(service.getBindID());
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
        }
    }

    @Test
    public void serviceProtocolRejectsAnAddressScheme() throws Exception {
        // 协议名与地址前缀不同，服务范围只接受协议名。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").addressScheme("public");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            // 地址前缀不能声明服务协议，也不会留下服务登记。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.protocol("public");
            });
            assertEquals("Service selected unknown protocol: public", failure.getMessage());
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void prefixContextSelectionRejectsAnAddressScheme() throws Exception {
        // 可选目录属于协议名，地址前缀不形成另一份上下文配置。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .addressScheme("public").contextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            // 前缀上下文入口立即报告未知协议。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.contextPathByPrefix("public", "/orders");
            });
            assertEquals("Service selected unknown protocol: public", failure.getMessage());
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void exactContextSelectionRejectsAnAddressScheme() throws Exception {
        // 精确上下文和前缀上下文采用同一个协议名称边界。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .addressScheme("public").contextPath("/orders");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            // 精确上下文入口不能通过地址前缀绕过协议选择。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.contextPathByExact("public", "/orders/echo");
            });
            assertEquals("Service selected unknown protocol: public", failure.getMessage());
            assertNull(context.getServiceInfo(Runnable.class));
        }
    }

    @Test
    public void sharedAddressSchemeDoesNotSelectAnyServiceProtocol() throws Exception {
        // 两个协议共用一个地址前缀，不影响各自独立的协议名称。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpa").addressScheme("http").contextPath("/first");
        http.protocol("httpb").addressScheme("http").contextPath("/second");

        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<Runnable> service = context.publisher() //
                    .rsfService(Runnable.class, () -> {
                    });

            // 共用前缀仍是未知协议，不再解释别名或判断歧义。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                service.protocol("http");
            });
            assertEquals("Service selected unknown protocol: http", failure.getMessage());
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

    @Test
    public void invalidRawContextOptionIsRejectedDuringBootstrap() {
        // option 允许保存原始配置，路径有效性由启动期的协议路由编译检查。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa").option("contextPath", "/orders?tenant=1");

        // 构建入口必须拒绝带查询参数的目录，不能推迟到首次收发请求。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::buildContext);
        assertTrue(failure.getMessage().contains("without authority, query or fragment"));
    }
}

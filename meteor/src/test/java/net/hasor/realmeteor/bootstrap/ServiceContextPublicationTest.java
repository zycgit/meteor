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
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.domain.ProtocolConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证公开配置及服务发布入口，不启动网络监听器。 */
public class ServiceContextPublicationTest {
    @MetService(name = " ", group = "\t", version = " ", serializeType = " ", clientTimeout = 0)
    public interface BlankAnnotatedService extends Runnable {
    }

    @Test
    public void sameInterfaceRegistrationsKeepIndependentConfiguration() throws Exception {
        // 只配置出站通道，先启动容器，再分别注册同一接口的两套配置。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Runnable> first = context.publisher().rsfService(Runnable.class) //
                    .group("first").name("task").version("1") //
                    .timeout(1200).serialize("Json").asMessage().register();
            MetBindInfo<Runnable> second = context.publisher().rsfService(Runnable.class) //
                    .group("second").name("task").version("2") //
                    .timeout(2400).serialize("Java").register();

            // 后注册的身份、调用选项和消息模式不会覆盖前一份注册。
            assertEquals("[first]task-1", first.getBindID());
            assertEquals(1200, first.getClientTimeout());
            assertEquals("Json", first.getSerializeType());
            assertTrue(first.isMessage());
            assertEquals("[second]task-2", second.getBindID());
            assertEquals(2400, second.getClientTimeout());
            assertEquals("Java", second.getSerializeType());
            assertFalse(second.isMessage());
            assertEquals(2, context.getServiceIDs().size());
        }
    }

    @Test
    public void sameInterfaceUsesTheDefaultsOfEachContext() throws Exception {
        // 同一接口可用于两个配置不同的实例，共享声明不共享最终注册值。
        MetConfiguration firstConfig = new MetConfiguration();
        firstConfig.setDefaultGroup("first").setDefaultVersion("1").setRequestTimeout(1500);
        firstConfig.connector("tcp").protocol("rsf");
        MetConfiguration secondConfig = new MetConfiguration();
        secondConfig.setDefaultGroup("second").setDefaultVersion("2").setRequestTimeout(2500);
        secondConfig.connector("tcp").protocol("rsf");

        try (MetContext first = firstConfig.buildContext(); //
             MetContext second = secondConfig.buildContext()) {
            first.start();
            second.start();
            MetBindInfo<Runnable> firstService = first.publisher().rsfService(Runnable.class).register();
            MetBindInfo<Runnable> secondService = second.publisher().rsfService(Runnable.class).register();

            // 各自按类型查询时仍使用自身配置的默认坐标。
            assertEquals("[first]java.lang.Runnable-1", firstService.getBindID());
            assertEquals(1500, firstService.getClientTimeout());
            assertEquals("[second]java.lang.Runnable-2", secondService.getBindID());
            assertEquals(2500, secondService.getClientTimeout());
            assertEquals(firstService.getBindID(), first.getServiceInfo(Runnable.class).getBindID());
            assertEquals(secondService.getBindID(), second.getServiceInfo(Runnable.class).getBindID());
        }
    }

    @Test
    public void requestTimeoutTakesPrecedenceOverTheGlobalDefault() throws Exception {
        // 未声明接口超时时，优先使用当前容器的请求超时。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setRequestTimeout(1500).setDefaultTimeout(7000);
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class).register();
            assertEquals(1500, service.getClientTimeout());
        }
    }

    @Test
    public void zeroRequestTimeoutFallsBackToTheGlobalDefault() throws Exception {
        // 零表示没有指定请求超时，最终值从全局默认超时取得。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setRequestTimeout(0).setDefaultTimeout(7000);
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class).register();
            assertEquals(7000, service.getClientTimeout());
        }
    }

    @Test
    public void negativeRequestTimeoutFallsBackToTheGlobalDefault() throws Exception {
        // 负值同样表示未指定请求超时，不能保留模型原有的硬编码超时。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setRequestTimeout(-1).setDefaultTimeout(7000);
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class).register();
            assertEquals(7000, service.getClientTimeout());
        }
    }

    @Test
    public void blankAnnotationValuesUseTheContextsDefaults() throws Exception {
        // 空白声明按未指定处理，坐标和调用选项采用当前容器的默认值。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultGroup("configured").setDefaultVersion("7").setRequestTimeout(2300);
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<BlankAnnotatedService> service = context.publisher() //
                    .rsfService(BlankAnnotatedService.class).register();

            // 按类型查询沿用同一份合并规则，编码器仍留给协议端点决定。
            assertEquals("configured", service.getBindGroup());
            assertEquals(BlankAnnotatedService.class.getName(), service.getBindName());
            assertEquals("7", service.getBindVersion());
            assertEquals(2300, service.getClientTimeout());
            assertNull(service.getSerializeType());
            assertEquals(service.getBindID(), context.getServiceInfo(BlankAnnotatedService.class).getBindID());
        }
    }

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

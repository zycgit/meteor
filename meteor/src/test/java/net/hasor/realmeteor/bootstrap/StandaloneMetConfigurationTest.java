/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅内核运行时即可通过公开配置构建上下文及调用内置协议。 */
public class StandaloneMetConfigurationTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void defaultsUseAnAvailableCoderWithoutOpeningAListener() throws Exception {
        // 未指定可选插件和监听地址，默认配置只构建内核能力。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            // 默认应用、序列化和 RSF 计划可用，但没有插件或已启动的网络资源。
            assertEquals("application", context.getApplicationName());
            assertEquals("application", context.getSettings().getApplicationName());
            var endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertEquals("Java", endpoint.protocol("rsf").option("serializeType", null));
            assertTrue(context.getSerializeCoder("Java") instanceof JavaSerializeCoder);
            assertNull(context.getSerializeCoder("Hprose"));
            String hproseClass = "net/hasor/meteor/protocol/hprose/session/HproseProtocol.class";
            assertNull(this.getClass().getClassLoader().getResource(hproseClass));
            assertNull(context.bindAddress("rsf"));
            assertEquals(Collections.singleton("rsf"), context.runProtocols());
            assertFalse(context.isOnline());
        }
    }

    @Test
    public void defaultSerializationCompletesRealRpcWithoutPlugins() throws Exception {
        // 分别配置 RSF 的 TCP 服务端监听和客户端出站连接。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            // 两个上下文分别发布与订阅，通过 TCP 验证默认序列化器。
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 使用显式服务端地址走真实 TCP，客户端自身不打开监听。
            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.bindAddress("rsf"));
        }
    }

    @Test
    public void absentPluginIsRejectedRatherThanSubstitutedByTheBuiltinProtocol() {
        // 只提供内核依赖时，显式选择未安装的 Hprose 协议。
        MetConfiguration config = new MetConfiguration();
        config.connector("http").protocol("hprose");

        // 构建直接指出未知协议，不能静默替换成内置 RSF。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::buildContext);
        assertTrue(failure.getMessage().contains("Unknown protocol"));
        assertTrue(failure.getMessage().contains("hprose"));
    }

    @Test
    public void builtSettingsDoNotRediscoverSpiThroughAnotherThreadClassLoader() throws Exception {
        // 构建时准备 SPI，随后切换到禁止扫描资源的线程类加载器。
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader unavailable = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                throw new AssertionError("A built configuration must use its prepared SPI snapshot: " + name);
            }
        };
        try (MetContext context = source.buildContext()) {
            try {
                // 读取设置只使用构建时快照，不再次通过线程类加载器扫描 SPI。
                thread.setContextClassLoader(unavailable);
                assertEquals(Collections.singleton("rsf"), context.getSettings().getProtocols());
                assertEquals("rsf", context.getSettings().getDefaultProtocol());
                assertEquals(0, context.getSettings().getBindAddressSet("rsf").getPort());
                assertEquals(1, context.getSettings().getConnectorConfigs().size());
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    @Test
    public void settingsExposeEveryMountAndRejectAmbiguousSingleAddressLookup() throws Exception {
        // 同一逻辑协议配置两个监听端点，第二个挂载使用独立公开地址别名。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsfs");
        configuration.connector("tcp").bind("127.0.0.1", 2101).protocol("rsf");
        configuration.connector("tcp").bind("127.0.0.1", 2102) //
                .option("tlsEnabled", true) //
                .protocol("rsf").addressScheme("rsfs");

        try (MetContext context = configuration.buildContext()) {
            // 默认别名解析为逻辑协议；复数查询保留两个挂载，端点和别名均可精确选择。
            MetSettings settings = context.getSettings();
            List<InterAddress> addresses = settings.getBindAddressSets("rsf");
            assertEquals("rsf", settings.getDefaultProtocol());
            assertEquals(List.of("rsf", "rsfs"), addresses.stream().map(InterAddress::getSchema).toList());
            assertEquals(2101, settings.getBindAddressSet("tcp-0", "rsf").getPort());
            assertEquals(2102, settings.getBindAddressSet("tcp-1", "rsf").getPort());
            assertEquals(2102, settings.getBindAddressSet("tcp-1", "rsfs").getPort());
            assertEquals(2102, settings.getBindAddressSet("rsfs").getPort());
            ConnectorConfig secureEndpoint = List.copyOf(settings.getConnectorConfigs()).get(1);
            String secureMount = secureEndpoint.protocols().iterator().next().mountId();
            assertEquals(2102, settings.getBindAddressSet("tcp-1", secureMount).getPort());

            // 旧单值入口不能按配置顺序猜测端点。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                settings.getBindAddressSet("rsf");
            });
            assertTrue(failure.getMessage().contains("Multiple"));
            assertTrue(failure.getMessage().contains("rsf"));
        }
    }

    @Test
    public void sameProtocolFactoryCanListenOnPlainAndTlsEndpoints() throws Exception {
        // 同一个 RSF 实现挂载到一个明文和两个 TLS 端点，TLS 可沿用默认别名或显式使用 rsfs。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .protocol("rsf");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .protocol("rsf").addressScheme("rsfs");

        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 逻辑协议返回全部运行地址；端点名称和地址别名都能精确选择其中一个。
            List<InterAddress> addresses = context.bindAddresses("rsf");
            InterAddress plain = context.bindAddress("tcp-0", "rsf");
            InterAddress secureDefault = context.bindAddress("tcp-1", "rsf");
            InterAddress secureAlias = context.bindAddress("tcp-2", "rsf");
            assertEquals(3, addresses.size());
            assertEquals(List.of("rsf", "rsf", "rsfs"), addresses.stream().map(InterAddress::getSchema).toList());
            assertEquals(plain, addresses.get(0));
            assertEquals(secureDefault, addresses.get(1));
            assertEquals(secureAlias, addresses.get(2));
            assertEquals("rsf", secureDefault.getSchema());
            assertEquals(secureAlias, context.bindAddress("tcp-2", "rsfs"));
            ConnectorConfig aliasEndpoint = List.copyOf(context.getSettings().getConnectorConfigs()).get(2);
            String aliasMount = aliasEndpoint.protocols().iterator().next().mountId();
            assertEquals(secureAlias, context.bindAddress("tcp-2", aliasMount));
            assertEquals(secureAlias, context.bindAddress("rsfs"));
            assertNotEquals(plain.getPort(), secureDefault.getPort());
            assertNotEquals(plain.getPort(), secureAlias.getPort());
            assertNotEquals(secureDefault.getPort(), secureAlias.getPort());

            // 旧单值入口遇到两个 RSF 挂载时明确报告歧义。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                context.bindAddress("rsf");
            });
            assertTrue(failure.getMessage().contains("Ambiguous"));
            assertTrue(failure.getMessage().contains("rsf"));
        }
    }

    @Test
    public void logicalProtocolNameTakesPrecedenceOverAnotherMountAddressScheme() throws Exception {
        // 首个挂载的公开别名故意与第二个挂载的逻辑协议名相同。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("wireb");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("wirea").addressScheme("wireb");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("wireb").addressScheme("wireb-public");

        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 逻辑协议名全局优先，同名别名只能通过精确 mount 身份查询。
            InterAddress logical = context.bindAddress("wireb");
            ConnectorConfig aliasEndpoint = List.copyOf(context.getSettings().getConnectorConfigs()).get(0);
            String aliasMount = aliasEndpoint.protocols().iterator().next().mountId();
            InterAddress alias = context.bindAddress("tcp-0", aliasMount);
            assertEquals("wireb", context.getDefaultProtocol());
            assertEquals("wireb-public", logical.getSchema());
            assertEquals(context.bindAddress("tcp-1", "wireb"), logical);
            assertEquals("wireb", alias.getSchema());
            assertNotEquals(alias.getPort(), logical.getPort());
        }
    }

    @Test
    public void outboundLogicalNameStillTakesPrecedenceOverBoundAddressScheme() throws Exception {
        // 已监听挂载的公开别名与另一个仅出站挂载的逻辑协议名相同。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("wireb");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("wirea").addressScheme("wireb");
        configuration.connector("tcp").protocol("wireb").addressScheme("wireb-public");

        try (MetContext context = configuration.buildContext()) {
            context.start();

            // selector 的身份由全局协议空间决定，不能在过滤监听端点后退化为别名。
            MetSettings settings = context.getSettings();
            assertTrue(settings.getBindAddressSets("wireb").isEmpty());
            assertNull(settings.getBindAddressSet("tcp-0", "wireb"));
            ConnectorConfig boundEndpoint = List.copyOf(settings.getConnectorConfigs()).get(0);
            String exactMount = boundEndpoint.protocols().iterator().next().mountId();
            assertNotNull(settings.getBindAddressSet("tcp-0", exactMount));
        }
    }

    @Test
    public void applicationNameBelongsToEachContextConfigurationSnapshot() throws Exception {
        // 使用同一配置先构建 orders，再修改名称构建 inventory。
        MetConfiguration source = new MetConfiguration().setApplicationName("orders");
        try (MetContext orders = source.buildContext()) {
            source.setApplicationName("inventory");
            try (MetContext inventory = source.buildContext()) {
                // 已构建上下文及其设置保留原应用名称，新上下文使用修改后的值。
                assertEquals("orders", orders.getApplicationName());
                assertEquals("orders", orders.getSettings().getApplicationName());
                assertEquals("inventory", inventory.getApplicationName());
            }
            assertEquals("orders", orders.getApplicationName());
        }
    }

    @Test
    public void blankApplicationNameUsesTheDefaultInBuiltContext() throws Exception {
        // 空白应用名称在构建时使用默认应用名。
        MetConfiguration configuration = new MetConfiguration().setApplicationName(" ");
        try (MetContext context = configuration.buildContext()) {
            assertEquals("application", context.getApplicationName());
        }
    }

    @Test
    public void nullApplicationNameUsesDefaultWithoutChangingAnExistingContext() throws Exception {
        // 将配置名称清空会恢复默认值，但不影响已经构建的命名上下文。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("orders");
        try (MetContext context = configuration.buildContext()) {
            configuration.setApplicationName(null);
            assertEquals("application", configuration.getApplicationName());
            assertEquals("orders", context.getApplicationName());
        }
    }

    @Test
    public void editingRequestOptionsDoesNotMutateTheBuilderOrAnotherContext() throws Exception {
        // 同一配置创建两个带有初始请求选项的上下文。
        MetConfiguration source = new MetConfiguration();
        source.setRequestOption("trace", "initial");
        try (MetContext first = source.buildContext(); //
             MetContext second = source.buildContext()) {
            // 修改第一个上下文的请求选项不会串改配置和第二个上下文。
            first.getSettings().getRequestOptions().addOption("trace", "first");
            assertEquals("initial", source.getRequestOptions().getOption("trace"));
            assertEquals("initial", second.getSettings().getRequestOptions().getOption("trace"));

            // 之后修改原配置也不影响两个上下文各自的请求选项。
            source.setRequestOption("trace", "later");
            assertEquals("first", first.getSettings().getRequestOptions().getOption("trace"));
            assertEquals("initial", second.getSettings().getRequestOptions().getOption("trace"));
        }
    }

    @Test
    public void removingResponseOptionDoesNotMutateTheBuilderOrAnotherContext() throws Exception {
        // 响应选项同样归各上下文所有，删除只对目标上下文生效。
        MetConfiguration source = new MetConfiguration();
        source.setResponseOption("tag", "initial");
        try (MetContext first = source.buildContext(); //
             MetContext second = source.buildContext()) {
            first.getSettings().getResponseOptions().removeOption("tag");
            assertNull(first.getSettings().getResponseOptions().getOption("tag"));
            assertEquals("initial", source.getResponseOptions().getOption("tag"));
            assertEquals("initial", second.getSettings().getResponseOptions().getOption("tag"));
        }
    }

    @Test
    public void protocolPreparationMergesConnectorDefaultsAndProtocolOverrides() throws Exception {
        // 两个协议共享端点参数，首个协议覆盖限制、序列化器和上下文路径。
        MetConfiguration source = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = source.connector("http");
        http.option("workerThread", 1).option("limit", 10).option("serializeType", "Json");
        http.protocol("httpa") //
                .option("limit", 20).option("serializeType", "Java").contextPath("/first///");
        http.protocol("httpb");

        try (MetContext context = source.buildContext()) {
            // 协议覆盖项只影响自身，未覆盖项继承端点默认值，路径统一归一化。
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig first = endpoint.protocol("httpa");
            ProtocolConfig second = endpoint.protocol("httpb");
            assertEquals("20", first.option("limit", null));
            assertEquals("10", second.option("limit", null));
            assertEquals("10", endpoint.options().get("limit"));
            assertEquals("1", first.option("workerThread", null));
            assertEquals("Java", first.option("serializeType", null));
            assertEquals("Json", second.option("serializeType", null));
            assertEquals("/first", first.option("contextPath", null));
            assertEquals("/httpb", second.option("contextPath", null));
        }
    }

    @Test
    public void transportSecurityMaterialDoesNotEnterProtocolOptions() throws Exception {
        // TLS 配置属于连接端点；协议只能看到端点最终是否启用安全传输。
        MetConfiguration source = new MetConfiguration();
        source.connector("http") //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "server.p12") //
                .option("tlsKeyStorePassword", "secret") //
                .option("tlsProtocols", "TLSv1.2") //
                .protocol("httpa");

        try (MetContext context = source.buildContext()) {
            // 证书材料留在连接器配置中，不复制给协议挂载和路由。
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig protocol = endpoint.protocol("httpa");
            assertEquals("server.p12", endpoint.options().get("tlsKeyStore"));
            assertEquals("secret", endpoint.options().get("tlsKeyStorePassword"));
            assertEquals("true", protocol.option("tlsEnabled", null));
            assertNull(protocol.option("tlsKeyStore", null));
            assertNull(protocol.option("tlsKeyStorePassword", null));
            assertNull(protocol.option("tlsProtocols", null));
        }
    }

    @Test
    public void protocolCannotOverrideTransportSecurityOptions() {
        // 安全传输参数必须通过 ConnectorSettings 配置，协议挂载不能形成另一份冲突状态。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ProtocolSettings protocol = configuration.connector("http").protocol("httpa");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            protocol.option("tlsEnabled", true);
        });
        assertEquals("Transport security option belongs to connector: tlsEnabled", failure.getMessage());
    }

    @Test
    public void editingProtocolConfigurationLeavesPreviousContextAndOtherProtocolUnchanged() throws Exception {
        // 同一 HTTP 端点挂载两个协议，构建后继续编辑第一个协议。
        MetConfiguration source = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = source.connector("http");
        http.option("limit", 10);
        MetConfiguration.ProtocolSettings editable = http.protocol("httpa");
        editable.option("limit", 20).contextPath("/first///");
        http.protocol("httpb");

        try (MetContext before = source.buildContext()) {
            ConnectorConfig original = before.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig first = original.protocol("httpa");
            ProtocolConfig second = original.protocol("httpb");

            // 再次构建得到新快照，已有上下文以及另一协议都保持自己的原值。
            editable.option("limit", 30).contextPath("/later/");
            try (MetContext after = source.buildContext()) {
                ConnectorConfig updated = after.getSettings().getConnectorConfigs().iterator().next();
                ProtocolConfig changed = updated.protocol("httpa");
                assertNotSame(first, changed);
                assertEquals("30", changed.option("limit", null));
                assertEquals("/later", changed.option("contextPath", null));
                assertEquals("20", first.option("limit", null));
                assertEquals("/first", first.option("contextPath", null));
                assertEquals("10", second.option("limit", null));
                assertEquals("/httpb", second.option("contextPath", null));
                assertEquals("10", updated.protocol("httpb").option("limit", null));
                assertEquals("/httpb", updated.protocol("httpb").option("contextPath", null));
            }
        }
    }
}

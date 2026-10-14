/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.bootstrap;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import net.hasor.meteor.bootstrap.MetConfiguration;
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
            // 默认应用和 Java 序列化可用，但没有插件或已启动的网络资源。
            assertEquals("application", context.getApplicationName());
            assertEquals("application", context.getSettings().getApplicationName());
            var endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertEquals("Java", endpoint.protocol("rsf").option("serializeType", null));
            assertTrue(context.getSerializeCoder("Java") instanceof JavaSerializeCoder);
            assertNull(context.getSerializeCoder("Hprose"));
            String hproseClass = "net/hasor/meteor/protocol/hprose/HproseProtocol.class";
            assertNull(this.getClass().getClassLoader().getResource(hproseClass));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
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
            assertEquals("10", endpoint.option("limit", null));
            assertEquals("1", first.option("workerThread", null));
            assertEquals("Java", first.option("serializeType", null));
            assertEquals("Json", second.option("serializeType", null));
            assertEquals("/first", first.option("contextPath", null));
            assertEquals("/httpb", second.option("contextPath", null));
        }
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

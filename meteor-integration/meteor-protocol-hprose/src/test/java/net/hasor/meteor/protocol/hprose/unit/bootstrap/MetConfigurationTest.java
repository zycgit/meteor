/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit.bootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.protocol.hprose.scenario.bootstrap.BootstrapTestSupport;
import net.hasor.meteor.protocol.hprose.scenario.example.TextService;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetConfigurationTest extends BootstrapTestSupport {

    @Test
    public void typedConfigurationConstructsContextComponents() throws Exception {
        // 先配置服务、网络和请求额度，再补充调度与地址缓存参数。
        MetConfiguration settings = new MetConfiguration();
        settings.setBindAddress("127.0.0.1");
        settings.setUnitName("zone_a");
        settings.setDataHome(Paths.get("custom-rsf-data"));
        settings.setAutomaticOnline(false);
        settings.setDefaultGroup("Example");
        settings.setDefaultVersion("2.0");
        settings.setDefaultTimeout(1234);
        settings.setRequestTimeout(2345);
        settings.setMaximumRequest(17);
        settings.setSendLimitPolicy(SendLimitPolicy.WaitSecond);
        settings.setConnectTimeout(3456);

        // 调度线程池和地址缓存也从同一份类型化配置构建。
        settings.setQueueMinPoolSize(1);
        settings.setQueueMaxPoolSize(2);
        settings.setQueueMaxSize(23);
        settings.setQueueKeepAliveTime(60);
        settings.setLocalDiskCache(false);
        settings.setRefreshCacheTime(800);
        settings.setInvalidWaitTime(45000);
        settings.setDiskCacheTimeInterval(7200000);
        settings.setResponseOption("server.tag", "first");
        settings.setResponseOption("server.tag", "second");
        settings.setRequestOption("client.tag", "configured");

        // 构建只装配组件，不开启网络；运行配置采用最终设置的选项值。
        Object configuration = settings;
        assertFalse(configuration instanceof Settings);
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        try (MetContext context = settings.buildContext(loader)) {
            MetSettings runtimeSettings = context.getSettings();
            assertFalse(context.isOnline());
            assertNull(context.bindAddress("rsf"));
            assertSame(loader, context.getClassLoader());
            assertTrue(context.getSerializeCoder("jSoN") instanceof JsonSerializeCoder);
            assertEquals("second", runtimeSettings.getResponseOptions().getOption("server.tag"));
            assertEquals("configured", runtimeSettings.getRequestOptions().getOption("client.tag"));
            assertEquals(Paths.get("custom-rsf-data"), runtimeSettings.getDataHome());
            assertEquals("zone_a", runtimeSettings.getBindAddressSet("rsf").getFormUnit());
            assertEquals(17, runtimeSettings.getMaximumRequest());
            assertEquals(23, runtimeSettings.getQueueMaxSize());
        }
    }

    @Test
    public void publishedServiceInheritsConfiguredDefaults() throws Exception {
        // 服务注册前给定默认分组、版本和调用超时。
        MetConfiguration settings = new MetConfiguration();
        settings.setDefaultGroup("Example");
        settings.setDefaultVersion("2.0");
        settings.setDefaultTimeout(1234);
        try (MetContext context = settings.buildContext()) {
            // 服务默认值继承配置，仍可在尚未启动网络时本地调用。
            MetBindInfo<TextService> service = context.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .register();
            assertEquals("Example", service.getBindGroup());
            assertEquals("2.0", service.getBindVersion());
            assertEquals(1234, service.getClientTimeout());
            assertNull(service.getSerializeType());
            assertEquals("local", context.getRsfClient().getRemote(service).echo("local"));
        }
    }

    @Test
    public void protocolDefaultFindsTheCaseInsensitiveSpiCoder() throws Exception {
        // 协议默认序列化器用混合大小写名称选择 SPI 实现。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp") //
                .protocol("rsf") //
                .option("serializeType", "jSoN");
        try (MetContext context = configuration.buildContext()) {
            // 服务不必复制协议默认值，查询时不同大小写指向同一个编码器。
            MetBindInfo<TextService> service = context.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .register();
            assertNull(service.getSerializeType());
            assertEquals(JsonSerializeCoder.class, context.getSerializeCoder("jSoN").getClass());
            assertSame(context.getSerializeCoder("Json"), context.getSerializeCoder("jSoN"));
        }
    }

    @Test
    public void serviceSerializerOverridesTheProtocolDefault() throws Exception {
        // 协议默认采用 JSON，但当前服务显式覆盖为 Java 序列化。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp") //
                .protocol("rsf") //
                .option("serializeType", "jSoN");
        try (MetContext context = configuration.buildContext()) {
            // 注册完成后，服务声明应保留它自己的序列化选择。
            var overrideBuilder = context.publisher().rsfService(TextService.class, value -> value);
            overrideBuilder.name("override");
            overrideBuilder.serialize("Java");
            MetBindInfo<TextService> override = overrideBuilder.register();
            assertEquals("Java", override.getSerializeType());
        }
    }

    @Test
    public void reusedConfigurationBuildsIndependentTypedSettings() throws Exception {
        // 第一份上下文保存公共选项、连接器和协议的初始配置。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultGroup("First");
        configuration.setConnectTimeout(1000);
        configuration.setResponseOption("tag", "first");
        MetConfiguration.ConnectorSettings tcp = configuration.connector("tcp");
        tcp.bind("127.0.0.1", 23001);
        tcp.option("custom.nested", "first");
        MetConfiguration.ProtocolSettings protocol = tcp.protocol("rsf").option("serializeType", "Json");
        try (MetContext first = configuration.buildContext()) {
            Object runtimeSettings = first.getSettings();
            assertFalse(runtimeSettings instanceof Settings);

            // 修改源配置不影响已创建的上下文，只作用于下一次构建。
            configuration.setDefaultGroup("Second");
            configuration.setConnectTimeout(2000);
            configuration.setResponseOption("tag", "second");
            protocol.option("serializeType", "Java");
            tcp.bind("127.0.0.1", 23002).option("custom.nested", "second");
            try (MetContext second = configuration.buildContext()) {
                // 两份快照分别对应各自构建时的配置，不能共享可变设置。
                MetSettings firstSettings = first.getSettings();
                MetSettings secondSettings = second.getSettings();
                assertNotSame(firstSettings, secondSettings);
                assertEquals("First", firstSettings.getDefaultGroup());
                assertEquals("Second", secondSettings.getDefaultGroup());
                assertEquals("first", firstSettings.getResponseOptions().getOption("tag"));
                assertEquals("second", secondSettings.getResponseOptions().getOption("tag"));
                ConnectorConfig firstConnector = firstSettings.getConnectorConfigs().iterator().next();
                ConnectorConfig secondConnector = secondSettings.getConnectorConfigs().iterator().next();
                assertEquals("Json", firstConnector.protocol("rsf").option("serializeType", null));
                assertEquals("Java", secondConnector.protocol("rsf").option("serializeType", null));
                assertEquals(23001, firstConnector.address().getPort());
                assertEquals(23002, secondConnector.address().getPort());
                assertEquals(1000, firstConnector.connectTimeout());
                assertEquals(2000, secondConnector.connectTimeout());
                assertEquals("first", firstConnector.option("custom.nested", null));
                assertEquals("second", secondConnector.option("custom.nested", null));
                assertNull(first.bindAddress("rsf"));
                assertNull(second.bindAddress("rsf"));

                // 上下文构建后继续添加接入点或请求头，不得污染已有快照。
                configuration.connector("http").protocol("hprose");
                configuration.getRequestOptions().addOption("later", "edited");
                assertEquals(1, firstSettings.getConnectorConfigs().size());
                assertEquals(1, secondSettings.getConnectorConfigs().size());
                assertNull(firstSettings.getRequestOptions().getOption("later"));
                assertNull(secondSettings.getRequestOptions().getOption("later"));
            }
        }
    }

    @Test
    public void unknownDefaultProtocolFailsBuild() throws Exception {
        // 默认协议必须能由 SPI 发现，未知名称在构建时拒绝。
        MetConfiguration settings = new MetConfiguration().setDefaultProtocol("missing");
        assertThrows(IllegalArgumentException.class, settings::buildContext);
    }

    @Test
    public void emptyTransportNameIsRejected() throws Exception {
        // 空传输名称不能创建连接器配置。
        MetConfiguration settings = new MetConfiguration();
        assertThrows(IllegalArgumentException.class, () -> settings.connector(""));
    }

    @Test
    public void buildUsesTheThreadClassLoader() throws Exception {
        // 临时设置当前线程加载器，验证无参构建遵循线程的发现边界。
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        try {
            thread.setContextClassLoader(loader);
            try (MetContext context = new MetConfiguration().buildContext()) {
                assertSame(loader, context.getClassLoader());
            }

        } finally {
            // 恢复线程状态，防止影响同一工作线程上的后续测试。
            thread.setContextClassLoader(original);
        }
    }

    @Test
    public void buildFallsBackWhenThreadClassLoaderIsAbsent() throws Exception {
        // 保存原始加载器，模拟没有线程上下文加载器的环境。
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            // 没有线程加载器时退回框架加载器，结束后恢复测试线程的原始值。
            thread.setContextClassLoader(null);
            try (MetContext context = new MetConfiguration().buildContext()) {
                assertSame(MetConfiguration.class.getClassLoader(), context.getClassLoader());
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    public void defaultsDescribeConfigurationWithoutStartingResources() throws Exception {
        // 仅读取默认配置，不创建上下文、监听器或线程池。
        MetSettings settings = new MetConfiguration();
        assertEquals("default", settings.getDefaultGroup());
        assertEquals("1.0.0", settings.getDefaultVersion());
        assertEquals(6000, settings.getDefaultTimeout());
        assertEquals(200, settings.getMaximumRequest());
        assertEquals(Paths.get("meteor-data"), settings.getDataHome());
        assertEquals(Collections.singleton("rsf"), settings.getProtocols());
        assertEquals("rsf://127.0.0.1:2181/default", settings.getBindAddressSet("rsf").toHostSchema());
        assertFalse(settings.isLocalDiskCache());
        assertTrue(settings.isAutomaticOnline());
    }

    @Test
    public void snapshotsIsolateEndpointProtocolAndCommonOptions() throws Exception {
        // 先保存 HTTP 端口、协议挂载路径及公共选项的初始快照。
        MetConfiguration source = new MetConfiguration().setResponseOption("tag", "first");
        MetConfiguration.ConnectorSettings http = source.connector("HTTP");
        http.bind("127.0.0.1", 8080);
        http.option("connectTimeout", 1200);
        MetConfiguration.ProtocolSettings hprose = http.protocol("HpRoSe").contextPath("/first");
        try (MetContext first = source.buildContext()) {
            // 下一次构建读取新的端口、路径及选项，原上下文保持原快照。
            http.bind("127.0.0.1", 8081).option("connectTimeout", 2400);
            hprose.contextPath("/second");
            source.setUnitName("new_unit").setResponseOption("tag", "second");
            try (MetContext second = source.buildContext()) {
                // 新旧快照分别保留对应的端口、超时、路径及单元信息。
                MetSettings firstSettings = first.getSettings();
                MetSettings secondSettings = second.getSettings();
                ConnectorConfig oldEndpoint = firstSettings.getConnectorConfigs().iterator().next();
                ConnectorConfig newEndpoint = secondSettings.getConnectorConfigs().iterator().next();
                assertEquals(8080, oldEndpoint.address().getPort());
                assertEquals(8081, newEndpoint.address().getPort());
                assertEquals(1200, oldEndpoint.connectTimeout());
                assertEquals(2400, newEndpoint.connectTimeout());
                assertEquals("/first", oldEndpoint.protocol("hprose").option("contextPath", null));
                assertEquals("/second", newEndpoint.protocol("hprose").option("contextPath", null));
                assertEquals("first", firstSettings.getResponseOptions().getOption("tag"));
                assertEquals("second", secondSettings.getResponseOptions().getOption("tag"));
                assertEquals("default", oldEndpoint.address().getFormUnit());
                assertEquals("new_unit", newEndpoint.address().getFormUnit());
                assertEquals(Collections.singleton("hprose"), firstSettings.getProtocols());
                assertEquals("hprose", first.getDefaultProtocol());
            }
        }
    }

    @Test
    public void failedBuildLeavesExistingContextUnchangedAndAllowsCorrection() throws Exception {
        // 已有上下文先正常构建，随后把源配置改为未知协议。
        MetConfiguration source = new MetConfiguration();
        try (MetContext first = source.buildContext()) {
            source.setDefaultGroup("changed").setDefaultProtocol("missing");
            assertThrows(IllegalArgumentException.class, source::buildContext);
            assertEquals("default", first.getSettings().getDefaultGroup());

            // 修正源配置后可以再次构建，失败尝试没有污染原来的上下文。
            source.setDefaultProtocol("RsF");
            try (MetContext second = source.buildContext()) {
                assertEquals("changed", second.getSettings().getDefaultGroup());
                assertEquals("rsf", second.getDefaultProtocol());
            }
        }
    }

    @Test
    public void transportMismatchFailsBeforeOpeningThePort() throws Exception {
        // 先占用端口，再配置不匹配的传输和协议，确保校验早于 bind。
        try (ServerSocket occupied = new ServerSocket(0)) {
            MetConfiguration mismatch = new MetConfiguration();
            mismatch.connector("tcp") //
                    .bind("127.0.0.1", occupied.getLocalPort()) //
                    .protocol("hprose");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, mismatch::buildContext);
            assertTrue(failure.getMessage().contains("requires transport"));
        }
    }

    @Test
    public void unknownProtocolFailsBuild() throws Exception {
        // 连接器已选择有效传输，但挂载的协议名称不存在。
        MetConfiguration missing = new MetConfiguration();
        missing.connector("tcp").protocol("missing");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, missing::buildContext);
        assertTrue(failure.getMessage().contains("Unknown protocol"));
    }

    @Test
    public void emptyEndpointFailsBuild() throws Exception {
        // 仅选择传输而没有挂载协议的端点不能参与组装。
        MetConfiguration empty = new MetConfiguration();
        empty.connector("tcp");
        assertThrows(IllegalArgumentException.class, empty::buildContext);
    }

    @Test
    public void duplicateProtocolFailsBuild() throws Exception {
        // 同一协议名称不同大小写仍表示重复挂载。
        MetConfiguration duplicate = new MetConfiguration();
        duplicate.connector("tcp").protocol("rsf");
        duplicate.connector("tcp").protocol("RSF");
        assertThrows(IllegalArgumentException.class, duplicate::buildContext);
    }

    @Test
    public void conflictingHttpMountPathsFailBuild() throws Exception {
        // 不同协议也不能占用规范化后相同的 HTTP 路径。
        MetConfiguration conflictingPaths = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = conflictingPaths.connector("http");
        http.protocol("hprosea").contextPath("/same/");
        http.protocol("hproseb").contextPath("/same");
        var conflict = assertThrows(IllegalArgumentException.class, conflictingPaths::buildContext);
        assertTrue(conflict.getMessage().contains("Duplicate HTTP directory mount"));
    }

    @Test
    public void transportOptionCannotOverrideName() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 name。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("name", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideName() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 name。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("name", "other"));
    }

    @Test
    public void transportOptionCannotOverrideListenType() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 listenType。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("listenType", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideListenType() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 listenType。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("listenType", "other"));
    }

    @Test
    public void transportOptionCannotOverrideFactory() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 factory。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("factory", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideFactory() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 factory。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("factory", "other"));
    }

    @Test
    public void transportOptionCannotOverrideScheme() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 scheme。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("scheme", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideScheme() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 scheme。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("scheme", "other"));
    }

    @Test
    public void transportOptionCannotOverrideProtocol() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 protocol。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("protocol", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideProtocol() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 protocol。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("protocol", "other"));
    }

    @Test
    public void transportOptionCannotOverrideLocalPort() {
        // 传输选项不能覆盖由正式配置入口或 SPI 确定的 localPort。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.option("localPort", "other"));
    }

    @Test
    public void protocolOptionCannotOverrideLocalPort() {
        // 协议选项不能覆盖由正式配置入口或 SPI 确定的 localPort。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> rsf.option("localPort", "other"));
    }

    @Test
    public void negativePortIsRejected() {
        // 监听端口不能为负数。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", -1));
    }

    @Test
    public void outOfRangePortIsRejected() {
        // 监听端口不能超出无符号 16 位范围。
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", 65536));
    }

    @Test
    public void contextClassLoaderControlsProtocolDiscovery() throws Exception {
        // 指定类加载器隐藏协议 SPI 声明，构建不能退回其它加载器发现协议。
        MetConfiguration config = new MetConfiguration();
        config.connector("http").protocol("hprose");
        String protocolSpi = "META-INF/services/net.hasor.meteor.connector.protocol.ProtocolFactory";
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (StringUtils.equals(name, protocolSpi)) {
                    return Collections.emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
        // 使用该加载器构建时，已安装的 Hprose 插件也必须表现为不可发现。
        var failure = assertThrows(IllegalArgumentException.class, () -> config.buildContext(loader));
        assertTrue(failure.getMessage().contains("Unknown protocol"));
    }
}

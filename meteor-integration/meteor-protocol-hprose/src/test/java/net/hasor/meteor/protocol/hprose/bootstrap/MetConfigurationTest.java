/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
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
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetConfigurationTest extends BootstrapTestSupport {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void typedConfigurationDrivesServiceDefaultsAndComponentConstruction() throws Exception {
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
        Object configuration = settings;
        assertFalse(configuration instanceof Settings);
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        try (MetContext context = settings.buildContext(loader)) {
            assertFalse(context.isOnline());
            assertNull(context.bindAddress("rsf"));
            assertSame(loader, context.getClassLoader());
            assertTrue(context.getSerializeCoder("jSoN") instanceof JsonSerializeCoder);
            assertEquals("second", context.getSettings().getResponseOptions().getOption("server.tag"));
            assertEquals("configured", context.getSettings().getRequestOptions().getOption("client.tag"));
            assertEquals(Paths.get("custom-rsf-data"), context.getSettings().getDataHome());
            assertEquals("zone_a", context.getSettings().getBindAddressSet("rsf").getFormUnit());
            assertEquals(17, context.getSettings().getMaximumRequest());
            assertEquals(23, context.getSettings().getQueueMaxSize());

            // 服务默认值继承配置，仍可在尚未启动网络时本地调用。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();
            assertEquals("Example", service.getBindGroup());
            assertEquals("2.0", service.getBindVersion());
            assertEquals(1234, service.getClientTimeout());
            assertNull(service.getSerializeType());
            assertEquals("local", context.getRsfClient().getRemote(service).echo("local"));
        }
    }

    @Test
    public void serializeTypeSelectsSpiCoderAndCanBeOverriddenPerService() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf").option("serializeType", "jSoN");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();
            assertNull(service.getSerializeType());
            assertEquals(JsonSerializeCoder.class, context.getSerializeCoder("jSoN").getClass());
            assertSame(context.getSerializeCoder("Json"), context.getSerializeCoder("jSoN"));
            var overrideBuilder = context.publisher().rsfService(Echo.class, value -> value);
            overrideBuilder.name("override");
            overrideBuilder.serialize("Java");
            MetBindInfo<Echo> override = overrideBuilder.register();
            assertEquals("Java", override.getSerializeType());
        }
    }

    @Test
    public void reusedConfigurationBuildsIndependentTypedSettings() throws Exception {
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
                assertNotSame(first.getSettings(), second.getSettings());
                assertEquals("First", first.getSettings().getDefaultGroup());
                assertEquals("Second", second.getSettings().getDefaultGroup());
                assertEquals("first", first.getSettings().getResponseOptions().getOption("tag"));
                assertEquals("second", second.getSettings().getResponseOptions().getOption("tag"));
                ConnectorConfig firstConnector = first.getSettings().getConnectorConfigs().iterator().next();
                ConnectorConfig secondConnector = second.getSettings().getConnectorConfigs().iterator().next();
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
                assertEquals(1, first.getSettings().getConnectorConfigs().size());
                assertEquals(1, second.getSettings().getConnectorConfigs().size());
                assertNull(first.getSettings().getRequestOptions().getOption("later"));
                assertNull(second.getSettings().getRequestOptions().getOption("later"));
            }
        }
    }

    @Test
    public void invalidConfigurationIsRejectedBeforeAContextIsConstructed() throws Exception {
        MetConfiguration settings = new MetConfiguration().setDefaultProtocol("missing");
        assertThrows(IllegalArgumentException.class, settings::buildContext);
        settings.setDefaultProtocol("rsf");
        assertThrows(IllegalArgumentException.class, () -> settings.connector(""));
    }

    @Test
    public void buildUsesTheThreadClassLoaderAndFallsBackWhenItIsAbsent() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        try {
            thread.setContextClassLoader(loader);
            try (MetContext context = new MetConfiguration().buildContext()) {
                assertSame(loader, context.getClassLoader());
            }

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
                ConnectorConfig oldEndpoint = first.getSettings().getConnectorConfigs().iterator().next();
                ConnectorConfig newEndpoint = second.getSettings().getConnectorConfigs().iterator().next();
                assertEquals(8080, oldEndpoint.address().getPort());
                assertEquals(8081, newEndpoint.address().getPort());
                assertEquals(1200, oldEndpoint.connectTimeout());
                assertEquals(2400, newEndpoint.connectTimeout());
                assertEquals("/first", oldEndpoint.protocol("hprose").option("contextPath", null));
                assertEquals("/second", newEndpoint.protocol("hprose").option("contextPath", null));
                assertEquals("first", first.getSettings().getResponseOptions().getOption("tag"));
                assertEquals("second", second.getSettings().getResponseOptions().getOption("tag"));
                assertEquals("default", oldEndpoint.address().getFormUnit());
                assertEquals("new_unit", newEndpoint.address().getFormUnit());
                assertEquals(Collections.singleton("hprose"), first.getSettings().getProtocols());
                assertEquals("hprose", first.getDefaultProtocol());
            }
        }
    }

    @Test
    public void failedBuildLeavesExistingContextUnchangedAndAllowsCorrection() throws Exception {
        MetConfiguration source = new MetConfiguration();
        try (MetContext first = source.buildContext()) {
            source.setDefaultGroup("changed").setDefaultProtocol("missing");
            assertThrows(IllegalArgumentException.class, source::buildContext);
            assertEquals("default", first.getSettings().getDefaultGroup());
            source.setDefaultProtocol("RsF");
            try (MetContext second = source.buildContext()) {
                assertEquals("changed", second.getSettings().getDefaultGroup());
                assertEquals("rsf", second.getDefaultProtocol());
            }
        }
    }

    @Test
    public void invalidMountsFailDuringBuildBeforeOpeningThePort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            MetConfiguration mismatch = new MetConfiguration();
            mismatch.connector("tcp").bind("127.0.0.1", occupied.getLocalPort()).protocol("hprose");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, mismatch::buildContext);
            assertTrue(failure.getMessage().contains("requires transport"));
        }

        // 协议缺失、空挂载及重复挂载分别在构建阶段报错。
        MetConfiguration missing = new MetConfiguration();
        missing.connector("tcp").protocol("missing");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, missing::buildContext);
        assertTrue(failure.getMessage().contains("Unknown protocol"));
        MetConfiguration empty = new MetConfiguration();
        empty.connector("tcp");
        assertThrows(IllegalArgumentException.class, empty::buildContext);
        MetConfiguration duplicate = new MetConfiguration();
        duplicate.connector("tcp").protocol("rsf");
        duplicate.connector("tcp").protocol("RSF");
        assertThrows(IllegalArgumentException.class, duplicate::buildContext);

        // 不同协议也不能占用规范化后相同的 HTTP 路径。
        MetConfiguration conflictingPaths = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = conflictingPaths.connector("http");
        http.protocol("hprosea").contextPath("/same/");
        http.protocol("hproseb").contextPath("/same");
        var conflict = assertThrows(IllegalArgumentException.class, conflictingPaths::buildContext);
        assertTrue(conflict.getMessage().contains("Duplicate HTTP mount"));
    }

    @Test
    public void optionsCannotOverrideSpiIdentity() {
        MetConfiguration.ConnectorSettings tcp = new MetConfiguration().connector("tcp");
        MetConfiguration.ProtocolSettings rsf = tcp.protocol("rsf");
        for (String key : new String[] { "name", "listenType", "factory", "scheme", "protocol", "localPort" }) {
            assertThrows(IllegalArgumentException.class, () -> tcp.option(key, "other"));
            assertThrows(IllegalArgumentException.class, () -> rsf.option(key, "other"));
        }
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", -1));
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", 65536));
    }

    @Test
    public void explicitOutboundEndpointReplacesDefaultListener() throws Exception {
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").protocol("rsf");
        try (MetContext context = source.buildContext()) {
            context.start();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertFalse(endpoint.bindEnabled());
            assertNull(context.getSettings().getBindAddressSet("rsf"));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
        }
    }

    @Test
    public void portZeroOpensAnEphemeralListenerOnlyAtStart() throws Exception {
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = source.buildContext()) {
            assertNull(context.bindAddress("rsf"));

            context.start();
            assertTrue(context.bindAddress("rsf").getPort() > 0);
        }
    }

    @Test
    public void tcpClientInvokesWithoutOpeningAListener() throws Exception {
        this.invokeWithoutListener("tcp", "rsf");
    }

    @Test
    public void httpClientInvokesWithoutOpeningAListener() throws Exception {
        this.invokeWithoutListener("http", "hprose");
    }

    @Test
    public void contextClassLoaderControlsProtocolDiscovery() throws Exception {
        MetConfiguration config = new MetConfiguration();
        config.connector("http").protocol("hprose");
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (StringUtils.equals(name, "META-INF/services/net.hasor.meteor.connector.protocol.ProtocolFactory")) {
                    return Collections.emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
        var failure = assertThrows(IllegalArgumentException.class, () -> config.buildContext(loader));
        assertTrue(failure.getMessage().contains("Unknown protocol"));
    }

    private void invokeWithoutListener(String transport, String protocol) throws Exception {
        // 服务端监听、客户端仅配置出口，二者使用相同协议路径。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector(transport).bind("127.0.0.1", 0).protocol(protocol).contextPath("/echo");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector(transport).protocol(protocol).contextPath("/echo");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 指定服务端地址完成远程调用，客户端仍不创建任何监听。
            Echo remote = client.getRsfClient(server.bindAddress(protocol)).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.bindAddress(protocol));
            assertNull(client.getSettings().getBindAddressSet(protocol));
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import net.hasor.cobble.setting.Settings;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.SendLimitPolicy;
import net.hasor.rsf.address.route.DefaultArgsKey;
import net.hasor.rsf.bootstrap.Configuration;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigurationTest extends BootstrapTestSupport {
    public interface Echo {
        String echo(String value);
    }

    public static class CustomArgsKey extends DefaultArgsKey {
    }

    @Test
    public void argsKeyClassIsPreservedWithoutReloadingThroughContextClassLoader() throws Exception {
        Configuration configuration = new Configuration().setArgsKey(CustomArgsKey.class);
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals(CustomArgsKey.class.getName())) {
                    throw new ClassNotFoundException("The configured Class must be used directly");
                }
                return super.loadClass(name);
            }
        };
        try (RsfContext first = configuration.buildContext(loader)) {
            assertSame(CustomArgsKey.class, first.getSettings().getArgsKeyClass());
            configuration.setArgsKey(DefaultArgsKey.class);
            try (RsfContext second = configuration.buildContext(loader)) {
                assertSame(DefaultArgsKey.class, second.getSettings().getArgsKeyClass());
                assertSame(CustomArgsKey.class, first.getSettings().getArgsKeyClass());
            }
        }
    }

    @Test
    public void typedConfigurationDrivesServiceDefaultsAndComponentConstruction() throws Exception {
        Configuration settings = new Configuration().setBindAddress("127.0.0.1").setUnitName("zone_a").setDataHome(Paths.get("custom-rsf-data")).setAutomaticOnline(false).setDefaultGroup("Example").setDefaultVersion("2.0").setDefaultTimeout(1234).setSerializeType("jSoN").setRequestTimeout(2345).setMaximumRequest(17).setSendLimitPolicy(SendLimitPolicy.WaitSecond).setConnectTimeout(3456).setQueueMinPoolSize(1).setQueueMaxPoolSize(2).setQueueMaxSize(23).setQueueKeepAliveTime(60).setLocalDiskCache(false).setRefreshCacheTime(800).setInvalidWaitTime(45000).setDiskCacheTimeInterval(7200000)
                .setArgsKey(DefaultArgsKey.class).setResponseOption("server.tag", "first").setResponseOption("server.tag", "second").setRequestOption("client.tag", "configured");
        Object configuration = settings;
        assertFalse(configuration instanceof Settings);
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        try (RsfContext context = settings.buildContext(loader)) {
            assertFalse(context.isOnline());
            assertNull(context.bindAddress("rsf"));
            assertSame(loader, context.getClassLoader());
            assertEquals("jSoN", context.getSettings().getSerializeType());
            assertTrue(context.getSerializeCoder("jSoN") instanceof JsonSerializeCoder);
            assertSame(DefaultArgsKey.class, context.getSettings().getArgsKeyClass());
            assertEquals("second", context.getSettings().getResponseOptions().getOption("server.tag"));
            assertEquals("configured", context.getSettings().getRequestOptions().getOption("client.tag"));
            assertEquals(Paths.get("custom-rsf-data"), context.getSettings().getDataHome());
            assertEquals("zone_a", context.getSettings().getBindAddressSet("rsf").getFormUnit());
            assertEquals(17, context.getSettings().getMaximumRequest());
            assertEquals(23, context.getSettings().getQueueMaxSize());
            RsfBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();
            assertEquals("Example", service.getBindGroup());
            assertEquals("2.0", service.getBindVersion());
            assertEquals(1234, service.getClientTimeout());
            assertEquals("jSoN", service.getSerializeType());
            assertEquals("local", context.getRsfClient().getRemote(service).echo("local"));
        }
    }

    @Test
    public void serializeTypeSelectsSpiCoderAndCanBeOverriddenPerService() throws Exception {
        Configuration configuration = new Configuration().setSerializeType("Java");
        configuration.setSerializeType("jSoN");
        assertEquals("jSoN", configuration.getSerializeType());
        try (RsfContext context = configuration.buildContext()) {
            RsfBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> value).register();
            assertEquals("jSoN", service.getSerializeType());
            assertEquals(JsonSerializeCoder.class, context.getSerializeCoder(service.getSerializeType()).getClass());
            assertSame(context.getSerializeCoder("Json"), context.getSerializeCoder("jSoN"));
            RsfBindInfo<Echo> override = context.publisher().rsfService(Echo.class, value -> value).name("override").serialize("Java").register();
            assertEquals("Java", override.getSerializeType());
        }
    }

    @Test
    public void reusedConfigurationBuildsIndependentTypedSettings() throws Exception {
        Configuration configuration = new Configuration().setDefaultGroup("First").setConnectTimeout(1000).setResponseOption("tag", "first").setSerializeType("Json");
        Configuration.ConnectorSettings tcp = configuration.connector("tcp").bind("127.0.0.1", 23001).option("custom.nested", "first");
        tcp.protocol("rsf");
        try (RsfContext first = configuration.buildContext()) {
            Object runtimeSettings = first.getSettings();
            assertFalse(runtimeSettings instanceof Settings);
            configuration.setDefaultGroup("Second").setConnectTimeout(2000).setResponseOption("tag", "second").setSerializeType("Java");
            tcp.bind("127.0.0.1", 23002).option("custom.nested", "second");
            try (RsfContext second = configuration.buildContext()) {
                assertNotSame(first.getSettings(), second.getSettings());
                assertEquals("First", first.getSettings().getDefaultGroup());
                assertEquals("Second", second.getSettings().getDefaultGroup());
                assertEquals("first", first.getSettings().getResponseOptions().getOption("tag"));
                assertEquals("second", second.getSettings().getResponseOptions().getOption("tag"));
                assertEquals("Json", first.getSettings().getSerializeType());
                assertEquals("Java", second.getSettings().getSerializeType());
                ConnectorConfig firstConnector = first.getSettings().getConnectorConfigs().iterator().next();
                ConnectorConfig secondConnector = second.getSettings().getConnectorConfigs().iterator().next();
                assertEquals(23001, firstConnector.address().getPort());
                assertEquals(23002, secondConnector.address().getPort());
                assertEquals(1000, firstConnector.connectTimeout());
                assertEquals(2000, secondConnector.connectTimeout());
                assertEquals("first", firstConnector.option("custom.nested", null));
                assertEquals("second", secondConnector.option("custom.nested", null));
                assertNull(first.bindAddress("rsf"));
                assertNull(second.bindAddress("rsf"));
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
        Configuration settings = new Configuration().setDefaultProtocol("missing");
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
            try (RsfContext context = new Configuration().buildContext()) {
                assertSame(loader, context.getClassLoader());
            }
            thread.setContextClassLoader(null);
            try (RsfContext context = new Configuration().buildContext()) {
                assertSame(Configuration.class.getClassLoader(), context.getClassLoader());
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    public void defaultsDescribeConfigurationWithoutStartingResources() throws Exception {
        RsfSettings settings = new Configuration();
        assertEquals("RSF", settings.getDefaultGroup());
        assertEquals("1.0.0", settings.getDefaultVersion());
        assertEquals("Java", settings.getSerializeType());
        assertEquals(6000, settings.getDefaultTimeout());
        assertEquals(200, settings.getMaximumRequest());
        assertEquals(Paths.get("rsf-data"), settings.getDataHome());
        assertEquals(Collections.singleton("rsf"), settings.getProtocols());
        assertEquals("rsf://127.0.0.1:2181/default", settings.getBindAddressSet("rsf").toHostSchema());
        assertFalse(settings.isLocalDiskCache());
        assertTrue(settings.isAutomaticOnline());
    }

    @Test
    public void snapshotsIsolateEndpointProtocolAndCommonOptions() throws Exception {
        Configuration source = new Configuration().setResponseOption("tag", "first");
        Configuration.ConnectorSettings http = source.connector("HTTP").bind("127.0.0.1", 8080).option("connectTimeout", 1200);
        Configuration.ProtocolSettings hprose = http.protocol("HpRoSe").contextPath("/first");
        try (RsfContext first = source.buildContext()) {
            http.bind("127.0.0.1", 8081).option("connectTimeout", 2400);
            hprose.contextPath("/second");
            source.setUnitName("new_unit").setResponseOption("tag", "second");
            try (RsfContext second = source.buildContext()) {
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
        Configuration source = new Configuration();
        try (RsfContext first = source.buildContext()) {
            source.setDefaultGroup("changed").setDefaultProtocol("missing");
            assertThrows(IllegalArgumentException.class, source::buildContext);
            assertEquals("RSF", first.getSettings().getDefaultGroup());
            source.setDefaultProtocol("RsF");
            try (RsfContext second = source.buildContext()) {
                assertEquals("changed", second.getSettings().getDefaultGroup());
                assertEquals("rsf", second.getDefaultProtocol());
            }
        }
    }

    @Test
    public void invalidMountsFailDuringBuildBeforeOpeningThePort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            Configuration mismatch = new Configuration();
            mismatch.connector("tcp").bind("127.0.0.1", occupied.getLocalPort()).protocol("hprose");
            assertTrue(assertThrows(IllegalArgumentException.class, mismatch::buildContext).getMessage().contains("requires transport"));
        }
        Configuration missing = new Configuration();
        missing.connector("tcp").protocol("missing");
        assertTrue(assertThrows(IllegalArgumentException.class, missing::buildContext).getMessage().contains("Unknown protocol"));
        Configuration empty = new Configuration();
        empty.connector("tcp");
        assertThrows(IllegalArgumentException.class, empty::buildContext);
        Configuration duplicate = new Configuration();
        duplicate.connector("tcp").protocol("rsf");
        duplicate.connector("tcp").protocol("RSF");
        assertThrows(IllegalArgumentException.class, duplicate::buildContext);
        Configuration conflictingPaths = new Configuration();
        Configuration.ConnectorSettings http = conflictingPaths.connector("http");
        http.protocol("hprosea").contextPath("/same/");
        http.protocol("hproseb").contextPath("/same");
        assertTrue(assertThrows(IllegalArgumentException.class, conflictingPaths::buildContext).getMessage().contains("Duplicate HTTP mount"));
    }

    @Test
    public void optionsCannotOverrideSpiIdentity() {
        Configuration.ConnectorSettings tcp = new Configuration().connector("tcp");
        Configuration.ProtocolSettings rsf = tcp.protocol("rsf");
        for (String key : new String[] { "name", "listenType", "factory", "scheme", "protocol", "localPort" }) {
            assertThrows(IllegalArgumentException.class, () -> tcp.option(key, "other"));
            assertThrows(IllegalArgumentException.class, () -> rsf.option(key, "other"));
        }
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", -1));
        assertThrows(IllegalArgumentException.class, () -> tcp.bind("127.0.0.1", 65536));
    }

    @Test
    public void explicitOutboundEndpointReplacesDefaultListener() throws Exception {
        Configuration source = new Configuration();
        source.connector("tcp").protocol("rsf");
        try (RsfContext context = source.buildContext()) {
            context.start();
            assertFalse(context.getSettings().getConnectorConfigs().iterator().next().bindEnabled());
            assertNull(context.getSettings().getBindAddressSet("rsf"));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
        }
    }

    @Test
    public void portZeroOpensAnEphemeralListenerOnlyAtStart() throws Exception {
        Configuration source = new Configuration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (RsfContext context = source.buildContext()) {
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
        Configuration config = new Configuration();
        config.connector("http").protocol("hprose");
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/net.hasor.rsf.connector.protocol.ProtocolFactory")) {
                    return Collections.emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
        assertTrue(assertThrows(IllegalArgumentException.class, () -> config.buildContext(loader)).getMessage().contains("Unknown protocol"));
    }

    private void invokeWithoutListener(String transport, String protocol) throws Exception {
        Configuration serverConfig = new Configuration().setSerializeType("Java");
        serverConfig.connector(transport).bind("127.0.0.1", 0).protocol(protocol).contextPath("/echo");
        Configuration clientConfig = new Configuration().setSerializeType("Java");
        clientConfig.connector(transport).protocol(protocol).contextPath("/echo");
        try (RsfContext server = serverConfig.buildContext(); RsfContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            assertEquals("remote:value", client.getRsfClient(server.bindAddress(protocol)).getRemote(service).echo("value"));
            assertNull(client.bindAddress(protocol));
            assertNull(client.getSettings().getBindAddressSet(protocol));
        }
    }
}

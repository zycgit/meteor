/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Map;
import java.util.LinkedHashMap;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.noear.solon.core.Props;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolonSettingsTest {
    @Test
    void derivesTransportFromProtocolSpiAndReadsHttpContext() throws Exception {
        // 不配置 transport 或 protocols.name，传输能力来自已安装 SPI。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("meteor.application-name", "example");
        values.put("meteor.connectors.rsf.host", "127.0.0.1");
        values.put("meteor.connectors.rsf.port", 8000);
        values.put("meteor.connectors.rsf.options.max-frame-size", "1024");
        values.put("meteor.connectors.jsonrpc.host", "127.0.0.1");
        values.put("meteor.connectors.jsonrpc.port", 8001);
        values.put("meteor.connectors.jsonrpc.context", "/api");
        MetConfiguration config = SolonSettings.configuration(settings(values), getClass().getClassLoader());
        assertEquals("example", config.getApplicationName());
        assertEquals(2, config.getConnectors().size());
        MetConfiguration.ConnectorSettings rsf = config.getConnectors().stream()
                .filter(connector -> connector.getPort() == 8000).findFirst().orElseThrow();
        MetConfiguration.ConnectorSettings http = config.getConnectors().stream()
                .filter(connector -> connector.getPort() == 8001).findFirst().orElseThrow();
        assertEquals("tcp", rsf.getType());
        assertEquals("rsf", rsf.getProtocols().get(0).getName());
        assertEquals("1024", rsf.getProtocols().get(0).getOptions().get("max-frame-size"));
        assertEquals("http", http.getType());
        assertEquals("jsonrpc", http.getProtocols().get(0).getName());
        assertEquals("/api", http.getProtocols().get(0).getOptions().get("contextPath"));
    }

    @Test
    void enablesOnlyConfiguredInstalledProtocols() throws Exception {
        // JSON-RPC 的 SPI 虽然存在，没有配置监听信息仍然禁用。
        Props source = settings(Map.of(
                "meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 0));
        MetConfiguration config = SolonSettings.configuration(source, getClass().getClassLoader());
        assertEquals(1, config.getConnectors().size());
        assertEquals("rsf", config.getConnectors().get(0).getProtocols().get(0).getName());
        assertEquals(0, config.getConnectors().get(0).getPort());
    }

    @Test
    void disablesProtocolWithoutPort() throws Exception {
        Props source = settings(Map.of("meteor.connectors.rsf.host", "127.0.0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> SolonSettings.configuration(source, getClass().getClassLoader()));
    }

    @Test
    void disablesProtocolWithoutHost() throws Exception {
        Props source = settings(Map.of("meteor.connectors.rsf.port", 8000));
        assertThrows(IllegalArgumentException.class,
                () -> SolonSettings.configuration(source, getClass().getClassLoader()));
    }

    @Test
    void ignoresConfigurationWithoutMatchingSpi() throws Exception {
        Props source = settings(Map.of(
                "meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 8000,
                "meteor.connectors.unavailable.host", "127.0.0.1", "meteor.connectors.unavailable.port", 8001));
        MetConfiguration config = SolonSettings.configuration(source, getClass().getClassLoader());
        assertEquals(1, config.getConnectors().size());
        assertEquals("rsf", config.getConnectors().get(0).getProtocols().get(0).getName());
    }

    @Test
    void rejectsEmptyProtocolSelectionInsteadOfUsingDefaultRsf() throws Exception {
        Props source = settings(Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SolonSettings.configuration(source, getClass().getClassLoader()));
        assertTrue(error.getMessage().contains("No Meteor protocol enabled"));
    }

    @Test
    void readsNativeConfigurationOverrides() throws Exception {
        Props source = settings(Map.of(
                "meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 8000,
                "meteor.application-name", "before"));
        source.setProperty("meteor.application-name", "after");
        MetConfiguration config = SolonSettings.configuration(source, getClass().getClassLoader());
        assertEquals("after", config.getApplicationName());
    }

    private static Props settings(Map<String, ?> values) throws Exception {
        Props properties = new Props();
        values.forEach((key, value) -> properties.setProperty(key, String.valueOf(value)));
        return properties;
    }
}

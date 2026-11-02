/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import javax.sql.DataSource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SpringSettingsTest {
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
        MetConfiguration config = SpringSettings.configuration(settings(values), getClass().getClassLoader());
        assertEquals("example", config.getApplicationName());
        assertEquals(2, config.getConnectors().size());
        MetConfiguration.ConnectorSettings rsf = config.getConnectors().stream().filter(connector -> connector.getPort() == 8000).findFirst().orElseThrow();
        MetConfiguration.ConnectorSettings http = config.getConnectors().stream().filter(connector -> connector.getPort() == 8001).findFirst().orElseThrow();
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
        StandardEnvironment source = settings(Map.of("meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 0));
        MetConfiguration config = SpringSettings.configuration(source, getClass().getClassLoader());
        assertEquals(1, config.getConnectors().size());
        assertEquals("rsf", config.getConnectors().get(0).getProtocols().get(0).getName());
        assertEquals(0, config.getConnectors().get(0).getPort());
    }

    @Test
    void disablesProtocolWithoutPort() throws Exception {
        StandardEnvironment source = settings(Map.of("meteor.connectors.rsf.host", "127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> SpringSettings.configuration(source, getClass().getClassLoader()));
    }

    @Test
    void disablesProtocolWithoutHost() throws Exception {
        StandardEnvironment source = settings(Map.of("meteor.connectors.rsf.port", 8000));
        assertThrows(IllegalArgumentException.class, () -> SpringSettings.configuration(source, getClass().getClassLoader()));
    }

    @Test
    void ignoresConfigurationWithoutMatchingSpi() throws Exception {
        StandardEnvironment source = settings(Map.of("meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 8000, "meteor.connectors.unavailable.host", "127.0.0.1", "meteor.connectors.unavailable.port", 8001));
        MetConfiguration config = SpringSettings.configuration(source, getClass().getClassLoader());
        assertEquals(1, config.getConnectors().size());
        assertEquals("rsf", config.getConnectors().get(0).getProtocols().get(0).getName());
    }

    @Test
    void rejectsEmptyProtocolSelectionInsteadOfUsingDefaultRsf() throws Exception {
        StandardEnvironment source = settings(Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> SpringSettings.configuration(source, getClass().getClassLoader()));
        assertTrue(error.getMessage().contains("No Meteor protocol enabled"));
    }

    @Test
    void preservesEnvironmentPrecedenceAndPlaceholders() throws Exception {
        StandardEnvironment source = settings(Map.of("meteor.connectors.rsf.host", "127.0.0.1", "meteor.connectors.rsf.port", 8000, "meteor.application-name", "before"));
        source.getPropertySources().addFirst(new MapPropertySource("application", Map.of("meteor.application-name", "${example.name}", "example.name", "after")));
        MetConfiguration config = SpringSettings.configuration(source, getClass().getClassLoader());
        assertEquals("after", config.getApplicationName());
    }

    @Test
    void ignoresAbsentRegistryConfigurationWithoutOptionalDependency() throws Exception {
        // 无配置时不能尝试解析可选注册中心类型。
        var source = settings(Map.of());
        assertNull(SpringSettings.registry(source, withoutRegistry("net.hasor.meteor.registry."), List.of(), (name, type) -> null));
    }

    @Test
    void disablesAutomaticRegistryWithoutOptionalDependency() throws Exception {
        // 显式禁用后保留直连用法，即使给了 provider 也无需加载依赖。
        var source = settings(Map.of("meteor.registry.enabled", false, "meteor.registry.provider", "nacos"));
        assertNull(SpringSettings.registry(source, withoutRegistry("net.hasor.meteor.registry."), List.of(), (name, type) -> null));
    }

    @Test
    void reportsMissingRegistryDependencyWhenExplicitlyConfigured() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "memory"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                SpringSettings.registry(source, withoutRegistry("net.hasor.meteor.registry."), List.of(), (name, type) -> null));
        assertTrue(error.getMessage().contains("meteor-registry dependency"));
    }

    @Test
    void reportsMissingSelectedProviderDependency() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "nacos", "meteor.registry.address", "127.0.0.1:8848"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                SpringSettings.registry(source, withoutRegistry("net.hasor.meteor.registry.nacos."), List.of(), (name, type) -> null));
        assertTrue(error.getMessage().contains("meteor-registry-nacos dependency"));
    }

    @Test
    void existingRegistryBeanOverridesAutomaticProviderSelection() throws Exception {
        // 已有 Bean 直接使用，不能再装配另一个客户端，也不要求配置指定的插件存在。
        var source = settings(Map.of("meteor.registry.provider", "nacos"));
        RegistryConfiguration existing = new MemoryRegistryConfiguration();
        assertNull(SpringSettings.registry(source, withoutRegistry("net.hasor.meteor.registry.nacos."), List.of(existing), (name, type) -> null));
    }

    @Test
    void offlineConfigurationIgnoresMissingStoragePlugin() throws Exception {
        var source = settings(Map.of("meteor.registry.online", false, "meteor.registry.provider", "nacos"));
        RegistryConfiguration registry = (RegistryConfiguration) SpringSettings.registry(source,
                withoutRegistry("net.hasor.meteor.registry.nacos."), List.of(), (name, type) -> null);
        assertFalse(registry.isOnline());
        assertNull(registry.getProvider());
    }

    @Test
    void readsManagementProtocolsFromNativeConfiguration() throws Exception {
        var source = settings(Map.of("meteor.registry.online", false,
                "meteor.registry.management-protocols", "rsf,jsonrpc", "meteor.registry.management-token", "secret"));
        RegistryConfiguration registry = (RegistryConfiguration) SpringSettings.registry(source,
                getClass().getClassLoader(), List.of(), (name, type) -> null);
        assertEquals(List.of("rsf", "jsonrpc"), registry.getManagementProtocols());
    }

    @Test
    void borrowsNamedJdbcDataSourceFromContainer() throws Exception {
        // 注册中心配置只借用现成 Bean，不创建数据源或打开连接。
        var source = settings(Map.of("meteor.registry.provider", "jdbc", "meteor.registry.data-source", "registryDb"));
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:unused_registry_configuration");
        MetExtensionFactory registry = SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> {
            assertEquals("registryDb", name);
            assertEquals(DataSource.class, type);
            return database;
        });
        assertInstanceOf(JdbcRegistryConfiguration.class, registry);
        try (MetContext context = new MetConfiguration().addExtension(registry).buildContext()) {
            assertNotNull(context.getExtension(RegistryClient.class));
            assertFalse(context.isOnline());
        }
    }

    @Test
    void rejectsMissingJdbcDataSourceBean() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "jdbc", "meteor.registry.data-source", "missing"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> null));
        assertTrue(error.getMessage().contains("meteor.registry.data-source"));
    }

    @Test
    void bindsTokenProviderBeanWithoutReadingItsCredentialDuringConfiguration() throws Exception {
        var source = settings(Map.of("meteor.registry.online", false, "meteor.registry.token-provider", "managementTokens"));
        RegistryTokenProvider tokens = () -> { throw new AssertionError("Credential must be read only during a call"); };
        MetExtensionFactory registry = SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> {
            assertEquals("managementTokens", name);
            assertEquals(RegistryTokenProvider.class, type);
            return tokens;
        });
        try (MetContext context = new MetConfiguration().addExtension(registry).buildContext()) {
            assertNull(context.getExtension(RegistryClient.class));
        }
    }

    @Test
    void rejectsConflictingManagementCredentialSources() throws Exception {
        var source = settings(Map.of("meteor.registry.online", false, "meteor.registry.management-token", "secret",
                "meteor.registry.token-provider", "tokens"));
        assertThrows(IllegalArgumentException.class, () ->
                SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> null));
    }

    @Test
    void configuresNacosWithoutOpeningSdkConnections() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "nacos", "meteor.registry.address", "127.0.0.1:1",
                "meteor.registry.group", "example", "meteor.registry.namespace", "test", "meteor.registry.timeout-millis", 500));
        RegistryConfiguration registry = (RegistryConfiguration) SpringSettings.registry(source,
                getClass().getClassLoader(), List.of(), (name, type) -> null);
        assertEquals("nacos", registry.getProvider());
        try (MetContext context = new MetConfiguration().addExtension(registry).buildContext()) {
            assertNotNull(context.getExtension(RegistryClient.class));
            assertFalse(context.isOnline());
        }
    }

    @Test
    void configuresZooKeeperWithoutOpeningSdkConnections() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "zookeeper", "meteor.registry.address", "127.0.0.1:1",
                "meteor.registry.root", "/example", "meteor.registry.timeout-millis", 500, "meteor.registry.session-timeout-millis", 3000));
        RegistryConfiguration registry = (RegistryConfiguration) SpringSettings.registry(source,
                getClass().getClassLoader(), List.of(), (name, type) -> null);
        assertEquals("zookeeper", registry.getProvider());
        try (MetContext context = new MetConfiguration().addExtension(registry).buildContext()) {
            assertNotNull(context.getExtension(RegistryClient.class));
            assertFalse(context.isOnline());
        }
    }

    @Test
    void rejectsUnknownRegistryProvider() throws Exception {
        var source = settings(Map.of("meteor.registry.provider", "unknown"));
        assertThrows(IllegalArgumentException.class, () ->
                SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> null));
    }

    @Test
    void requiresProviderWhenOnlineRegistryIsEnabled() throws Exception {
        var source = settings(Map.of("meteor.registry.enabled", true));
        assertThrows(IllegalArgumentException.class, () ->
                SpringSettings.registry(source, getClass().getClassLoader(), List.of(), (name, type) -> null));
    }

    @Test
    void readsManagementProtocolsFromNativeList() throws Exception {
        // 配置源使用自己的列表语义，不先转成通用 Map。
        var source = settings(Map.of("meteor.registry.online", false,
                "meteor.registry.management-protocols[0]", "rsf", "meteor.registry.management-protocols[1]", "jsonrpc"));
        RegistryConfiguration registry = (RegistryConfiguration) SpringSettings.registry(source,
                getClass().getClassLoader(), List.of(), (name, type) -> null);
        assertEquals(List.of("rsf", "jsonrpc"), registry.getManagementProtocols());
    }

    @Test
    void discoversRegistryFromNonEnumerablePropertySource() throws Exception {
        // 如外部配置源仅支持按键读取，provider 仍应触发自动配置。
        StandardEnvironment environment = settings(Map.of());
        Map<String, Object> values = Map.of("meteor.registry.provider", "memory");
        environment.getPropertySources().addFirst(new PropertySource<Map<String, Object>>("external", values) {
            @Override
            public Object getProperty(String name) {
                return this.source.get(name);
            }
        });
        MetExtensionFactory registry = SpringSettings.registry(environment,
                getClass().getClassLoader(), List.of(), (name, type) -> null);
        assertInstanceOf(MemoryRegistryConfiguration.class, registry);
    }

    private ClassLoader withoutRegistry(String prefix) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith(prefix)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
    }

    private static StandardEnvironment settings(Map<String, ?> values) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("example", new LinkedHashMap<>(values)));
        return environment;
    }
}

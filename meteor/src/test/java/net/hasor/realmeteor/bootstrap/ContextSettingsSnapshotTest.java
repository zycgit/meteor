/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetOptionParser;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextSettingsSnapshotTest {
    private static final MetOptionParser<Integer> INTEGER = new MetOptionParser<>() {
        @Override
        public Integer parse(String value) {
            return Integer.valueOf(value);
        }

        @Override
        public String format(Integer value) {
            return value.toString();
        }
    };

    @Test
    public void buildSuppliesDefaultsWithoutWritingThemBackToConfiguration() throws Exception {
        // 不声明端点或默认协议，构建阶段应补齐默认 TCP/RSF 配置。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            MetSettings settings = context.getSettings();
            assertFalse(MetConfiguration.class.isInstance(settings));
            assertEquals("rsf", settings.getDefaultProtocol());
            assertEquals(Set.of("rsf"), settings.getProtocols());
            ConnectorConfig endpoint = settings.getConnectorConfigs().iterator().next();
            assertEquals("tcp://127.0.0.1:2181/default", endpoint.address().toHostSchema());
            assertTrue(endpoint.bindEnabled());
            assertEquals("2", endpoint.options().get("workerThread"));
            assertEquals("2", endpoint.options().get("taskThread"));
            assertEquals("3000", endpoint.options().get("connectTimeout"));
            assertNull(context.getListenAddress("rsf"));

            // 已构建设置独立持有结果，用户配置仍保留最初的输入状态。
            assertTrue(configuration.getConnectors().isEmpty());
            assertNull(configuration.getDefaultProtocol());
        }
    }

    @Test
    public void invalidAddressFailsDuringBuildAndCanBeCorrected() throws Exception {
        // 无效的 IPv6 字面量可以作为输入读取，地址解析在构建时执行。
        MetConfiguration configuration = new MetConfiguration().setBindAddress("[invalid-ipv6]");
        assertEquals("[invalid-ipv6]", configuration.getBindAddress());
        assertThrows(IOException.class, configuration::buildContext);

        // 修正输入后可重新构建，失败尝试没有给配置留下端点快照。
        configuration.setBindAddress("127.0.0.1");
        try (MetContext context = configuration.buildContext()) {
            assertEquals("127.0.0.1", context.getSettings().getBindAddress());
            assertTrue(configuration.getConnectors().isEmpty());
        }
    }

    @Test
    public void blankBindAddressCannotSilentlyBecomeTheLoopbackDefault() {
        // 空白地址仍是无效配置，默认端点生成不能将其隐式解析为本机地址。
        MetConfiguration configuration = new MetConfiguration().setBindAddress("");
        assertThrows(IllegalArgumentException.class, configuration::buildContext);
        assertEquals("", configuration.getBindAddress());
    }

    @Test
    public void requestTimeoutFallbackBelongsToEachBuild() throws Exception {
        // 未设置独立调用超时时，每次构建采用当时的服务默认超时。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(1700);
        try (MetContext first = configuration.buildContext()) {
            configuration.setDefaultTimeout(2900);
            try (MetContext second = configuration.buildContext()) {
                // 读取原配置不计算回退值，既有快照也不重新读取原配置。
                assertEquals(0, configuration.getRequestTimeout());
                assertEquals(1700, first.getSettings().getRequestTimeout());
                assertEquals(2900, second.getSettings().getRequestTimeout());
            }
        }
    }

    @Test
    public void typedOptionReadsWorkButTypedWritesAreRejected() throws Exception {
        // 构建时复制已有类型化选项，读取继续使用调用方提供的解析器。
        MetConfiguration configuration = new MetConfiguration();
        configuration.getRequestOptions().addOption("attempts", 2, INTEGER);
        try (MetContext context = configuration.buildContext()) {
            MetOptionSet options = context.getSettings().getRequestOptions();
            assertEquals(Integer.valueOf(2), options.getOption("attempts", INTEGER));

            // 泛型写入口与字符串写入口具有相同的只读契约。
            assertThrows(UnsupportedOperationException.class, () -> options.addOption("attempts", 3, INTEGER));
            assertEquals(Integer.valueOf(2), options.getOption("attempts", INTEGER));
        }
    }

    @Test
    public void optionSnapshotPreservesNullsAndDoesNotExposeItsKeys() throws Exception {
        // 原选项模型允许 null 键和值，快照不能改变其读取语义。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setRequestOption(null, "null-key").setRequestOption("nullable", null);
        try (MetContext context = configuration.buildContext()) {
            MetOptionSet options = context.getSettings().getRequestOptions();
            assertEquals("null-key", options.getOption(null));
            assertNull(options.getOption("nullable"));

            // 修改返回的键数组或原始选项，均不能修改快照里的键值。
            String[] keys = options.getOptionKeys();
            Arrays.fill(keys, "changed");
            configuration.setRequestOption(null, "later").setRequestOption("nullable", "later");
            assertTrue(Arrays.asList(options.getOptionKeys()).contains(null));
            assertTrue(Arrays.asList(options.getOptionKeys()).contains("nullable"));
            assertEquals("null-key", options.getOption(null));
            assertNull(options.getOption("nullable"));
        }
    }

    @Test
    public void connectorSettingsExposeOnlyReadOnlyCollections() throws Exception {
        // 构建一个显式出站端点，检查公开配置集合及端点、协议选项。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            MetSettings settings = context.getSettings();
            Collection<ConnectorConfig> connectors = settings.getConnectorConfigs();
            ConnectorConfig endpoint = connectors.iterator().next();
            ProtocolConfig protocol = endpoint.protocol("rsf");

            // 任一集合写入口都不能改变 Context 后续使用的配置。
            assertThrows(UnsupportedOperationException.class, connectors::clear);
            assertThrows(UnsupportedOperationException.class, () -> settings.getProtocols().clear());
            assertThrows(UnsupportedOperationException.class, () -> endpoint.options().put("connectTimeout", "1"));
            assertThrows(UnsupportedOperationException.class, () -> endpoint.protocols().clear());
            assertThrows(UnsupportedOperationException.class, () -> protocol.options().put("serializeType", "Java"));
            assertEquals(1, settings.getConnectorConfigs().size());
            assertEquals(Set.of("rsf"), settings.getProtocols());
            assertEquals("Json", protocol.option("serializeType", null));
        }
    }
}

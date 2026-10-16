/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetConfigurationInputTest {
    @Test
    public void gettersCanInspectIncompleteInputsWithoutResolvingThem() {
        // 配置正在编辑，地址不可解析、默认协议未挂载、端点尚无协议。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setBindAddress("[invalid-ipv6]").setDefaultProtocol("missing");
        MetConfiguration.ConnectorSettings endpoint = configuration.connector("TCP");

        // getter 只返回输入，不解析地址、不发现 SPI，也不校验完整端点。
        assertEquals("[invalid-ipv6]", configuration.getBindAddress());
        assertEquals("missing", configuration.getDefaultProtocol());
        assertSame(endpoint, configuration.getConnectors().get(0));
        assertEquals("tcp", endpoint.getType());
        assertNull(endpoint.getHost());
        assertEquals(0, endpoint.getPort());
        assertTrue(endpoint.getProtocols().isEmpty());
        assertTrue(endpoint.getOptions().isEmpty());
    }

    @Test
    public void gettersExposeProtocolInputsWithoutApplyingBuildDefaults() {
        // 协议挂载保留用户配置的名称、别名、选项及上下文路径。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings endpoint = configuration.connector("HTTP").bind("localhost", 8080);
        MetConfiguration.ProtocolSettings protocol = endpoint.protocol("CUSTOM");
        protocol.addressScheme("PUBLIC").contextPath("/api").option("serializeType", "Json");

        // 读取未知 SPI 的输入不触发构建；全局默认选择仍未填写。
        assertEquals("localhost", endpoint.getHost());
        assertEquals(8080, endpoint.getPort());
        assertSame(protocol, endpoint.getProtocols().get(0));
        assertEquals("custom", protocol.getName());
        assertEquals("public", protocol.getAddressScheme());
        assertEquals("/api", protocol.getOptions().get("contextPath"));
        assertEquals("Json", protocol.getOptions().get("serializeType"));
        assertNull(configuration.getDefaultProtocol());
        assertEquals(0, configuration.getRequestTimeout());
    }
}

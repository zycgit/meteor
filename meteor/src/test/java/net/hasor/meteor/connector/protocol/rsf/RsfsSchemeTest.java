/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPlan;
import net.hasor.meteor.connector.transport.tcp.TcpTransportFactory;
import net.hasor.meteor.connector.SocketTestSupport;
import org.junit.Test;
import static net.hasor.meteor.connector.TransportTestConfigs.transport;
import static net.hasor.meteor.connector.transport.TransportTestServices.newRuntimeServices;
import static org.junit.Assert.*;

/** RSFS 是 RSF 在 TLS 端点上的地址形式，不对应另一套协议或传输实现。 */
public class RsfsSchemeTest {
    @Test
    public void rsfProtocolUsesSecureSchemeWithoutChangingItsWireContract() {
        // 同一个协议实现可以挂载成不同的公开地址，协议本身不推断端点安全属性。
        RsfProtocol protocol = new RsfProtocol();
        ProtocolConfig plain = ProtocolTestAccess.config("rsf", "rsf", "rsf", Map.of());
        ProtocolConfig secure = ProtocolTestAccess.config("rsf", "rsfs", "rsf", Map.of());

        // TLS 只包装传输字节，RSF 探测和序列化约定保持不变。
        assertEquals("rsf", plain.name());
        assertEquals("rsf", secure.name());
        assertEquals("rsf", plain.scheme());
        assertEquals("rsfs", secure.scheme());
        assertEquals("rsf", plain.factoryName());
        assertEquals("rsf", secure.factoryName());
        assertEquals("tcp", protocol.transportType());
        assertEquals(RouteProbeResult.MATCH, protocol.probe(secure, new byte[] { (byte) 0xb1 }));
        assertEquals(RouteProbeResult.NEED_MORE, protocol.probe(secure, new byte[0]));
        assertEquals(RouteProbeResult.REJECT, protocol.probe(secure, new byte[] { 0 }));
    }

    @Test
    public void secureRsfEndpointKeepsTheTcpTransportIdentity() {
        // 用户只选择 TCP 和 RSF，TLS 由端点选项启用。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp") //
                .option("tlsEnabled", true) //
                .protocol("rsf").addressScheme("rsfs");

        // 构建后的公开地址为 rsfs，内部仍由 tcp 与 RsfProtocol 处理。
        Collection<ConnectorConfig> connectors = configuration.getConnectorConfigs();
        ConnectorConfig connector = connectors.iterator().next();
        ProtocolConfig protocol = connector.protocol("rsfs");
        assertEquals("tcp", connector.transportType());
        assertEquals("true", connector.options().get("tlsEnabled"));
        assertNotNull(protocol);
        assertEquals("rsf", protocol.name());
        assertEquals("rsfs", protocol.scheme());
        assertEquals("rsf", protocol.factoryName());
        assertEquals("rsf", configuration.getDefaultProtocol());
    }

    @Test
    public void secureRsfRemainsTheDefaultWhenPlainAndTlsEndpointsCoexist() {
        // 同时配置明文和 TLS 端点，默认协议使用公开地址名称选择安全端点。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsfs");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .protocol("rsf").addressScheme("rsfs");

        // 默认协议规范化为逻辑名；公开别名仍精确选择安全端点。
        assertEquals("rsf", configuration.getDefaultProtocol());
        InterAddress address = configuration.getBindAddressSet("rsfs");
        assertNotNull(address);
        assertEquals("rsfs", address.getSchema());
    }

    @Test
    public void plainRsfCanBeSelectedWhenTheTlsEndpointWasConfiguredFirst() {
        // 端点顺序不能让安全地址覆盖显式选择的明文 RSF。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsf");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .protocol("rsf").addressScheme("rsfs");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

        // 默认值保留逻辑名；端点加逻辑协议可精确选择明文挂载。
        assertEquals("rsf", configuration.getDefaultProtocol());
        InterAddress address = configuration.getBindAddressSet("tcp-1", configuration.getDefaultProtocol());
        assertNotNull(address);
        assertEquals("rsf", address.getSchema());
    }

    @Test
    public void rsfsCannotBeSelectedAsAProtocolSpi() {
        // 端点只按 SPI 名称选择协议，rsfs 仅是启用 TLS 后生成的地址 scheme。
        Map<String, String> options = Map.of();
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolTestAccess.config("rsfs", "rsfs", "rsfs", options);
        ConnectorConfig config = new ConnectorConfig("invalid", address, options, List.of(protocol), false);

        // 即使端点启用 TLS，也必须选择唯一的 rsf 协议实现。
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertEquals("Unknown protocol: rsfs", failure.getMessage());
        }
    }

    @Test
    public void secureListenerRequiresPrivateKeyMaterial() {
        // TCP 监听器启用 TLS，但没有提供服务端身份材料。
        Map<String, String> options = Map.of("tlsEnabled", "true");
        ConnectorConfig config = this.endpoint(options);
        TcpTransportFactory factory = new TcpTransportFactory();
        ClassLoader loader = this.getClass().getClassLoader();
        TransportConfig transportConfig = transport(config);
        TransportPlan<byte[]> plan = factory.prepare(transportConfig, loader);

        // 缺少服务器私钥时在资源创建之前拒绝，而不是启动明文监听。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            plan.create(newRuntimeServices(loader));
        });
        assertTrue(failure.getMessage().contains("tlsKeyStore"));
    }

    @Test
    public void unsupportedTlsVersionIsRejectedBeforeListening() {
        // 证书有效，但配置了运行时不支持的 TLS 版本。
        Map<String, String> options = Map.of("tlsEnabled", "true", //
                "tlsKeyStore", "classpath:/tls/acceptance/server.p12", //
                "tlsKeyStorePassword", "meteor-test", "tlsProtocols", "TLSv9.9");
        ConnectorConfig config = this.endpoint(options);
        TcpTransportFactory factory = new TcpTransportFactory();
        ClassLoader loader = this.getClass().getClassLoader();
        TransportConfig transportConfig = transport(config);
        TransportPlan<byte[]> plan = factory.prepare(transportConfig, loader);

        // 无效版本不能被静默过滤并退回默认协议。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            plan.create(newRuntimeServices(loader));
        });
        assertTrue(failure.getMessage().contains("Unsupported TLS protocol"));
    }

    private ConnectorConfig endpoint(Map<String, String> options) {
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        ProtocolConfig protocol = ProtocolTestAccess.config("rsf", "rsfs", "rsf", options);
        return new ConnectorConfig("secure", address, options, List.of(protocol), true);
    }
}

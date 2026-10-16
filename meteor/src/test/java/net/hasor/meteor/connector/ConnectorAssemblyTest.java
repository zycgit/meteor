/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开的传输与协议 SPI 入口验证连接器组装。 */
public class ConnectorAssemblyTest {
    @Test
    public void initializationCompilesConfiguredProtocolWithoutOpeningListener() {
        // 通过与 bootstrap 相同的 SPI 发现路径准备一个端点。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();

            // 初始化已编译配置的协议计划，但不会打开监听器。
            assertTrue(manager.isInitialized());
            assertNull(manager.getListenAddress("rsf"));
            assertEquals(List.of("rsf"), List.copyOf(manager.protocols()));
        }
    }

    @Test
    public void startingInboundOpensConfiguredProtocolAddress() throws Exception {
        // 管理器初始化后启动已经配置的端点。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            manager.startInbound();

            // 公开地址使用挂载协议和实际 TCP 监听端口。
            InterAddress address = manager.getListenAddress("rsf");
            assertNotNull(address);
            assertEquals("rsf", address.getSchema());
            assertTrue(address.getPort() > 0);
            assertEquals(List.of("rsf"), List.copyOf(manager.protocols()));
        }
    }

    @Test
    public void connectRequiresInitializedManager() {
        // 构建已经完成配置准备但尚未初始化的管理器。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("client", false);
        InterAddress target = EndpointFixture.address("rsf");
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            // 连接操作通过返回的 Future 报告生命周期错误。
            Throwable failure = manager.connect(target).getCause();
            assertTrue(failure instanceof IllegalStateException);
        }
    }

    @Test
    public void unknownProtocolDoesNotActivateConfiguredTransport() {
        // 初始化只挂载 RSF 的客户端端点。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("client", false);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();

            // 不相关的地址协议在创建传输对象前失败，已编译的配置仍然可查询。
            InterAddress target = EndpointFixture.address("missing");
            Throwable failure = manager.connect(target).getCause();
            assertTrue(failure instanceof MetException);
            assertTrue(failure.getMessage().contains("No connector configured"));
            assertEquals(List.of("rsf"), List.copyOf(manager.protocols()));
        }
    }

    @Test
    public void duplicatePublishedSchemesMakeLegacyConnectAmbiguous() {
        // 两个端点可以声明同一个公开地址 scheme。
        ConnectorConfig first = EndpointFixture.rsfEndpoint("first", false);
        ConnectorConfig second = EndpointFixture.rsfEndpoint("second", false);
        try (ConnectorManager manager = EndpointFixture.manager(first, second)) {
            manager.init();

            // 只给 scheme 的兼容入口不能按配置顺序挑选端点。
            Throwable failure = manager.connect(EndpointFixture.address("rsf")).getCause();
            assertTrue(failure instanceof MetException);
            assertTrue(failure.getMessage().contains("Ambiguous connector mount"));
            assertTrue(manager.isInitialized());
        }
    }

    @Test
    public void missingTransportFailsInitialization() {
        // 管理器从上下文加载尚未支持的端点配置。
        Map<String, String> options = Map.of();
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Map.of());
        InterAddress local = InterAddress.forBinding("missing", "127.0.0.1", 0, "test");
        ConnectorConfig config = new ConnectorConfig("server", local, options, List.of(protocol), true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            // 不支持的传输类型在端点构建前被拒绝。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, manager::init);
            assertTrue(failure.getMessage().contains("Unknown transport: missing"));
            assertFalse(manager.isInitialized());
        }
    }
}

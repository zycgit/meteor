/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConfigTest {
    @Test
    public void transportTypeComesFromEndpointAddress() {
        // 端点地址声明底层传输，普通选项不再携带连接器身份。
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // 修改普通选项不会改变从地址固化的传输类型。
        options.put("workerThread", "2");
        assertEquals("tcp", config.transportType());
    }

    @Test
    public void protocolOptionsSnapshotIsDetachedFromItsBuilder() {
        // 挂载协议复制可变选项，再加入连接器配置。
        Map<String, String> protocolOptions = new HashMap<>();
        protocolOptions.put("maxFrameSize", "1024");
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "RSF", protocolOptions);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // Builder 改动不回写协议选项，查询协议名仍忽略大小写。
        protocolOptions.put("maxFrameSize", "2048");
        ProtocolConfig selected = config.protocol("RsF");
        assertEquals("1024", selected.option("maxFrameSize", null));
    }

    @Test
    public void mountedProtocolsSnapshotIsDetachedFromItsBuilder() {
        // 使用可变列表提供协议挂载。
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Collections.emptyMap());
        List<ProtocolConfig> protocols = new ArrayList<>(Collections.singletonList(protocol));
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // 清空原列表后，连接器仍保留构建时的挂载。
        protocols.clear();
        assertEquals(1, config.protocols().size());
        assertNotSame(protocol, config.protocol("rsf"));
        assertEquals("rsf", config.protocol("rsf").name());
    }

    @Test
    public void mountedProtocolsAreReadOnly() {
        // 配置快照公开协议挂载集合。
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);
        Collection<ProtocolConfig> exposed = config.protocols();

        // 挂载集合禁止调用方删除协议。
        assertThrows(UnsupportedOperationException.class, exposed::clear);
    }

    @Test
    public void protocolOptionsAreReadOnly() {
        // 协议配置公开自己的选项快照。
        Map<String, String> options = Map.of("maxFrameSize", "1024");
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "RSF", options);
        Map<String, String> exposed = protocol.options();

        // 协议选项禁止调用方直接修改。
        assertThrows(UnsupportedOperationException.class, exposed::clear);
    }

    @Test
    public void emptyRouteProtocolsAreRejected() {
        // 端点配置没有任何协议挂载。
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        List<ProtocolConfig> protocols = Collections.emptyList();

        // 构建阶段明确报告至少需要一个协议。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("endpoint", address, options, protocols, true);
        });
        assertTrue(failure.getMessage().contains("at least one"));
    }

    @Test
    public void duplicatePublishedSchemesAreIndexedByDistinctProtocolNames() {
        // 同一个端点可以通过完整路由区分具有相同公开 scheme 的协议挂载。
        ProtocolConfig first = ProtocolConfig.newConfig("first", "rsf", Collections.emptyMap());
        ProtocolConfig second = ProtocolConfig.newConfig("second", "RSF", Collections.emptyMap());
        List<ProtocolConfig> protocols = Arrays.asList(first, second);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();

        // 端点按各自协议名查找配置，公开地址前缀不作为内部主键。
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, true);
        List<ProtocolConfig> mounted = List.copyOf(config.protocols());
        assertEquals(2, mounted.size());
        assertEquals("rsf", config.protocol("first").scheme());
        assertEquals("rsf", config.protocol("second").scheme());
        assertNotSame(config.protocol("first"), config.protocol("second"));
    }

    @Test
    public void duplicateProtocolNamesOnOneEndpointAreRejected() {
        // 一个协议挂载已经可以声明多个 HTTP 上下文，同一端点无需重复挂载同一协议。
        ProtocolConfig first = ProtocolConfig.newConfig("orders", "http", Collections.emptyMap());
        ProtocolConfig second = ProtocolConfig.newConfig("ORDERS", "https", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("http");
        Map<String, String> options = Map.of();

        // 大小写不同仍属于同一协议，构建端点时立即拒绝重复配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("endpoint", address, options, List.of(first, second), true);
        });
        assertTrue(failure.getMessage().contains("Duplicate protocol"));
        assertTrue(failure.getMessage().contains("orders"));
    }

    @Test
    public void sharedPublishedSchemeDoesNotSelectAProtocol() {
        // 两个挂载共享同一个公开 scheme，协议身份仍由各自名字确定。
        ProtocolConfig first = ProtocolConfig.newConfig("first", "shared", Collections.emptyMap());
        ProtocolConfig second = ProtocolConfig.newConfig("second", "shared", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, List.of(first, second), true);

        // 地址 scheme 不属于协议查询入口，因此不返回任何挂载。
        assertNull(config.protocol("shared"));
    }

    @Test
    public void sameProtocolNameOnDifferentEndpointsKeepsIndependentPreparationState() {
        // 两个端点使用同一份协议输入，各自复制准备阶段的上下文选项。
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsf", Map.of("contextPath", "/original"));
        InterAddress address = EndpointFixture.address("tcp");
        ConnectorConfig first = new ConnectorConfig("first", address, Map.of(), List.of(protocol), false);
        ConnectorConfig second = new ConnectorConfig("second", address, Map.of(), List.of(protocol), false);
        ProtocolConfig firstProtocol = first.protocol("rsf");
        ProtocolConfig secondProtocol = second.protocol("rsf");

        // 一个端点编译自己的选项后，另一端点和用户输入仍保持原有上下文。
        firstProtocol.setOptions(Map.of("contextPath", "/first"));
        assertNotSame(firstProtocol, secondProtocol);
        assertEquals("/first", firstProtocol.option("contextPath", null));
        assertEquals("/original", secondProtocol.option("contextPath", null));
        assertEquals("/original", protocol.option("contextPath", null));
    }

    @Test
    public void publishedSchemeCannotSelectTheOnlyProtocol() {
        // 单个协议的公开地址前缀与 SPI 名字不同。
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "rsfs", Map.of());
        InterAddress address = EndpointFixture.address("tcp");
        ConnectorConfig config = new ConnectorConfig("endpoint", address, Map.of(), List.of(protocol), false);

        // 即使不存在重名协议，地址前缀也不能作为协议名使用。
        assertNull(config.protocol("rsfs"));
    }

    @Test
    public void protocolNameCannotSelectAnotherProtocolsScheme() {
        // 首个协议的地址前缀恰好等于另一个协议的名字。
        ProtocolConfig first = ProtocolConfig.newConfig("first", "second", Map.of());
        ProtocolConfig second = ProtocolConfig.newConfig("second", "http", Map.of());
        InterAddress address = EndpointFixture.address("http");
        ConnectorConfig config = new ConnectorConfig("endpoint", address, Map.of(), List.of(first, second), false);

        // 查询只比较协议名，大小写不影响第二个协议的身份。
        ProtocolConfig selected = config.protocol("SECOND");
        assertEquals("second", selected.name());
        assertEquals("http", selected.scheme());
    }
}

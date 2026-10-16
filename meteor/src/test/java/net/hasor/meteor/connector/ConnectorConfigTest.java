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
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConfigTest {
    @Test
    public void transportTypeComesFromEndpointAddress() {
        // 端点地址声明底层传输，普通选项不再携带连接器身份。
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
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
        ProtocolConfig protocol = new ProtocolConfig("rsf", "RSF", "rsf", protocolOptions);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // Builder 改动不回写协议选项，查询 scheme 仍忽略大小写。
        protocolOptions.put("maxFrameSize", "2048");
        ProtocolConfig selected = config.protocol("RsF");
        assertEquals("1024", selected.option("maxFrameSize", null));
    }

    @Test
    public void mountedProtocolsSnapshotIsDetachedFromItsBuilder() {
        // 使用可变列表提供协议挂载。
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
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
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
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
        ProtocolConfig protocol = new ProtocolConfig("rsf", "RSF", "rsf", options);
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
    public void duplicatePublishedSchemesReceiveIndependentMounts() {
        // 同一个端点可以通过完整路由区分具有相同公开 scheme 的协议挂载。
        ProtocolConfig first = new ProtocolConfig("first", "rsf", "rsf", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("second", "RSF", "rsf", Collections.emptyMap());
        List<ProtocolConfig> protocols = Arrays.asList(first, second);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();

        // 端点为两个挂载分配不同的不透明 ID，不再把 scheme 当作内部主键。
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, true);
        List<ProtocolConfig> mounted = List.copyOf(config.protocols());
        assertEquals(2, mounted.size());
        assertNotEquals(mounted.get(0).mountId(), mounted.get(1).mountId());
        assertEquals(Set.of("rsf"), new HashSet<>(mounted.stream().map(ProtocolConfig::scheme).toList()));
    }

    @Test
    public void duplicateLogicalProtocolsOnOneEndpointAreRejected() {
        // 一个协议挂载已经可以声明多个 HTTP 上下文，同一端点无需重复挂载同一逻辑协议。
        ProtocolConfig first = new ProtocolConfig("orders", "http", "httpa", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("ORDERS", "https", "httpa", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("http");
        Map<String, String> options = Map.of();

        // 大小写不同也属于同一逻辑协议，构建端点时立即拒绝含糊配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("endpoint", address, options, List.of(first, second), true);
        });
        assertTrue(failure.getMessage().contains("Duplicate logical protocol"));
        assertTrue(failure.getMessage().contains("ORDERS"));
    }

    @Test
    public void ambiguousPublishedSchemeDoesNotSelectAMountByOrder() {
        // 两个挂载共享同一个公开 scheme，但分别属于不同逻辑协议。
        ProtocolConfig first = new ProtocolConfig("first", "shared", "rsf", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("second", "shared", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, List.of(first, second), true);

        // scheme 兼容查询存在歧义时必须显式失败，调用方可以改用 mountId。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            config.protocol("shared");
        });
        assertTrue(failure.getMessage().contains("Ambiguous"));
    }

    @Test
    public void exactMountIdentityResolvesSharedPublishedScheme() {
        // 两个逻辑协议共享公开 scheme，端点仍为每个挂载保留唯一身份。
        ProtocolConfig first = new ProtocolConfig("first", "shared", "rsf", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("second", "shared", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of();
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, List.of(first, second), true);
        ProtocolConfig expected = config.protocols().iterator().next();

        // 精确 mountId 跳过别名歧义并返回对应挂载。
        assertSame(expected, config.protocol(expected.mountId()));
    }
}

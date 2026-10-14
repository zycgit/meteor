/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConfigTest {
    @Test
    public void connectorOptionsSnapshotIsDetachedFromItsBuilder() {
        // 使用可变选项构建连接器配置快照。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "TCP");
        options.put("connectTimeout", "1200");
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // 修改构建选项不会改变快照中的连接超时。
        options.put("connectTimeout", "2400");
        assertEquals(1200, config.connectTimeout());
    }

    @Test
    public void protocolOptionsSnapshotIsDetachedFromItsBuilder() {
        // 挂载协议复制可变选项，再加入连接器配置。
        Map<String, String> protocolOptions = new HashMap<>();
        protocolOptions.put("maxFrameSize", "1024");
        ProtocolConfig protocol = new ProtocolConfig("rsf", "RSF", "rsf", protocolOptions);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of("listenType", "TCP");
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
        Map<String, String> options = Map.of("listenType", "TCP");
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);

        // 清空原列表后，连接器仍保留构建时的挂载。
        protocols.clear();
        assertEquals(1, config.protocols().size());
        assertSame(protocol, config.protocol("rsf"));
    }

    @Test
    public void connectorOptionsAreReadOnly() {
        // 配置快照公开自己的选项视图。
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of("listenType", "TCP");
        List<ProtocolConfig> protocols = Collections.singletonList(protocol);
        ConnectorConfig config = new ConnectorConfig("endpoint", address, options, protocols, false);
        Map<String, String> exposed = config.options();

        // 选项视图禁止调用方删除配置。
        assertThrows(UnsupportedOperationException.class, exposed::clear);
    }

    @Test
    public void mountedProtocolsAreReadOnly() {
        // 配置快照公开协议挂载集合。
        ProtocolConfig protocol = new ProtocolConfig("rsf", "rsf", "rsf", Collections.emptyMap());
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of("listenType", "TCP");
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
    public void emptyProtocolMountsAreRejected() {
        // 端点配置没有任何协议挂载。
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of("listenType", "tcp");
        List<ProtocolConfig> protocols = Collections.emptyList();

        // 构建阶段明确报告至少需要一个协议。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("endpoint", address, options, protocols, true);
        });
        assertTrue(failure.getMessage().contains("at least one"));
    }

    @Test
    public void caseInsensitiveDuplicateProtocolMountsAreRejected() {
        // 两个挂载使用仅大小写不同的 scheme。
        ProtocolConfig first = new ProtocolConfig("first", "rsf", "rsf", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("second", "RSF", "rsf", Collections.emptyMap());
        List<ProtocolConfig> protocols = Arrays.asList(first, second);
        InterAddress address = EndpointFixture.address("tcp");
        Map<String, String> options = Map.of("listenType", "tcp");

        // 构建阶段拒绝歧义路由，异常明确说明重复挂载。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            new ConnectorConfig("endpoint", address, options, protocols, true);
        });
        assertTrue(failure.getMessage().contains("Duplicate"));
    }
}

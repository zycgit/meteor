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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过已组装的传输与协议端点验证公开入站生命周期。 */
public class ConnectorConnectionsTest {
    @Test
    public void stoppingInboundSuspendsThePublishedAddress() throws Exception {
        // 通过组装后的 RSF 端点打开真实 TCP 监听器。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            manager.startInbound();
            assertNotNull(manager.getListenAddress("rsf"));

            // 停止入站接纳后，调用方不能再取得暂停的监听地址。
            manager.stopInbound();
            assertNull(manager.getListenAddress("rsf"));
        }
    }

    @Test
    public void startingInboundResumesTheExistingAddress() throws Exception {
        // 暂停入站接纳前记录首次绑定地址。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            manager.startInbound();
            InterAddress first = manager.getListenAddress("rsf");
            manager.stopInbound();

            // 重新启动入站接纳时复用原监听器，不打开另一个端口。
            manager.startInbound();
            InterAddress resumed = manager.getListenAddress("rsf");
            assertEquals(first, resumed);
        }
    }

    @Test
    public void closingManagerRemovesThePublishedAddress() throws Exception {
        // 打开监听器后关闭持有它的管理器。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            manager.startInbound();
            assertNotNull(manager.getListenAddress("rsf"));
            manager.close();

            // 管理器清理端点路由后不再保留公开地址。
            assertNull(manager.getListenAddress("rsf"));
            assertFalse(manager.isInitialized());
        }
    }

    @Test
    public void endpointAndProtocolNameKeepOutgoingChannelsIndependent() throws Exception {
        // 客户端两个端点挂载同名协议，服务端打开真实 RSF 监听器。
        ConnectorConfig serverConfig = EndpointFixture.rsfEndpoint("server", true);
        ConnectorConfig firstConfig = EndpointFixture.rsfEndpoint("first", false);
        ConnectorConfig secondConfig = EndpointFixture.rsfEndpoint("second", false);
        try (ConnectorManager server = EndpointFixture.manager(serverConfig); //
             ConnectorManager client = EndpointFixture.manager(firstConfig, secondConfig)) {
            server.init();
            client.init();
            server.startInbound();

            // 协议名相同时，连接器名称仍把两个出站通道定位到各自端点。
            InterAddress target = server.getListenAddress("rsf");
            MetChannel first = client.connect("first", "rsf", target).get(3, TimeUnit.SECONDS);
            MetChannel second = client.connect("second", "rsf", target).get(3, TimeUnit.SECONDS);
            assertEquals("rsf", first.getProtocol());
            assertEquals("rsf", second.getProtocol());
            assertNotSame(first, second);
            long firstId = ((ConnectorChannel<?>) first).channelId();
            long secondId = ((ConnectorChannel<?>) second).channelId();
            assertTrue(firstId > 0);
            assertTrue(secondId > firstId);
            assertNotEquals(first.getLocal(), second.getLocal());
            assertEquals(target, first.getRemote());
            assertEquals(target, second.getRemote());
        }
    }

    @Test
    public void protocolNameConnectsThroughItsPublishedAddressScheme() throws Exception {
        // 协议名仍为 rsf，地址前缀单独配置，连接时同时匹配这两个属性。
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "published-rsf", Map.of());
        Map<String, String> options = Map.of("workerThread", "1");
        ConnectorConfig incoming = new ConnectorConfig("server", address, options, List.of(protocol), true);
        ConnectorConfig outgoing = new ConnectorConfig("client", address, options, List.of(protocol), false);

        try (ConnectorManager server = EndpointFixture.manager(incoming); //
             ConnectorManager client = EndpointFixture.manager(outgoing)) {
            server.init();
            client.init();
            server.startInbound();

            // 用户按协议名选择，真实通道地址保留独立的公开前缀。
            InterAddress target = server.getListenAddress("rsf");
            MetChannel channel = client.connect(target, "RSF").get(3, TimeUnit.SECONDS);
            assertEquals("published-rsf", target.getSchema());
            assertEquals("rsf", channel.getProtocol());
            assertEquals(target, channel.getRemote());
        }
    }

    @Test
    public void connectRejectsAnAddressSchemeAsProtocolName() throws Exception {
        // 仅配置出站端点，目标地址的前缀与协议名不同。
        InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", "published-rsf", Map.of());
        ConnectorConfig config = new ConnectorConfig("client", local, Map.of(), List.of(protocol), false);
        InterAddress target = new InterAddress("published-rsf", "127.0.0.1", 1, "test");

        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();

            // 将地址前缀当作协议参数时，必须在网络连接前报告协议未配置。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                manager.connect(target, "published-rsf").get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof MetException);
            assertEquals(ProtocolStatus.ProtocolUndefined, ((MetException) failure.getCause()).getStatus());
        }
    }
}

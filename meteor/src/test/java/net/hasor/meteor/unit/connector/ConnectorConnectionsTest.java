/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.ProtocolConfig;
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
            assertNotNull(manager.bindAddress("rsf"));

            // 停止入站接纳后，调用方不能再取得暂停的监听地址。
            manager.stopInbound();
            assertNull(manager.bindAddress("rsf"));
        }
    }

    @Test
    public void startingInboundResumesTheExistingAddress() throws Exception {
        // 暂停入站接纳前记录首次绑定地址。
        ConnectorConfig config = EndpointFixture.rsfEndpoint("server", true);
        try (ConnectorManager manager = EndpointFixture.manager(config)) {
            manager.init();
            manager.startInbound();
            InterAddress first = manager.bindAddress("rsf");
            manager.stopInbound();

            // 重新启动入站接纳时复用原监听器，不打开另一个端口。
            manager.startInbound();
            InterAddress resumed = manager.bindAddress("rsf");
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
            assertNotNull(manager.bindAddress("rsf"));
            manager.close();

            // 管理器清理端点路由后不再保留公开地址。
            assertNull(manager.bindAddress("rsf"));
            assertFalse(manager.isInitialized());
        }
    }

    @Test
    public void exactMountIdentityConnectsTheSelectedEndpoint() throws Exception {
        // 客户端保存 endpoint 内部的精确挂载身份，服务端打开真实 RSF 监听器。
        ConnectorConfig serverConfig = EndpointFixture.rsfEndpoint("server", true);
        ConnectorConfig clientConfig = EndpointFixture.rsfEndpoint("client", false);
        try (ConnectorManager server = EndpointFixture.manager(serverConfig); ConnectorManager client = EndpointFixture.manager(clientConfig)) {
            server.init();
            client.init();
            server.startInbound();
            ProtocolConfig mount = clientConfig.protocols().iterator().next();

            // 精确连接入口直接使用 mountId，不再退回 scheme 或逻辑协议二次选择。
            InterAddress target = server.bindAddress("rsf");
            MetChannel channel = client.connect("client", mount.mountId(), target).get(3, TimeUnit.SECONDS);
            assertEquals("rsf", channel.getProtocol());
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.MetChannel;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConnectionsTest {
    @Test
    public void connectorOwnsIncomingAndOutgoingChannels() throws Exception {
        // 分别创建出站与入站通道，交由同一真实连接器登记所有权。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            Future<MetChannel> connecting = fixture.manager.connect(EndpointFixture.address("tcp"));
            EndpointFixture.Channel outgoing = (EndpointFixture.Channel) connecting.get(5, TimeUnit.SECONDS);
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            EndpointFixture.Channel incoming = owner.channel();
            assertNotEquals(outgoing.getChannelId(), incoming.getChannelId());

            // 关闭连接器时，两种方向的通道都排空后释放。
            owner.close();
            assertTrue(outgoing.drained);
            assertTrue(incoming.drained);
            assertTrue(owner.destroyed);
        }
    }

    @Test
    public void closingListenerLeavesChannelsUsable() throws Exception {
        // 端点同时拥有监听器和已建立的出站通道。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            fixture.manager.bind("tcp").get(5, TimeUnit.SECONDS);
            MetChannel channel = fixture.manager.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS);
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");

            // 关闭监听只停止入站接受，保留已有通道与后续出站连接。
            owner.closeBind();
            assertTrue(channel.isActive());
            assertFalse(owner.listen.isActive());
            Throwable rejectedBind = fixture.manager.bind("tcp").getCause();
            assertTrue(rejectedBind instanceof IllegalStateException);
            MetChannel outgoing = fixture.manager.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS);
            assertTrue(outgoing.isActive());
        }
    }

    @Test
    public void connectorCloseWaitsForSubmittedWrites() throws Exception {
        // 模拟已提交但尚未完成的写入，以排空信号协调关闭线程。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            Future<MetChannel> connecting = fixture.manager.connect(EndpointFixture.address("tcp"));
            EndpointFixture.Channel channel = (EndpointFixture.Channel) connecting.get(5, TimeUnit.SECONDS);
            channel.pending = true;
            EndpointFixture.Endpoint endpoint = fixture.endpoints.get("tcp");

            // 关闭已经进入排空阶段时，传输尚未销毁且拒绝新的写入。
            Thread close = new Thread(fixture.manager::close);
            close.start();
            try {
                assertTrue(channel.drainStarted.await(2, TimeUnit.SECONDS));
                assertFalse(endpoint.destroyed);
                assertNotNull(channel.sendData(null).getCause());
            } finally {
                // 即使断言失败也放行关闭线程，避免测试结束后遗留阻塞任务。
                channel.finish();
                close.join(2000);
            }

            // 写入完成后关闭线程退出，传输资源最终释放。
            assertFalse(close.isAlive());
            assertTrue(endpoint.destroyed);
        }
    }

    @Test
    public void drainFailureHardClosesTheChannelAndStillClosesItsPeers() throws Exception {
        // 同一端点登记健康通道和排空时抛错的通道。
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            Future<MetChannel> connecting = fixture.manager.connect(EndpointFixture.address("tcp"));
            EndpointFixture.Channel healthy = (EndpointFixture.Channel) connecting.get(5, TimeUnit.SECONDS);
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            EndpointFixture.Channel broken = new EndpointFixture.Channel(owner, fixture.manager.nextConnectionId()) {
                @Override
                public Future<MetChannel> drainAndClose() {
                    throw new IllegalStateException("drain failed");
                }
            };
            owner.receiveChannel(broken);

            // 排空失败的通道硬关闭，同时继续关闭其它通道与端点。
            owner.close();
            assertFalse(broken.isActive());
            assertTrue(healthy.drained);
            assertFalse(healthy.isActive());
            assertTrue(owner.destroyed);
        }
    }

}

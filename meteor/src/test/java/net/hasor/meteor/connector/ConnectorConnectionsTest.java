/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConnectionsTest {
    static Throwable failure(Future<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected operation failure");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    @Test
    public void connectorOwnsIncomingAndOutgoingChannels() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            Future<MetChannel> connecting = fixture.manager.connect(EndpointFixture.address("tcp"));
            EndpointFixture.Channel outgoing = (EndpointFixture.Channel) connecting.get(5, TimeUnit.SECONDS);
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            EndpointFixture.Channel incoming = owner.channel();
            assertNotEquals(outgoing.getChannelId(), incoming.getChannelId());
            owner.close();
            assertTrue(outgoing.drained);
            assertTrue(incoming.drained);
            assertTrue(owner.destroyed);
        }
    }

    @Test
    public void closingListenerLeavesChannelsUsable() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            fixture.manager.bind("tcp").get(5, TimeUnit.SECONDS);
            MetChannel channel = fixture.manager.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS);
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            owner.closeBind();
            assertTrue(channel.isActive());
            assertFalse(owner.listen.isActive());
            assertNotNull(failure(fixture.manager.bind("tcp")));
            assertTrue(fixture.manager.connect(EndpointFixture.address("tcp")).get(5, TimeUnit.SECONDS).isActive());
        }
    }

    @Test
    public void connectorCloseWaitsForSubmittedWrites() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            Future<MetChannel> connecting = fixture.manager.connect(EndpointFixture.address("tcp"));
            EndpointFixture.Channel channel = (EndpointFixture.Channel) connecting.get(5, TimeUnit.SECONDS);
            channel.pending = true;
            Thread close = new Thread(fixture.manager::close);
            close.start();
            try {
                assertTrue(channel.drainStarted.await(2, TimeUnit.SECONDS));
                assertFalse(fixture.endpoints.get("tcp").destroyed);
                assertNotNull(channel.sendData(null).getCause());
            } finally {
                // 即使断言失败也放行关闭线程，避免测试结束后遗留阻塞任务。
                channel.finish();
                close.join(2000);
            }
            assertFalse(close.isAlive());
            assertTrue(fixture.endpoints.get("tcp").destroyed);
        }
    }

    @Test
    public void drainFailureHardClosesTheChannelAndStillClosesItsPeers() throws Exception {
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
            owner.fireChannelConnected(broken);
            owner.close();
            assertFalse(broken.isActive());
            assertTrue(healthy.drained);
            assertFalse(healthy.isActive());
            assertTrue(owner.destroyed);
        }
    }

}

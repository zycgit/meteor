/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
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
            EndpointFixture.Channel outgoing = (EndpointFixture.Channel) fixture.manager.connect(EndpointFixture.address("tcp")).get();
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
            fixture.manager.bind("tcp").get();
            RsfChannel channel = fixture.manager.connect(EndpointFixture.address("tcp")).get();
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            owner.closeBind();
            assertTrue(channel.isActive());
            assertFalse(owner.listen.isActive());
            assertNotNull(failure(fixture.manager.bind("tcp")));
            assertTrue(fixture.manager.connect(EndpointFixture.address("tcp")).get().isActive());
        }
    }

    @Test
    public void connectorCloseWaitsForSubmittedWrites() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Channel channel = (EndpointFixture.Channel) fixture.manager.connect(EndpointFixture.address("tcp")).get();
            channel.pending = true;
            Thread close = new Thread(fixture.manager::close);
            close.start();
            assertTrue(channel.drainStarted.await(2, TimeUnit.SECONDS));
            assertFalse(fixture.endpoints.get("tcp").destroyed);
            assertNotNull(channel.sendData(null).getCause());
            channel.finish();
            close.join(2000);
            assertFalse(close.isAlive());
            assertTrue(fixture.endpoints.get("tcp").destroyed);
        }
    }

    @Test
    public void drainFailureHardClosesTheChannelAndStillClosesItsPeers() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Channel healthy = (EndpointFixture.Channel) fixture.manager.connect(EndpointFixture.address("tcp")).get();
            EndpointFixture.Endpoint owner = fixture.endpoints.get("tcp");
            EndpointFixture.Channel broken = new EndpointFixture.Channel(owner, fixture.manager.nextConnectionId()) {
                @Override
                public Future<RsfChannel> drainAndClose() {
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

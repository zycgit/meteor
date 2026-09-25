/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConnectionsTest {
    @Test
    public void acceptedAndOutgoingChannelsShareTheIdRegistry() throws Exception {
        try (Harness host = new Harness()) {
            Channel incoming = host.connector.accept();
            RsfChannel outgoing = host.manager.connect(host.config).get();
            assertSame(host.connector, incoming.getConnector());
            assertSame(host.connector, outgoing.getConnector());
            assertNotEquals(incoming.getChannelId(), outgoing.getChannelId());
            assertSame(incoming, host.manager.findConnection(incoming.getChannelId()));
            assertSame(outgoing, host.manager.findConnection(outgoing.getChannelId()));
            assertEquals(2, host.manager.getConnections().size());
            incoming.close();
            assertNull(host.manager.findConnection(incoming.getChannelId()));
            assertSame(outgoing, host.manager.findConnection(outgoing.getChannelId()));
            outgoing.close();
            assertTrue(host.manager.getConnections().isEmpty());
        }
    }

    @Test
    public void channelClosedEventOnlyRemovesMatchingOwnerAndChannel() throws Exception {
        try (Harness host = new Harness()) {
            Channel channel = host.connector.accept();
            Channel stale = new Channel(channel.getChannelId(), host.connector);
            host.connector.fireChannelClosed(stale);
            assertSame(channel, host.manager.findConnection(channel.getChannelId()));

            ConnectorConfig other = new ConnectorConfig("other", host.config.address(), Collections.singletonMap("listenType", "memory"));
            host.manager.prepare(other, TestConnector::new);
            host.manager.bind(other).get();
            TestConnector otherConnector = (TestConnector) host.manager.find("other");
            Channel unrelated = new Channel(channel.getChannelId(), otherConnector);
            assertSame(otherConnector, unrelated.getConnector());
            otherConnector.fireChannelClosed(unrelated);
            assertSame(channel, host.manager.findConnection(channel.getChannelId()));

            channel.close();
            host.connector.fireChannelClosed(channel);
            assertTrue(host.manager.getConnections().isEmpty());
        }
    }

    @Test
    public void channelKeepsTheIdAssignedByItsManager() throws Exception {
        try (Harness host = new Harness()) {
            long id = host.manager.nextConnectionId();
            Channel channel = new Channel(id, host.connector);
            host.connector.fireChannelConnected(channel);
            assertEquals(id, channel.getChannelId());
            assertSame(channel, host.manager.findConnection(id));
            assertTrue(host.connector.accept().getChannelId() > id);
        }
    }

    @Test
    public void repeatedConnectCreatesIndependentConnectionsEvenToTheSameTarget() throws Exception {
        try (Harness host = new Harness()) {
            RsfChannel first = host.manager.connect(host.config).get();
            RsfChannel second = host.manager.connect(host.config).get();
            assertNotSame(first, second);
            assertNotEquals(first.getChannelId(), second.getChannelId());
            assertEquals(2, host.manager.getConnections().size());
            assertEquals(1, host.created);
        }
    }

    @Test
    public void requestFailureDoesNotRemoveAnOtherwiseUsableConnection() throws Exception {
        try (Harness host = new Harness()) {
            Channel channel = host.connector.accept();
            host.manager.onFailure(channel, 1, new ThrowPayload(new IOException("request timeout")));
            assertSame(channel, host.manager.findConnection(channel.getChannelId()));
            assertTrue(channel.isActive());
            channel.close();
            assertNull(host.manager.findConnection(channel.getChannelId()));
        }
    }

    @Test
    public void providerFailureDoesNotRemoveExistingConnections() throws Exception {
        try (Harness host = new Harness()) {
            Channel incoming = host.connector.accept();
            IOException cause = new IOException("connect failed");
            host.connector.failure = cause;
            assertSame(cause, failure(host.manager.connect(host.config)));
            assertEquals(Collections.singletonList(incoming), host.manager.getConnections());
            host.connector.failure = null;
            assertNotNull(host.manager.connect(host.config).get());
        }
    }

    @Test
    public void closeOwnsBothDirectionsAndRemovesEveryConnection() throws Exception {
        Harness host = new Harness();
        Channel incoming = host.connector.accept();
        Channel outgoing = (Channel) host.manager.connect(host.config).get();
        host.close();
        host.close();
        assertEquals(1, incoming.closes);
        assertEquals(1, outgoing.closes);
        assertEquals(1, host.connector.destroyed);
        assertTrue(host.manager.getConnections().isEmpty());
        assertTrue(failure(host.manager.connect(host.config)) instanceof IllegalStateException);
    }

    @Test
    public void lateConnectionAndOldCallbacksCannotAffectRestartedManager() throws Exception {
        try (Harness host = new Harness()) {
            TestConnector old = host.connector;
            Channel oldChannel = old.accept();
            host.close();
            host.start();
            Channel current = host.connector.accept();
            assertTrue(current.getChannelId() > oldChannel.getChannelId());
            Channel late = old.accept();
            assertFalse(late.isActive());
            assertNull(host.manager.findConnection(late.getChannelId()));
            old.fireChannelClosed(oldChannel);
            assertEquals(Collections.singletonList(current), host.manager.getConnections());
        }
    }

    @Test
    public void individualConnectorCloseOnlyClosesItsOwnConnections() throws Exception {
        try (Harness host = new Harness()) {
            Channel first = host.connector.accept();
            ConnectorConfig other = new ConnectorConfig("other", host.config.address(), Collections.singletonMap("listenType", "memory"));
            host.manager.prepare(other, TestConnector::new);
            host.manager.bind(other).get();
            TestConnector secondConnector = (TestConnector) host.manager.find("other");
            Channel second = secondConnector.accept();
            assertNotEquals(first.getChannelId(), second.getChannelId());
            host.connector.close();
            assertFalse(first.isActive());
            assertTrue(second.isActive());
            assertEquals(Collections.singletonList(second), host.manager.getConnections());
        }
    }

    @Test
    public void closedConnectorCannotRegisterIntoItsReplacement() throws Exception {
        try (Harness host = new Harness()) {
            TestConnector old = host.connector;
            Channel oldChannel = old.accept();
            old.close();
            assertNull(host.manager.find(host.config.name()));
            RsfChannel current = host.manager.connect(host.config).get();
            assertNotSame(old, host.connector);
            assertEquals(2, host.created);
            Channel late = old.accept();
            assertFalse(late.isActive());
            old.fireChannelClosed(oldChannel);
            assertSame(current, host.manager.findConnection(current.getChannelId()));
            assertEquals(Collections.singletonList(current), host.manager.getConnections());
        }
    }

    @Test
    public void cleanupFailureStillClosesOtherConnectionsAndConnector() throws Exception {
        Harness host = new Harness();
        Channel bad = host.connector.accept();
        Channel good = host.connector.accept();
        bad.closeFailure = new IllegalStateException("close failed");
        host.close();
        assertEquals(1, bad.closes);
        assertEquals(1, good.closes);
        assertEquals(1, host.connector.destroyed);
        assertTrue(host.manager.getConnections().isEmpty());
        host.close();
    }

    static Throwable failure(Future<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected failure");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext());
        private final ConnectorConfig      config  = new ConnectorConfig("test", new InterAddress("memory", "localhost", 1, "default"), Collections.singletonMap("listenType", "memory"));
        private       TestConnector        connector;
        private       int                  created;

        private Harness() throws Exception {
            this.start();
        }

        private void start() throws Exception {
            this.manager.prepare(this.config, (connectorConfig, connectorManager) -> {
                this.created++;
                this.connector = new TestConnector(connectorConfig, connectorManager);
                return this.connector;
            });
            this.manager.init();
            this.manager.bind(this.config).get();
        }

        public void close() {
            this.manager.close();
        }
    }

    private static final class TestConnector extends AbstractConnector {
        private IOException failure;
        private int         destroyed;

        private TestConnector(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            super(connectorConfig, connectorManager);
        }

        protected void initialize() {
        }

        protected Future<RsfListen> listen(String type, InterAddress address, ReceivedListener listener) {
            return new BasicFuture<>(new AbstractRsfListen(type, address, listener) {
                private boolean active = true;

                public boolean isActive() {
                    return this.active;
                }

                public void close() {
                    this.active = false;
                }
            });
        }

        private Channel accept() {
            Channel channel = new Channel(this.manager.nextConnectionId(), this);
            this.fireChannelConnected(channel);
            return channel;
        }

        protected Future<RsfChannel> openSession(String type, InterAddress target, ReceivedListener listener) {
            BasicFuture<RsfChannel> result = new BasicFuture<>();
            if (this.failure != null) {
                result.failed(this.failure);
            } else {
                result.completed(this.accept());
            }
            return result;
        }

        protected void doClose() {
            this.destroyed++;
        }
    }

    private static final class Channel extends AbstractRsfChannel {
        private final TestConnector    owner;
        private       int              closes;
        private       RuntimeException closeFailure;

        private Channel(long id, TestConnector owner) {
            super(owner, id, owner.manager);
            this.owner = owner;
        }

        public InterAddress getRemote() {
            return this.owner.config().address();
        }

        public InterAddress getLocal() {
            return this.owner.config().address();
        }

        public boolean isActive() {
            return this.closes == 0;
        }

        public BasicFuture<RsfChannel> sendData(Payload payload) {
            return new BasicFuture<>(this);
        }

        public Future<RsfChannel> drainAndClose() {
            return this.close();
        }

        public Future<RsfChannel> close() {
            BasicFuture<RsfChannel> result = new BasicFuture<>();
            if (this.closes == 0) {
                this.closes++;
                this.owner.fireChannelClosed(this);
            }
            if (this.closeFailure == null) {
                result.completed(this);
            } else {
                result.failed(this.closeFailure);
            }
            return result;
        }
    }
}

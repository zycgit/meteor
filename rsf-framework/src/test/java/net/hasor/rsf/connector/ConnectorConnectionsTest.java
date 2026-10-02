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
            RsfChannel outgoing = host.manager.connect(host.config.address()).get();
            assertSame(host.connector, incoming.getConnector());
            assertSame(host.connector, outgoing.getConnector());
            assertNotEquals(incoming.getChannelId(), outgoing.getChannelId());
            incoming.close();
            assertFalse(incoming.isActive());
            assertTrue(outgoing.isActive());
            host.close();
            assertFalse(outgoing.isActive());
            assertEquals(0, incoming.drains);
            assertEquals(1, ((Channel) outgoing).drains);
        }
    }

    @Test
    public void channelClosedEventOnlyRemovesMatchingOwnerAndChannel() throws Exception {
        try (Harness host = new Harness()) {
            Channel channel = host.connector.accept();
            Channel stale = new Channel(channel.getChannelId(), host.connector);
            host.connector.fireChannelClosed(stale);
            assertTrue(channel.isActive());

            ConnectorConfig other = new ConnectorConfig("other", host.config.address(), Collections.singletonMap("listenType", "memory"));
            host.manager.prepare(other, TestConnector::new);
            host.manager.bind(other).get();
            TestConnector otherConnector = (TestConnector) host.manager.find("other");
            Channel unrelated = new Channel(channel.getChannelId(), otherConnector);
            assertSame(otherConnector, unrelated.getConnector());
            otherConnector.fireChannelClosed(unrelated);
            assertTrue(channel.isActive());

            host.close();
            assertEquals(1, channel.drains);
            host.connector.fireChannelClosed(channel);
            assertEquals(1, channel.closes);
        }
    }

    @Test
    public void duplicateChannelIdCannotReplaceTheRegisteredConnection() throws Exception {
        try (Harness host = new Harness()) {
            Channel original = host.connector.accept();
            Channel duplicate = new Channel(original.getChannelId(), host.connector);
            try {
                host.connector.fireChannelConnected(duplicate);
                fail("Duplicate IDs must not replace a registered channel");
            } catch (IllegalStateException expected) {
                assertTrue(original.isActive());
            }
            duplicate.close();
            host.close();
            assertEquals(1, original.drains);
            assertEquals(0, duplicate.drains);
        }
    }

    @Test
    public void channelKeepsTheIdAssignedByItsManager() throws Exception {
        try (Harness host = new Harness()) {
            long id = host.manager.nextConnectionId();
            Channel channel = new Channel(id, host.connector);
            host.connector.fireChannelConnected(channel);
            assertEquals(id, channel.getChannelId());
            assertTrue(host.connector.accept().getChannelId() > id);
            host.close();
            assertEquals(1, channel.drains);
        }
    }

    @Test
    public void repeatedConnectCreatesIndependentConnectionsEvenToTheSameTarget() throws Exception {
        try (Harness host = new Harness()) {
            RsfChannel first = host.manager.connect(host.config.address()).get();
            RsfChannel second = host.manager.connect(host.config.address()).get();
            assertNotSame(first, second);
            assertNotEquals(first.getChannelId(), second.getChannelId());
            assertEquals(1, host.created);
            first.close();
            assertTrue(second.isActive());
            host.close();
            assertFalse(second.isActive());
        }
    }

    @Test
    public void requestFailureDoesNotRemoveAnOtherwiseUsableConnection() throws Exception {
        try (Harness host = new Harness()) {
            Channel channel = host.connector.accept();
            host.manager.onFailure(channel, 1, new ThrowPayload(new IOException("request timeout")));
            assertTrue(channel.isActive());
            host.close();
            assertEquals(1, channel.drains);
        }
    }

    @Test
    public void providerFailureDoesNotRemoveExistingConnections() throws Exception {
        try (Harness host = new Harness()) {
            Channel incoming = host.connector.accept();
            IOException cause = new IOException("connect failed");
            host.connector.failure = cause;
            assertSame(cause, failure(host.manager.connect(host.config.address())));
            assertTrue(incoming.isActive());
            host.connector.failure = null;
            RsfChannel outgoing = host.manager.connect(host.config.address()).get();
            host.close();
            assertEquals(1, incoming.drains);
            assertFalse(outgoing.isActive());
        }
    }

    @Test
    public void closeOwnsBothDirectionsAndRemovesEveryConnection() throws Exception {
        Harness host = new Harness();
        Channel incoming = host.connector.accept();
        Channel outgoing = (Channel) host.manager.connect(host.config.address()).get();
        host.close();
        host.close();
        assertEquals(1, incoming.closes);
        assertEquals(1, outgoing.closes);
        assertEquals(1, host.connector.destroyed);
        assertEquals(1, incoming.drains);
        assertEquals(1, outgoing.drains);
        assertTrue(failure(host.manager.connect(host.config.address())) instanceof IllegalStateException);
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
            old.fireChannelClosed(oldChannel);
            assertTrue(current.isActive());
            host.close();
            assertEquals(1, current.drains);
            assertEquals(0, late.drains);
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
            host.close();
            assertEquals(1, first.drains);
            assertEquals(1, second.drains);
        }
    }

    @Test
    public void closedConnectorCannotRegisterIntoItsReplacement() throws Exception {
        try (Harness host = new Harness()) {
            TestConnector old = host.connector;
            Channel oldChannel = old.accept();
            old.close();
            assertNull(host.manager.find(host.config.name()));
            RsfChannel current = host.manager.connect(host.config.address()).get();
            assertNotSame(old, host.connector);
            assertEquals(2, host.created);
            Channel late = old.accept();
            assertFalse(late.isActive());
            old.fireChannelClosed(oldChannel);
            assertTrue(current.isActive());
            host.close();
            assertFalse(current.isActive());
            assertEquals(1, ((Channel) current).drains);
            assertEquals(0, late.drains);
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
        assertEquals(1, bad.drains);
        assertEquals(1, good.drains);
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
        private final ConnectorConfig      config  = new ConnectorConfig("test", new InterAddress("memory", "localhost", 1, "default"), Collections.singletonMap("listenType", "memory"));
        private final TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext(this.config));
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

        protected Future<RsfListen> listen(InterAddress address, ReceivedListener listener) {
            String type = this.config.listenType();
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

        protected Future<RsfChannel> openSession(InterAddress target, ReceivedListener listener) {
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
        private       int              drains;
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
            this.drains++;
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

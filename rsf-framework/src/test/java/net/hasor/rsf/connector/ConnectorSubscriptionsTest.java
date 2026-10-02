/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorSubscriptionsTest {
    @Test
    public void dispatchesOriginalMessagesInRegistrationOrderWithoutManagerLock() throws Exception {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            RequestPayload request = new RequestPayload();
            ResponsePayload response = new ResponsePayload();
            IOException failure = new IOException("disconnected");
            RsfChannel source = new TestChannel();
            List<String> received = new ArrayList<>();
            Thread deliveryThread = Thread.currentThread();
            manager.subscribe((channel, id, payload) -> {
                assertSame(source, channel);
                assertEquals(42, id);
                assertSame(deliveryThread, Thread.currentThread());
                assertFalse(Thread.holdsLock(manager));
                switch (payload.getType()) {
                    case REQUEST:
                        assertSame(request, payload);
                        received.add("request");
                        break;
                    case RESPONSE:
                        assertSame(response, payload);
                        received.add("response");
                        break;
                    case THROW:
                        assertSame(failure, ((ThrowPayload) payload).getThrowable());
                        received.add("failure");
                        break;
                }
            });
            manager.subscribe((channel, id, payload) -> received.add("observer-" + payload.getType()));
            manager.onRequest(source, 42, request);
            manager.onResponse(source, 42, response);
            manager.onFailure(source, 42, new ThrowPayload(failure));
            assertEquals(Arrays.asList("request", "observer-REQUEST", "response", "observer-RESPONSE", "failure", "observer-THROW"), received);
        }
    }

    @Test
    public void repeatedRegistrationDeliversOncePerEntry() {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            ConnectorSubscriber subscriber = (channel, id, payload) -> received.incrementAndGet();
            manager.subscribe(subscriber);
            manager.subscribe(subscriber);
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(2, received.get());
        }
    }

    @Test
    public void subscriberExceptionsDoNotPreventDeliveryToOtherSubscribers() {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, payload) -> {
                throw new IllegalStateException("observer");
            });
            manager.subscribe((channel, id, payload) -> received.incrementAndGet());
            manager.onRequest(new TestChannel(), 1, new RequestPayload());
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            manager.onFailure(new TestChannel(), 1, new ThrowPayload(new IOException("disconnected")));
            assertEquals(3, received.get());
        }
    }

    @Test
    public void registrationInsideCallbackOnlyAffectsFollowingNotifications() {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            List<String> received = new ArrayList<>();
            manager.subscribe((channel, id, response) -> {
                received.add("first");
                manager.subscribe((nextChannel, nextId, next) -> received.add("new"));
            });
            manager.subscribe((channel, id, response) -> received.add("second"));
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(Arrays.asList("first", "second"), received);
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(Arrays.asList("first", "second", "first", "second", "new"), received);
        }
    }

    @Test
    public void shutdownNotificationsRemainDeliverableUntilCleanupFinishes() throws Exception {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            List<Payload> received = new ArrayList<>();
            ResponsePayload response = new ResponsePayload();
            ThrowPayload failure = new ThrowPayload(new IOException("disconnected"));
            manager.subscribe((channel, id, payload) -> received.add(payload));
            ConnectorConfig config = new ConnectorConfig("closing", new InterAddress("rsf://127.0.0.1:1/default"), Collections.singletonMap("listenType", "memory"));
            manager.prepare(config, (connectorConfig, connectorManager) -> new AbstractConnector(connectorConfig, connectorManager) {
                protected void initialize() {
                }

                protected BasicFuture<RsfListen> listen(InterAddress address, ReceivedListener listener) {
                    throw new UnsupportedOperationException();
                }

                protected BasicFuture<RsfChannel> openSession(InterAddress address, ReceivedListener listener) {
                    throw new UnsupportedOperationException();
                }

                protected void doClose() {
                    this.manager.onResponse(new TestChannel(), 1, response);
                    this.manager.onFailure(new TestChannel(), 2, failure);
                    throw new IllegalStateException("cleanup failure");
                }
            });
            manager.bind(manager.config("closing").withAddress(config.address()));
            manager.close();
            assertEquals(Arrays.asList(response, failure), received);
            manager.onResponse(new TestChannel(), 1, response);
            assertEquals(2, received.size());
        }
    }

    @Test
    public void closeClearsAllSubscriptionsAndRestartNeedsNewSubscriptions() throws Exception {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.init();
            AtomicInteger received = new AtomicInteger();
            manager.subscribe((channel, id, info) -> received.incrementAndGet());
            manager.subscribe((channel, id, info) -> received.incrementAndGet());
            manager.subscribe((channel, id, error) -> received.incrementAndGet());
            manager.init();
            manager.close();
            manager.onRequest(new TestChannel(), 1, new RequestPayload());
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            manager.onFailure(new TestChannel(), 1, new ThrowPayload(new IOException("late failure")));
            assertEquals(0, received.get());
            manager.init();
            manager.subscribe((channel, id, info) -> received.incrementAndGet());
            manager.onResponse(new TestChannel(), 1, new ResponsePayload());
            assertEquals(1, received.get());
        }
    }

    private static final class TestChannel implements RsfChannel {
        public RsfConnector getConnector() {
            throw new UnsupportedOperationException();
        }

        public long getChannelId() {
            return 1;
        }

        public InterAddress getLocal() {
            throw new UnsupportedOperationException();
        }

        public InterAddress getRemote() {
            throw new UnsupportedOperationException();
        }

        public boolean isActive() {
            return true;
        }

        public BasicFuture<RsfChannel> sendData(Payload payload) {
            return new BasicFuture<>(this);
        }

        public BasicFuture<RsfChannel> close() {
            return new BasicFuture<>(this);
        }

        public BasicFuture<RsfChannel> drainAndClose() {
            return this.close();
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.payload.Payload;

/** A configured provider fixture, with no reflective access to production lifecycle state. */
final class EndpointFixture implements AutoCloseable {
    final TestConnectorManager  manager;
    final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    final List<String>          events    = Collections.synchronizedList(new ArrayList<>());
    int created;

    static InterAddress address(String scheme) {
        return new InterAddress(scheme, "127.0.0.1", 1234, "test");
    }

    static ConnectorConfig config(String name) {
        return new ConnectorConfig(name, address(name), Collections.singletonMap("listenType", "memory"), Collections.singletonList(new ProtocolConfig(name, name, name, Collections.singletonMap("listenType", "memory"))), true);
    }

    EndpointFixture(String... names) {
        ConnectorConfig[] configs = Arrays.stream(names).map(EndpointFixture::config).toArray(ConnectorConfig[]::new);
        this.manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext(configs));
        for (ConnectorConfig config : configs) {
            this.manager.prepare(config, (cfg, manager) -> {
                this.created++;
                Endpoint endpoint = new Endpoint(cfg, manager, this.events);
                this.endpoints.put(cfg.name(), endpoint);
                return endpoint;
            });
        }
        this.manager.init();
    }

    public void close() {
        this.manager.close();
    }

    static class Endpoint extends AbstractConnector {
        final List<String> events;
        BasicFuture<RsfListen> binding;
        Listen                 listen;
        boolean                destroyed;
        int                    starts;
        int                    binds;

        Endpoint(ConnectorConfig config, ConnectorManager manager, List<String> events) {
            super(config, manager);
            this.events = events;
        }

        protected void initialize() {
            this.starts++;
        }

        protected Future<RsfListen> listen(InterAddress address, ReceivedListener receiver) {
            this.binds++;
            if (this.binding != null) {
                return this.binding;
            }
            this.listen = new Listen(this, receiver);
            return new BasicFuture<>(this.listen);
        }

        protected Future<RsfChannel> openSession(InterAddress address, ReceivedListener receiver) {
            return new BasicFuture<>(this.channel());
        }

        Channel channel() {
            Channel channel = new Channel(this, this.manager.nextConnectionId());
            this.fireChannelConnected(channel);
            return channel;
        }

        protected void doClose() {
            this.destroyed = true;
            this.events.add(this.config.name() + ":closed");
        }
    }

    static final class Listen extends AbstractRsfListen {
        private final Endpoint owner;
        private       boolean  active = true;

        Listen(Endpoint owner, ReceivedListener receiver) {
            super(owner.config().listenType(), owner.config().address(), receiver);
            this.owner = owner;
        }

        public boolean isActive() {
            return this.active;
        }

        public void close() {
            this.active = false;
            this.owner.events.add(this.owner.config().name() + ":unbound");
            this.owner.onListenClosed(this);
        }
    }

    static final class Channel extends AbstractRsfChannel {
        private final Endpoint                owner;
        private final BasicFuture<RsfChannel> closed       = new BasicFuture<>();
        final         CountDownLatch          drainStarted = new CountDownLatch(1);
        volatile      boolean                 drained;
        boolean pending;

        Channel(Endpoint owner, long id) {
            super(owner, id, owner.manager);
            this.owner = owner;
        }

        public InterAddress getLocal() {
            return this.owner.config().address();
        }

        public InterAddress getRemote() {
            return this.getLocal();
        }

        public boolean isActive() {
            return !this.closed.isDone();
        }

        public Future<RsfChannel> sendData(Payload payload) {
            BasicFuture<RsfChannel> result = new BasicFuture<>();
            if (this.drained || !this.owner.acceptsWrites()) {
                result.failed(new IllegalStateException("Closing"));
            } else {
                result.completed(this);
            }
            return result;
        }

        void finish() {
            this.closed.completed(this);
            this.owner.fireChannelClosed(this);
        }

        public Future<RsfChannel> close() {
            this.finish();
            return this.closed;
        }

        public Future<RsfChannel> drainAndClose() {
            this.drained = true;
            this.drainStarted.countDown();
            if (!this.pending) {
                this.finish();
            }
            return this.closed;
        }
    }
}

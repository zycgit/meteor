/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.payload.Payload;

/** 内核 SPI 夹具：只控制传输回调，不访问生产私有状态。 */
final class EndpointFixture implements AutoCloseable {
    final TestConnectorManager  manager;
    final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    final List<String>          events    = Collections.synchronizedList(new ArrayList<>());
    int created;

    static InterAddress address(String scheme) {
        return new InterAddress(scheme, "127.0.0.1", 1234, "test");
    }

    static ConnectorConfig config(String name) {
        Map<String, String> options = Collections.singletonMap("listenType", "memory");
        ProtocolConfig protocol = new ProtocolConfig(name, name, name, options);
        return new ConnectorConfig(name, address(name), options, Collections.singletonList(protocol), true);
    }

    EndpointFixture(String... names) {
        ConnectorConfig[] configs = Arrays.stream(names).map(EndpointFixture::config).toArray(ConnectorConfig[]::new);

        // 显式装配每个连接器，使创建计数只由真实管理器的首次访问触发。
        this.manager = new TestConnectorManager(ConnectorTestSupport.sharedContext(configs));
        for (ConnectorConfig config : configs) {
            this.manager.prepare(config, (cfg, manager) -> {
                this.created++;
                Endpoint endpoint = new Endpoint(cfg, manager, this.events);
                this.endpoints.put(cfg.name(), endpoint);
                return endpoint;
            });
        }

        // 启动管理器只建立配置索引，实际端点继续延迟创建。
        this.manager.init();
    }

    public void close() {
        this.manager.close();
    }

    static class Endpoint extends AbstractConnector {
        final List<String> events;
        BasicFuture<MetListen> binding;
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

        protected Future<MetListen> listen(InterAddress address, ReceivedListener receiver) {
            this.binds++;
            if (this.binding != null) {
                return this.binding;
            }
            this.listen = new Listen(this, receiver);
            return new BasicFuture<>(this.listen);
        }

        protected Future<MetChannel> openSession(InterAddress address, ReceivedListener receiver) {
            return new BasicFuture<>(this.channel());
        }

        Channel channel() {
            Channel channel = new Channel(this, this.manager.nextConnectionId());
            this.fireChannelConnected(channel);
            return channel;
        }

        void receiveChannel(MetChannel channel) {
            this.fireChannelConnected(channel);
        }

        void channelClosed(MetChannel channel) {
            this.fireChannelClosed(channel);
        }

        void listenerClosed(MetListen listen) {
            this.onListenClosed(listen);
        }

        boolean canWrite() {
            return this.acceptsWrites();
        }

        ReceivedListener receiver() {
            return this.manager;
        }

        protected void doClose() {
            this.destroyed = true;
            this.events.add(this.config.name() + ":closed");
        }
    }

    static final class Listen extends AbstractMetListen {
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
            this.owner.listenerClosed(this);
        }
    }

    static class Channel extends AbstractMetChannel {
        private final Endpoint                owner;
        private final BasicFuture<MetChannel> closed       = new BasicFuture<>();
        final         CountDownLatch          drainStarted = new CountDownLatch(1);
        volatile      boolean                 drained;
        boolean pending;

        Channel(Endpoint owner, long id) {
            super(owner, id, owner.receiver());
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

        public Future<MetChannel> sendData(Payload payload) {
            // 写入准入服从真实端点的关闭状态，夹具只记录结果。
            BasicFuture<MetChannel> result = new BasicFuture<>();
            if (this.drained || !this.owner.canWrite()) {
                result.failed(new IllegalStateException("Closing"));
            } else {
                result.completed(this);
            }
            return result;
        }

        void finish() {
            this.closed.completed(this);
            this.owner.channelClosed(this);
        }

        public Future<MetChannel> close() {
            this.finish();
            return this.closed;
        }

        public Future<MetChannel> drainAndClose() {
            // 排空信号用于协调测试线程，pending 控制何时释放已提交写入。
            this.drained = true;
            this.drainStarted.countDown();
            if (!this.pending) {
                this.finish();
            }
            return this.closed;
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkListen;
import net.hasor.meteor.connector.transport.tcp.TcpProtocolProvider;
import net.hasor.meteor.connector.transport.udp.UdpProtocolProvider;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.payload.Payload;

/** Owns one configured endpoint, its listeners, transport connections and protocol channels. */
final class ConnectorEndpoint<M> {
    private static final Logger                          logger         = Logger.getLogger(ConnectorEndpoint.class);
    private final        PlanEndpoint<M>                 plan;
    private final        RouteTable<M>                   routes;
    private final        ConnectorManager                manager;
    private final        Map<Long, ConnectorChannel<?>>  channels       = new LinkedHashMap<>();
    private final        Map<NetworkChannel<M>, Boolean> connections    = new ConcurrentHashMap<>();
    private final        Set<NetworkChannel<M>>          closingInbound = ConcurrentHashMap.newKeySet();
    private              BasicFuture<NetworkListen>      binding;
    private              NetworkListen                   listen;
    private              NetworkConnector<M>             networkConnector;
    private volatile     State                           state          = State.NEW;
    private              boolean                         bindDisabled;

    private enum State {
        NEW,
        READY,
        CLOSED
    }

    ConnectorEndpoint(PlanEndpoint<M> plan, ConnectorManager manager) {
        this.plan = plan;
        this.manager = manager;
        this.routes = this.createRoutes();
    }

    private RouteTable<M> createRoutes() {
        // 按挂载创建路由；只有 TCP/UDP 协议适配器提供报文探测，HTTP 使用路径选路。
        Map<String, RouteDefinition<M>> definitions = new LinkedHashMap<>();
        for (ProtocolMountBinding<M> binding : this.plan.protocolBindings().values()) {
            ProtocolConfig protocol = binding.config();
            ProtocolProvider<M> provider = binding.provider();
            Function<M, RouteProbeResult> protocolProbe = this.protocolProbe(provider, protocol);
            RouteDefinition<M> definition = new RouteDefinition<>(protocol.options(), binding.routes(), protocolProbe);
            definitions.put(protocol.name(), definition);
        }

        // 路由表由端点持有，静态索引在构造时一次生成，动态查询由管理器提供。
        Function<String, RouteSelection> exactResolver = this.manager.routeResolver(this.plan.config().name());
        return new RouteTable<>(definitions, exactResolver);
    }

    private Function<M, RouteProbeResult> protocolProbe(ProtocolProvider<M> provider, ProtocolConfig config) {
        // TCP 识别连接前缀，UDP 识别完整数据报；消息类型已在计划准备时校验。
        if (provider instanceof TcpProtocolProvider tcp) {
            return message -> tcp.probeProtocol(config, (byte[]) message);
        }
        if (provider instanceof UdpProtocolProvider udp) {
            return message -> udp.probeProtocol(config, (byte[]) message);
        }
        return null;
    }

    ConnectorConfig config() {
        return this.plan.config();
    }

    ConnectorManager manager() {
        return this.manager;
    }

    String singleProtocol() {
        return this.routes.resolveProtocol(null);
    }

    synchronized boolean hasListener() {
        return this.listen != null;
    }

    synchronized InterAddress getBindAddress() {
        if (this.listen != null && this.listen.isOpen()) {
            return this.listen.getAddress();
        }
        return null;
    }

    void init() throws Exception {
        synchronized (this) {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("Connector endpoint is closed");
            }
            if (this.state == State.READY) {
                return;
            }

            this.networkConnector = this.plan.transportPlan().create(this.manager.transportRuntimeServices());
            this.state = State.READY;
        }
    }

    synchronized boolean acceptsWrites() {
        return this.state == State.READY && this.manager.isInitialized();
    }

    synchronized boolean acceptsInbound() {
        return this.state == State.READY && this.manager.acceptsInbound();
    }

    Future<NetworkListen> bind() {
        ConnectorConfig config = this.config();
        if (!config.bindEnabled()) {
            throw new IllegalStateException("Endpoint has no configured listener: " + config.name());
        }

        BasicFuture<NetworkListen> result;
        synchronized (this) {
            this.requireBindingAllowed();
            if (this.binding != null) {
                NetworkListen bound = this.binding.getResult();
                if (bound == null || bound.isOpen()) {
                    return this.binding;
                }
                if (this.listen == bound) {
                    this.listen = null;
                }
            }

            result = new BasicFuture<>();
            FutureListener<Future<NetworkListen>> discard = done -> this.forgetBinding(result);
            result.onFailed(discard).onCancel(discard);
            this.binding = result;
        }

        try {
            // 传输 bind 同步返回监听器，直接完成已经登记的共享 Future。
            NetworkListen bound = this.networkConnector.bind(config.address(), this.routes, channel -> {
                return new RouteChannelDispatcher<>(this, channel, null);
            });
            bound.onClose(() -> this.onListenClosed(bound));
            this.completeBind(result, bound);
        } catch (Throwable failure) {
            result.failed(failure);
        }
        return result;
    }

    private void requireBindingAllowed() {
        if (this.state != State.READY) {
            throw new IllegalStateException("Connector endpoint is not ready");
        }
        if (this.bindDisabled || !this.manager.allowsBindings()) {
            throw new IllegalStateException("Connector endpoint binding is disabled");
        }
    }

    private synchronized void forgetBinding(BasicFuture<NetworkListen> result) {
        if (this.binding == result) {
            this.binding = null;
        }
    }

    private void completeBind(BasicFuture<NetworkListen> result, NetworkListen bound) {
        Throwable failure = null;
        try {
            synchronized (this) {
                if (bound == null) {
                    throw new IllegalStateException("Transport returned no listener");
                }
                if (result.isDone() || !this.acceptListen(bound)) {
                    throw new IllegalStateException("Connector endpoint no longer accepts listeners");
                }
            }
            result.completed(bound);
        } catch (Throwable error) {
            failure = error;
        }

        if (bound != null && result.getResult() != bound) {
            this.onListenClosed(bound);
            try {
                bound.close();
            } catch (RuntimeException | Error cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        if (failure != null) {
            result.failed(failure);
        }
    }

    private boolean acceptListen(NetworkListen listen) {
        if (this.state != State.READY || this.bindDisabled || !this.manager.allowsBindings()) {
            return false;
        }
        this.listen = listen;
        return true;
    }

    private synchronized void onListenClosed(NetworkListen listen) {
        if (this.listen == listen) {
            this.listen = null;
        }
    }

    Future<MetChannel> connect(String protocolName, InterAddress target) {
        if (!this.acceptsWrites()) {
            return failed(new IllegalStateException("Connector endpoint is not ready for connections"));
        }

        // 协议名和地址前缀已经由 Manager 的公开连接入口统一校验。
        try {
            return this.openSession(protocolName, target);
        } catch (Exception | Error failure) {
            return failed(failure);
        }
    }

    private Future<MetChannel> openSession(String protocolName, InterAddress address) {
        // 先创建逻辑通道，让同一个 ready 覆盖底层连接和协议握手。
        ConnectorChannel<M> channel = this.createChannel();
        try {
            ChannelListenerFactory<M> factory = networkChannel -> {
                this.attachChannel(channel, protocolName, networkChannel, true);
                return new RouteChannelDispatcher<>(this, networkChannel, channel);
            };
            Future<Void> connect = this.networkConnector.connect(protocolName, address, this.routes, factory);

            channel.ready().onCancel(done -> {
                connect.cancel();
            });

            connect.onFailed(done -> {
                channel.fail(done.getCause());
            }).onCancel(done -> {
                channel.ready().cancel();
            });
        } catch (RuntimeException | Error failure) {
            channel.fail(failure);
        }
        return channel.ready();
    }

    ConnectorChannel<M> createChannel() {
        ConnectorChannel<M> channel = new ConnectorChannel<>(this);
        this.registerChannel(channel);
        return channel;
    }

    void attachChannel(ConnectorChannel<M> channel, String protocolName, NetworkChannel<M> networkChannel, boolean outbound) {
        ProtocolMountBinding<M> binding = this.plan.protocolBindings().get(protocolName);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown protocol: " + protocolName);
        }

        // 共享入站连接只关闭当前协议通道，不影响同一连接上的其他协议。
        boolean isolatedClose = this.plan.sharedRoutes() && !outbound;
        ProtocolConfig protocolConfig = binding.config();
        ProtocolNetworkChannel<M> connection = new ProtocolNetworkChannel<>(networkChannel, protocolConfig, outbound, isolatedClose);
        connection.onClose(channel::closed);
        channel.attach(connection);
    }

    ProtocolSession<M> createSession(ProtocolNetworkChannel<M> connection, ObjLongConsumer<Payload> messages) {
        ProtocolMountBinding<M> binding = this.plan.protocolBindings().get(connection.getProtocol());
        ProtocolConfig protocolConfig = binding.config();

        // 监听地址以当前协议的公开前缀传给会话，未开启监听时保持为空。
        InterAddress local = this.getBindAddress();
        InterAddress advertised = null;
        if (local != null) {
            String scheme = protocolConfig.scheme();
            advertised = new InterAddress(scheme, local.getHost(), local.getPort(), local.getFormUnit());
        }

        // 会话使用端点已经确定的配置、路由与管理器公共资源。
        ProtocolSessionContext context = new ProtocolSessionContext(//
                this.manager.context(), this.manager::schedule, advertised, connection.isOutbound(), binding.routes());
        return binding.provider().createSession(protocolConfig, context, connection, messages);
    }

    synchronized boolean registerConnection(NetworkChannel<M> channel, boolean inbound) {
        if (this.state != State.READY) {
            return false;
        }
        if (inbound && !this.manager.acceptsInbound()) {
            return false;
        }
        if (!inbound && !this.manager.isInitialized()) {
            return false;
        }

        this.connections.put(channel, inbound);
        return true;
    }

    void unregisterConnection(NetworkChannel<M> channel) {
        this.connections.remove(channel);
        this.closingInbound.remove(channel);
    }

    void registerChannel(ConnectorChannel<?> channel) {
        synchronized (this) {
            if (this.acceptsWrites()) {
                // 通道 ID 由当前 Manager 的统一分配器生成。
                this.channels.put(channel.channelId(), channel);
                return;
            }
        }
        channel.close();
    }

    synchronized void unregisterChannel(ConnectorChannel<?> channel) {
        this.channels.remove(channel.channelId(), channel);
    }

    void publishMessage(MetChannel channel, long requestId, Payload payload) {
        this.manager.publishMessage(channel, requestId, payload);
    }

    boolean acceptingInbound(NetworkChannel<M> channel) {
        return this.acceptsInbound() && !this.closingInbound.contains(channel);
    }

    void closeInbound(NetworkChannel<M> channel) {
        if (!this.closingInbound.add(channel)) {
            return;
        }

        try {
            channel.drainAndClose().onFailed(done -> {
                this.forceClose(channel, done.getCause());
            }).onCancel(done -> {
                this.forceClose(channel, new IllegalStateException("Channel drain cancelled"));
            });
        } catch (RuntimeException | Error failure) {
            this.forceClose(channel, failure);
        }
    }

    private void forceClose(NetworkChannel<M> channel, Throwable failure) {
        logger.warn("Accepted channel drain failed", failure);
        try {
            channel.close().onFailed(done -> logger.warn("Accepted channel close failed", done.getCause()));
        } catch (RuntimeException | Error cleanup) {
            logger.warn("Accepted channel close failed", cleanup);
        }
    }

    void stopInbound() {
        BasicFuture<NetworkListen> pending;
        NetworkListen current;
        synchronized (this) {
            pending = this.binding != null && !this.binding.isDone() ? this.binding : null;
            if (pending != null) {
                this.binding = null;
            }
            current = this.listen;
        }

        Throwable failure = null;
        if (pending != null) {
            try {
                pending.failed(new IllegalStateException("Inbound traffic is stopped"));
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }

        if (current != null) {
            try {
                current.suspend();
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }

        for (Map.Entry<NetworkChannel<M>, Boolean> connection : this.connections.entrySet()) {
            if (!connection.getValue()) {
                continue;
            }

            NetworkChannel<M> channel = connection.getKey();
            try {
                this.closeInbound(channel);
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }

        throwFailure(failure);
    }

    void startInbound() {
        NetworkListen current;
        synchronized (this) {
            current = this.listen;
        }

        if (current != null) {
            current.resume();
        }
    }

    void closeListener() {
        BasicFuture<NetworkListen> pending;
        NetworkListen closing;
        synchronized (this) {
            this.bindDisabled = true;
            pending = this.binding != null && !this.binding.isDone() ? this.binding : null;
            this.binding = null;
            closing = this.listen;
            this.listen = null;
        }

        if (pending != null) {
            try {
                pending.failed(new IllegalStateException("Connector endpoint binding is disabled"));
            } catch (RuntimeException | Error failure) {
                logger.warn("Pending binding failure notification failed", failure);
            }
        }

        if (closing != null) {
            try {
                closing.close();
            } catch (RuntimeException | Error failure) {
                logger.warn("Listener close failed", failure);
            }
        }
    }

    boolean beginClose() {
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return false;
            }
            this.state = State.CLOSED;
        }
        return true;
    }

    List<Future<MetChannel>> drainChannels() {
        List<ConnectorChannel<?>> closing;
        synchronized (this) {
            closing = new ArrayList<>(this.channels.values());
        }

        List<Future<MetChannel>> pending = new ArrayList<>();
        for (ConnectorChannel<?> channel : closing) {
            try {
                pending.add(channel.drainAndClose());
            } catch (RuntimeException failure) {
                logger.warn("Channel drain failed", failure);
                pending.add(channel.close());
            }
        }
        return pending;
    }

    List<Future<Void>> drainConnections() {
        List<Future<Void>> draining = new ArrayList<>();
        for (NetworkChannel<M> channel : this.connections.keySet()) {
            try {
                draining.add(channel.drainAndClose());
            } catch (RuntimeException failure) {
                logger.warn("Connection drain failed", failure);
                draining.add(channel.close());
            }
        }
        return draining;
    }

    void closeTransport() {
        if (this.networkConnector != null) {
            this.networkConnector.close();
        }

        this.connections.clear();
        this.closingInbound.clear();
    }

    private static <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }

    private static Throwable appendFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }

        if (current != failure) {
            current.addSuppressed(failure);
        }
        return current;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }
}

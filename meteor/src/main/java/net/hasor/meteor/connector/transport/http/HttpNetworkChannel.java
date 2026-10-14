/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ContextPaths;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.channel.NetChannel;

/**
 * Common write admission and completion-driven drain, independent of TCP/HTTP protocols.
 */
abstract class HttpNetworkChannel implements NetworkChannel<HttpExchange> {
    protected final ConnectorConfig               config;
    protected final SocketTransport               transport;
    protected final Executor                      executor;
    private         ChannelListener<HttpExchange> receiver;
    private         ContextRoutes                 routes;
    private final   boolean                       streaming;
    private         boolean                       streamingResponses;
    private final   BasicFuture<Void>             closed    = new BasicFuture<>();
    private final   Set<BasicFuture<?>>           submitted = new HashSet<>();
    private         int                           writing;
    private         boolean                       draining;

    public HttpNetworkChannel(ConnectorConfig config, SocketTransport transport, Executor executor) {
        this.config = config;
        this.transport = transport;
        this.executor = executor;
        this.streaming = Boolean.parseBoolean(config.option("streamBody", "false"));
        this.streamingResponses = this.streaming;
    }

    protected final void receiver(ChannelListener<HttpExchange> receiver) {
        this.streamingResponses = this.streaming;
        if (receiver instanceof RoutedReceiver<HttpExchange> routed) {
            this.routes = routed.contextRoutes();
            if (this.routes == null) {
                this.routes = configuredRoutes(routed.routes());
            }

            String initial = routed.initialRoute();
            NetworkRoute<HttpExchange> outgoing = initial == null ? null : routed.routes().get(initial);
            if (outgoing != null) {
                this.streamingResponses = Boolean.parseBoolean(outgoing.options().get("streamBody"));
            }
        }
        this.receiver = receiver;
    }

    protected final void received(HttpExchange exchange) throws Exception {
        if (this.receiver instanceof RoutedReceiver<HttpExchange> routed) {
            ContextRoutes.Route route = exchange.request().route();
            String protocol = route == null ? "" : route.protocol();
            if (routed.routes().containsKey(protocol)) {
                routed.receive(protocol, exchange);
            } else {
                exchange.respond(HttpResponse.newBuffered(404, Collections.emptyMap(), new byte[0], Map.of()));
                this.write(exchange);
            }
        } else {
            this.receiver.receive(exchange);
        }
    }

    /** 响应正文模式属于出站协议，不使用本地发布服务的请求路径索引。 */
    protected final boolean streamResponseBody() {
        return this.streamingResponses;
    }

    protected final boolean streamBody(ContextRoutes.Route route) {
        return route == null ? this.streaming : Boolean.parseBoolean(route.options().get("streamBody"));
    }

    protected final ContextRoutes.Route route(String uri) {
        return this.routes == null ? null : this.routes.match(uri);
    }

    private static ContextRoutes configuredRoutes(Map<String, NetworkRoute<HttpExchange>> routes) {
        List<ContextRoutes.Route> prefixes = new ArrayList<>();
        for (Map.Entry<String, NetworkRoute<HttpExchange>> entry : routes.entrySet()) {
            NetworkRoute<HttpExchange> network = entry.getValue();
            for (ContextPaths.Mount mount : network.contexts().mounts()) {
                prefixes.add(new ContextRoutes.Route(entry.getKey(), mount, network.options()));
            }
        }
        return new ContextRoutes(prefixes);
    }

    protected final void manageChannel() {
        this.receiver.connected();
    }

    protected final void forgetChannel() {
        try {
            this.receiver.closed(new IOException("HTTP channel closed"));
        } finally {
            this.closed.completed(null);
        }
    }

    public final void execute(Runnable task) {
        this.executor.execute(task);
    }

    protected final BasicFuture<Void> closeFuture() {
        return this.closed;
    }

    protected final InterAddress localAddress(NetChannel socket) {
        return socket == null ? null : SocketAddressUtils.local(this.config.address(), socket.getLocalAddr());
    }

    protected final boolean admitWrite(BasicFuture<?> sent) {
        synchronized (this) {
            if (!this.draining && !this.closed.isDone()) {
                this.submitted.add(sent);
                sent.onFinal(done -> {
                    synchronized (this) {
                        this.submitted.remove(sent);
                    }

                    this.checkDrain();
                });
                return true;
            }
        }

        sent.failed(new IllegalStateException("Connector or channel is closing; new writes are disabled"));
        return false;
    }

    /**
     * Physical writes outlive cancellation of the public send future.
     */
    protected final Future<?> writeSocket(NetChannel socket, Object message) {
        synchronized (this) {
            this.writing++;
        }

        try {
            Future<?> writing;
            if (message instanceof Object[] batch) {
                writing = socket.sendData(batch);
            } else {
                writing = socket.sendData(message);
            }
            return writing.onFinal(done -> this.writeFinished());
        } catch (Throwable error) {
            this.writeFinished();
            throw error;
        }
    }

    private void writeFinished() {
        synchronized (this) {
            this.writing--;
        }

        this.checkDrain();
    }

    @Override
    public final Future<Void> drainAndClose() {
        synchronized (this) {
            this.draining = true;
        }

        this.executeWrite(this.executor, this.closed, () -> {
            this.prepareDrain();
            this.checkDrain();
        });
        return this.closed;
    }

    private void checkDrain() {
        synchronized (this) {
            boolean pendingWrites = !this.submitted.isEmpty() || this.writing != 0;
            if (!this.draining || this.closed.isDone() || pendingWrites) {
                return;
            }
        }

        // Completion may run inside Neta's pipeline; close only on the ordered executor.
        this.executeWrite(this.executor, this.closed, () -> {
            synchronized (this) {
                if (!this.submitted.isEmpty() || this.writing != 0) {
                    return;
                }
            }

            this.closeAfterDrain();
        });
    }

    /**
     * Resolve protocol ordering dependencies before draining submitted writes.
     */
    protected void prepareDrain() {
    }

    protected void closeAfterDrain() {
        this.close();
    }

    protected <T> void completeWrite(BasicFuture<T> result, T value, Throwable error) {
        if (result != null) {
            if (error == null) {
                result.completed(value);
            } else {
                result.failed(error);
            }
        }
    }

    protected void executeWrite(Executor executor, BasicFuture<?> result, Runnable action) {
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } catch (Throwable failure) {
                    result.failed(failure);
                }
            });
        } catch (Throwable failure) {
            result.failed(failure);
        }
    }
}

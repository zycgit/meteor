/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteDefinition;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.channel.NetChannel;

/** HTTP/1、HTTP/2 与升级通道共用的写入准入和排空生命周期。 */
public abstract class HttpChannel implements NetworkChannel<HttpExchange> {
    protected final TransportConfig               config;
    protected final SocketRuntime                 transport;
    protected final Executor                      executor;
    private final   RouteTable<HttpExchange>      routes;
    private         ChannelListener<HttpExchange> receiver;
    private         boolean                       forgotten;
    private final   boolean                       streaming;
    private final   boolean                       streamingResponses;
    private final   BasicFuture<Void>             closed    = new BasicFuture<>();
    private final   Set<BasicFuture<?>>           submitted = new HashSet<>();
    private final   Set<HttpUpgradeChannel<?>>    upgrades  = new HashSet<>();
    private         int                           writing;
    private         boolean                       draining;

    protected HttpChannel(TransportConfig config, SocketRuntime transport, Executor executor, //
            RouteTable<HttpExchange> routes, String initialMountId) {
        this.config = config;
        this.transport = transport;
        this.executor = executor;
        this.routes = routes;
        this.streaming = Boolean.parseBoolean(config.option("streamBody", "false"));
        boolean responseStreaming = this.streaming;

        String initial = routes.resolveMountId(initialMountId);
        RouteDefinition<HttpExchange> outgoing = initial == null ? null : routes.definitions().get(initial);
        if (outgoing != null) {
            String configuredStreaming = outgoing.options().get("streamBody");
            if (configuredStreaming != null) {
                responseStreaming = Boolean.parseBoolean(configuredStreaming);
            }
        }
        this.streamingResponses = responseStreaming;
    }

    protected final synchronized boolean attachReceiver(ChannelListenerFactory<HttpExchange> factory) throws Exception {
        if (this.forgotten) {
            return false;
        }
        this.receiver = factory.createListener(this);
        return true;
    }

    protected final void received(HttpExchange exchange) throws Exception {
        if (this.receiver instanceof RouteReceiver<HttpExchange> routed) {
            RouteSelection route = exchange.request().route();
            String mountId = route == null ? "" : route.mountId();
            if (this.routes.contains(mountId)) {
                routed.receive(mountId, exchange);
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

    protected final boolean streamBody(RouteSelection route) {
        return route == null ? this.streaming : Boolean.parseBoolean(route.options().get("streamBody"));
    }

    protected final RouteSelection route(String uri) {
        return this.routes.select(uri);
    }

    /** Select an HTTP/1 upgrade route on the connection's ordered worker. */
    protected void authorizeUpgrade(String uri, Consumer<RouteSelection> callback) {
        this.execute(() -> {
            RouteSelection route = this.acceptsUpgrade() ? this.route(uri) : null;
            callback.accept(route);
        });
    }

    protected final void notifyConnected() {
        this.receiver.connected();
    }

    protected final void notifyClosed() {
        ChannelListener<HttpExchange> current;
        synchronized (this) {
            if (this.forgotten) {
                return;
            }
            this.forgotten = true;
            current = this.receiver;
        }

        try {
            this.closeUpgrades(new IOException("HTTP channel closed"));
            if (current != null) {
                current.closed(new IOException("HTTP channel closed"));
            }
        } finally {
            this.closed.completed(null);
        }
    }

    final void trackUpgrade(HttpUpgradeChannel<?> channel) {
        this.upgrades.add(channel);
        channel.completion().onFinal(done -> this.execute(() -> this.upgrades.remove(channel)));
    }

    protected final synchronized boolean acceptsUpgrade() {
        return !this.draining && !this.closed.isDone();
    }

    protected final synchronized boolean isDraining() {
        return this.draining;
    }

    protected final void closeUpgrades(Throwable failure) {
        for (HttpUpgradeChannel<?> channel : new ArrayList<>(this.upgrades)) {
            channel.abort(failure);
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
        return this.trackWrite(() -> {
            if (message instanceof Object[] batch) {
                return socket.sendData(batch);
            } else {
                return socket.sendData(message);
            }
        });
    }

    final Future<?> writeUpgrade(Supplier<Future<?>> sender) {
        return this.trackWrite(sender);
    }

    private Future<?> trackWrite(Supplier<Future<?>> sender) {
        synchronized (this) {
            this.writing++;
        }
        try {
            return sender.get().onFinal(done -> this.writeFinished());
        } catch (Throwable failure) {
            this.writeFinished();
            throw failure;
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

        this.executeWrite(this.closed, () -> {
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
        this.executeWrite(this.closed, () -> {
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

    protected final <T> void completeWrite(BasicFuture<T> result, T value, Throwable error) {
        if (result != null) {
            if (error == null) {
                result.completed(value);
            } else {
                result.failed(error);
            }
        }
    }

    protected final void executeWrite(BasicFuture<?> result, Runnable action) {
        try {
            this.executor.execute(() -> {
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

    protected final void startStreamingBodyWrite(long id, HttpBodyStream bodyStream, Map<String, List<String>> trailers, Function<Object, Future<?>> writer, BasicFuture<Void> sent) {
        HttpStreamingBodyWriter bodyWriter = new HttpStreamingBodyWriter(id, bodyStream, trailers, this::execute, writer, sent);
        bodyWriter.start();
    }

    protected final void terminate(HttpExchange exchange, Throwable failure) {
        exchange.terminated(failure);
    }
}

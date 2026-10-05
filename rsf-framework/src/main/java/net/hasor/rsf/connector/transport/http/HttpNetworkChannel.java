/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.http;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.transport.*;

/**
 * Common write admission and completion-driven drain, independent of TCP/HTTP protocols.
 */
abstract class HttpNetworkChannel implements NetworkChannel<HttpExchange> {
    protected final ConnectorConfig               config;
    protected final SocketTransport               transport;
    protected final Executor                      executor;
    private         ChannelListener<HttpExchange> receiver;
    private final   BasicFuture<Void>             closed    = new BasicFuture<>();
    private final   Set<BasicFuture<?>>           submitted = new HashSet<>();
    private         int                           writing;
    private         boolean                       draining;

    public HttpNetworkChannel(ConnectorConfig config, SocketTransport transport, Executor executor) {
        this.config = config;
        this.transport = transport;
        this.executor = executor;
    }

    protected final void receiver(ChannelListener<HttpExchange> receiver) {
        this.receiver = receiver;
    }

    protected final void received(String route, HttpExchange exchange) throws Exception {
        if (this.receiver instanceof RoutedReceiver) {
            ((RoutedReceiver<HttpExchange>) this.receiver).receive(route, exchange);
        } else {
            this.receiver.receive(exchange);
        }
    }

    protected final void received(HttpExchange exchange) throws Exception {
        this.receiver.receive(exchange);
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
            return socket.sendData(message).onFinal(done -> this.writeFinished());
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
            if (!this.draining || this.closed.isDone() || !this.submitted.isEmpty() || this.writing != 0) {
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

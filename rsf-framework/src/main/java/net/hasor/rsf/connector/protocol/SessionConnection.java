/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.transport.NetworkChannel;

/** A protocol view of a physical connection. Shared views never close their siblings. */
public final class SessionConnection<M> implements NetworkChannel<M> {
    private final NetworkChannel<M>   network;
    private final String              scheme;
    private final boolean             shared;
    private final BasicFuture<Void>   closed = new BasicFuture<>();
    private       Consumer<Throwable> closing;

    public SessionConnection(NetworkChannel<M> network, String scheme, boolean shared) {
        this.network = network;
        this.scheme = scheme;
        this.shared = shared;
    }

    public void onClose(Consumer<Throwable> closing) {
        this.closing = closing;
    }

    public void terminated(Throwable cause) {
        if (this.closed.completed(null) && this.closing != null) {
            this.closing.accept(cause);
        }
    }

    private InterAddress address(InterAddress address) {
        return address == null ? null : new InterAddress(this.scheme, address.getHost(), address.getPort(), address.getFormUnit());
    }

    public InterAddress getLocal() {
        return this.address(this.network.getLocal());
    }

    public InterAddress getRemote() {
        return this.address(this.network.getRemote());
    }

    public boolean isOpen() {
        return !this.closed.isDone() && this.network.isOpen();
    }

    public Future<Void> write(M message) {
        if (!this.isOpen()) {
            BasicFuture<Void> result = new BasicFuture<>();
            result.failed(new IOException("Protocol session is closed"));
            return result;
        } else {
            return this.network.write(message);
        }
    }

    public void execute(Runnable action) {
        this.network.execute(action);
    }

    public Future<Void> close() {
        if (this.shared) {
            this.execute(() -> this.terminated(new IOException("Protocol session closed")));
        } else {
            this.network.close();
        }

        return this.closed;
    }

    public Future<Void> drainAndClose() {
        if (this.shared) {
            return this.close();
        }

        this.network.drainAndClose();
        return this.closed;
    }
}

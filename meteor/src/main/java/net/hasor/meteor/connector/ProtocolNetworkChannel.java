/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.NetworkChannel;

/** Protocol-scoped network channel. An isolated close leaves sibling protocol channels open. */
final class ProtocolNetworkChannel<M> implements NetworkChannel<M> {
    private final NetworkChannel<M>   networkChannel;
    private final String              scheme;
    private final boolean             isolatedClose;
    private final BasicFuture<Void>   closeFuture = new BasicFuture<>();
    private       Consumer<Throwable> closeListener;

    ProtocolNetworkChannel(NetworkChannel<M> networkChannel, String scheme, boolean isolatedClose) {
        this.networkChannel = networkChannel;
        this.scheme = scheme;
        this.isolatedClose = isolatedClose;
    }

    void onClose(Consumer<Throwable> listener) {
        this.closeListener = listener;
    }

    void terminate(Throwable cause) {
        if (this.closeFuture.completed(null) && this.closeListener != null) {
            this.closeListener.accept(cause);
        }
    }

    private InterAddress protocolAddress(InterAddress address) {
        if (address == null) {
            return null;
        } else {
            return new InterAddress(this.scheme, address.getHost(), address.getPort(), address.getFormUnit());
        }
    }

    @Override
    public InterAddress getLocal() {
        return this.protocolAddress(this.networkChannel.getLocal());
    }

    @Override
    public InterAddress getRemote() {
        return this.protocolAddress(this.networkChannel.getRemote());
    }

    @Override
    public boolean isOpen() {
        return !this.closeFuture.isDone() && this.networkChannel.isOpen();
    }

    @Override
    public Future<Void> write(M message) {
        if (!this.isOpen()) {
            BasicFuture<Void> result = new BasicFuture<>();
            result.failed(new IOException("Protocol connection is closed"));
            return result;
        } else {
            return this.networkChannel.write(message);
        }
    }

    @Override
    public void execute(Runnable action) {
        this.networkChannel.execute(action);
    }

    @Override
    public Future<Void> close() {
        if (this.isolatedClose) {
            this.execute(() -> this.terminate(new IOException("Protocol connection closed")));
        } else {
            this.networkChannel.close();
        }

        return this.closeFuture;
    }

    @Override
    public Future<Void> drainAndClose() {
        if (this.isolatedClose) {
            return this.close();
        }

        this.networkChannel.drainAndClose();
        return this.closeFuture;
    }
}

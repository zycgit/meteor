/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.neta.channel.NetListen;

/** Listener admission lifecycle. Existing connections remain owned by the connector after close. */
final class SocketListen implements NetworkListen {
    private final NetListen              socket;
    private final InterAddress           address;
    private final boolean                sharedSocket;
    private final SocketAdmissionTracker admission;
    private final BasicFuture<Void>      closed = new BasicFuture<>();

    SocketListen(InterAddress configured, NetListen socket, boolean sharedSocket, //
            SocketAdmissionTracker admission) {
        this.socket = socket;
        this.address = SocketAddressUtils.local(configured, socket.getLocalAddr());
        this.sharedSocket = sharedSocket;
        this.admission = admission;
        socket.onClose(done -> {
            this.closed.completed(null);
        });
    }

    public InterAddress getAddress() {
        return this.address;
    }

    public boolean isOpen() {
        return !this.closed.isDone() && !this.socket.isClose() && !this.socket.isSuspend();
    }

    public void onClose(Runnable action) {
        this.closed.onFinal(done -> action.run());
    }

    public void suspend() {
        if (!this.closed.isDone() && !this.socket.isClose()) {
            try {
                this.socket.suspend();
            } finally {
                // Offline admission also aborts raw sockets not yet delivered to the channel listener.
                this.admission.suspend();
            }
        }
    }

    public void resume() {
        if (this.closed.isDone() || this.socket.isClose()) {
            throw new IllegalStateException("Listener is closed");
        }
        this.admission.resume();
        try {
            this.socket.resume();
        } catch (RuntimeException | Error failure) {
            this.admission.suspend();
            throw failure;
        }
    }

    public void close() {
        // Closing the listener only stops new admission; accepted connections finish ownership transfer.
        if (this.sharedSocket) {
            this.socket.suspend();
            this.closed.completed(null);
        } else {
            this.socket.closeNow();
        }
    }
}

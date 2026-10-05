/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.neta.channel.NetListen;
import net.hasor.rsf.address.InterAddress;

/**
 * UDP suspension preserves the shared socket for established peers until connector disposal.
 */
public final class SocketListen implements NetworkListen {
    private final NetListen         socket;
    private final InterAddress      address;
    private final boolean           datagram;
    private final BasicFuture<Void> closed = new BasicFuture<>();

    public SocketListen(InterAddress configured, NetListen socket, boolean datagram) {
        this.socket = socket;
        this.address = SocketAddressUtils.local(configured, socket.getLocalAddr());
        this.datagram = datagram;
        socket.onClose(done -> {
            this.closed.completed(null);
        });
    }

    public InterAddress getAddress() {
        return this.address;
    }

    public boolean isOpen() {
        return !this.closed.isDone() && !this.socket.isClose();
    }

    public void onClose(Runnable action) {
        this.closed.onFinal(done -> action.run());
    }

    public void close() {
        if (this.datagram) {
            this.socket.suspend();
            this.closed.completed(null);
        } else {
            this.socket.closeNow();
        }
    }
}

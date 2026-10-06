/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import net.hasor.rsf.connector.AbstractRsfListen;
import net.hasor.rsf.connector.ReceivedListener;
import net.hasor.rsf.connector.transport.NetworkListen;

public final class ProtocolListen extends AbstractRsfListen {
    private final NetworkListen listen;

    public ProtocolListen(String type, NetworkListen listen, ReceivedListener receiver) {
        super(type, listen.getAddress(), receiver);
        this.listen = listen;
    }

    public boolean isActive() {
        return this.listen.isOpen();
    }

    public void close() {
        this.listen.close();
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import net.hasor.meteor.connector.AbstractMetListen;
import net.hasor.meteor.connector.ReceivedListener;
import net.hasor.meteor.connector.transport.NetworkListen;

public final class ProtocolListen extends AbstractMetListen {
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

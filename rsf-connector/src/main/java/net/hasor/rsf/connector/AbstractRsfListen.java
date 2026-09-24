/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Objects;
import net.hasor.rsf.address.InterAddress;

/** Base listener with an immutable type and bound address, and a protected receive callback. */
public abstract class AbstractRsfListen implements RsfListen {
    private final String           type;
    private final InterAddress     address;
    private final ReceivedListener listener;

    protected AbstractRsfListen(String type, InterAddress address, ReceivedListener listener) {
        this.type = Objects.requireNonNull(type, "type");
        this.address = Objects.requireNonNull(address, "address");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public final String getType() {
        return this.type;
    }

    @Override
    public final InterAddress getBindAddress() {
        return this.address;
    }

    protected final ReceivedListener listener() {
        return this.listener;
    }
}

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

/** Base channel with an immutable target address and a protected receive callback. */
public abstract class AbstractRsfChannel implements RsfChannel {
    private final InterAddress     target;
    private final ReceivedListener listener;

    protected AbstractRsfChannel(InterAddress target, ReceivedListener listener) {
        this.target = Objects.requireNonNull(target, "target");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public final InterAddress getTarget() {
        return this.target;
    }

    protected final ReceivedListener listener() {
        return this.listener;
    }
}

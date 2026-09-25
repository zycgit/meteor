/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Objects;

/** Base channel with a fixed owner, externally assigned ID and protected receive callback.
 * Transport implementations provide local and remote addresses.
 */
public abstract class AbstractRsfChannel implements RsfChannel {
    private final RsfConnector     connector;
    private final long             channelId;
    private final ReceivedListener listener;

    protected AbstractRsfChannel(RsfConnector connector, long channelId, ReceivedListener listener) {
        this.connector = connector;
        this.channelId = channelId;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public final RsfConnector getConnector() {
        return this.connector;
    }

    @Override
    public final long getChannelId() {
        return this.channelId;
    }

    protected final ReceivedListener listener() {
        return this.listener;
    }
}

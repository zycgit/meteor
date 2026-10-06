/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.connector.transport.ChannelListener;
import net.hasor.rsf.domain.payload.Payload;

/** One protocol session. Lifecycle, receive and send run on its ordered network executor. */
public interface ProtocolSession<M> extends ChannelListener<M> {
    Future<Void> ready();

    Future<Void> send(Payload payload);

    /** Resolve pending protocol exchanges before submitted writes are drained. */
    void prepareDrain();
}

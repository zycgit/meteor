/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.function.Consumer;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/** One lifecycle observer for a physical connection, outside all protocol branches. */
public final class ConnectionEvents implements ProtoHandler<Object, Object> {
    private final Runnable            active;
    private final Runnable            closed;
    private final Consumer<Throwable> failed;

    public ConnectionEvents(Runnable active, Runnable closed, Consumer<Throwable> failed) {
        this.active = active;
        this.closed = closed;
        this.failed = failed;
    }

    public void onActive(ProtoContext context) {
        this.active.run();
    }

    public void onClose(ProtoContext context) {
        this.closed.run();
    }

    public ProtoStatus onError(ProtoContext context, Throwable failure, ProtoExceptionHolder holder) {
        this.failed.accept(failure);
        return ProtoStatus.Next;
    }

    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Object> source, ProtoSndQueue<Object> target) {
        while (source.hasMore() && target.hasSlot()) {
            target.offerMessage(source.takeMessage());
        }
        return ProtoStatus.Next;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/** Delivers input closure after the last decoded message through the application event queue. */
public final class InputCloseHandler implements ProtoHandler<Object, Object> {
    private SoInputCloseEvent inputClose;

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) {
        if (event.getData() instanceof SoInputCloseEvent closed) {
            this.inputClose = closed;
            return false;
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Object> source, ProtoSndQueue<Object> target) {
        while (source.hasMore() && target.hasSlot()) {
            target.offerMessage(source.takeMessage());
        }

        if (this.inputClose == null || source.hasMore() || !target.hasSlot()) {
            return ProtoStatus.Next;
        }

        target.offerMessage(this.inputClose);
        return ProtoStatus.End;
    }
}

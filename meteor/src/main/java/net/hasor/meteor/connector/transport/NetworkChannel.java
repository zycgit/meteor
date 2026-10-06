/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;

/**
 * A serial message channel. TCP messages are byte chunks, UDP messages are whole datagrams.
 */
public interface NetworkChannel<M> {
    InterAddress getLocal();

    InterAddress getRemote();

    boolean isOpen();

    /**
     * Completes after the submitted write finishes, not after an application response arrives.
     * Byte channels copy the submitted array; cancelling a future cannot retract bytes already written.
     */
    Future<Void> write(M message);

    /** Runs state changes in channel order, outside the engine pipeline. Tasks must not block. */
    void execute(Runnable task);

    /** Forces closure without waiting for submitted writes. */
    Future<Void> close();

    /** Rejects new writes, drains submitted writes, then closes. Shares the close() future. */
    Future<Void> drainAndClose();
}

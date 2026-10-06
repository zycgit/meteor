/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.meteor.address.InterAddress;

/** A local listener owned by a connector, independent of its network engine. */
public interface MetListen extends AutoCloseable {
    /** Provider-defined listener type, such as tcp, http or udp; independent of the address schema. */
    String getType();

    /** Actual bound address, or null while binding; its schema identifies the exposed protocol. */
    InterAddress getBindAddress();

    /** Whether this listener currently accepts new inbound connections or requests. */
    boolean isActive();

    /** Stop this listener. Existing sessions are owned separately by the manager. Must be idempotent. */
    void close();
}

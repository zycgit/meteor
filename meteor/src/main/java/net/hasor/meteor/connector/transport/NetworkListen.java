/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.meteor.address.InterAddress;

public interface NetworkListen extends AutoCloseable {
    InterAddress getAddress();

    boolean isOpen();

    void onClose(Runnable action);

    /** Suspend admission without completing the close callback. */
    void suspend();

    /** Resume admission after suspension. */
    void resume();

    void close();
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import net.hasor.rsf.address.InterAddress;

public interface NetworkListen extends AutoCloseable {
    InterAddress getAddress();

    boolean isOpen();

    void onClose(Runnable action);

    void close();
}

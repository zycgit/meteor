/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;

public interface NetworkConnector<M> extends AutoCloseable {
    NetworkListen bind(InterAddress address, ChannelFactory<M> factory) throws Exception;

    /**
     * Establishes a transport channel and installs its receiver. The receiver observes activation.
     * HTTP establishes a logical channel here and opens its socket on the first exchange.
     * Application handshakes, if any, run above this operation.
     */
    Future<Void> connect(InterAddress address, ChannelFactory<M> factory);

    /** Releases network resources. Drain individual channels first when graceful shutdown is required. */
    void close();
}

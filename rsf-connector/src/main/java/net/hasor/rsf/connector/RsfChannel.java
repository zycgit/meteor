/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.RequestInfo;

/** An outbound protocol session; a TCP connection and an HTTP endpoint have different lifetimes. */
public interface RsfChannel extends AutoCloseable {
    InterAddress getTarget();

    boolean isActive();

    /** Completes with this channel when the transport has sent the request, independently of its eventual RPC response. */
    Future<RsfChannel> sendData(RequestInfo request);

    void close();
}

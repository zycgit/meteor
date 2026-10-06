/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.domain.payload.RequestPayload;

record PendingRequest(RequestPayload request, BasicFuture<Void> sent) {
}

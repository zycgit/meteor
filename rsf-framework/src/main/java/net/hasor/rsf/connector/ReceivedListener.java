/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;

public interface ReceivedListener {
    void onRequest(RsfChannel channel, long requestId, RequestPayload request);

    void onResponse(RsfChannel channel, long requestId, ResponsePayload response);

    void onFailure(RsfChannel channel, long requestId, ThrowPayload failure);
}

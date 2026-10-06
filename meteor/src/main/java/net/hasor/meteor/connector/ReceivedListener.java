/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

public interface ReceivedListener {
    void onRequest(MetChannel channel, long requestId, RequestPayload request);

    void onResponse(MetChannel channel, long requestId, ResponsePayload response);

    void onFailure(MetChannel channel, long requestId, ThrowPayload failure);
}

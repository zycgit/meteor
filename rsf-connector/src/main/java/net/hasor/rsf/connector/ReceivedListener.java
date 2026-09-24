/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import net.hasor.rsf.domain.RequestInfo;
import net.hasor.rsf.domain.ResponseInfo;

public interface ReceivedListener {
    void onRequest(RequestInfo request, Exchange exchange);

    void onResponse(ResponseInfo response);

    void onFailure(long requestId, Throwable failure);
}

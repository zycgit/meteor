/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.domain.payload.ResponsePayload;

interface HproseResponseEncoder {
    HttpResponse encode(ResponsePayload response) throws Exception;
}

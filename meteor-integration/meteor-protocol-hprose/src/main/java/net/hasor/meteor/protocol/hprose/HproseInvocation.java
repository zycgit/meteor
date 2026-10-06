/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;

/** Decoded Hprose invocation and its response encoder, or a local metadata reply. */
final class HproseInvocation {
    private final RequestPayload        request;
    private final HttpResponse          immediate;
    private final HproseResponseEncoder encoder;

    private HproseInvocation(RequestPayload request, HttpResponse immediate, HproseResponseEncoder encoder) {
        this.request = request;
        this.immediate = immediate;
        this.encoder = encoder;
    }

    public RequestPayload request() {
        return this.request;
    }

    public HttpResponse immediate() {
        return this.immediate;
    }

    public HproseResponseEncoder encoder() {
        return this.encoder;
    }

    public static HproseInvocation respond(HttpResponse response) {
        return new HproseInvocation(null, response, null);
    }

    public static HproseInvocation dispatch(RequestPayload request, HproseResponseEncoder encoder) {
        return new HproseInvocation(request, null, encoder);
    }
}

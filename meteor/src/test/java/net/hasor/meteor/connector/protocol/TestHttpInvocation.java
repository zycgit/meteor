/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;

/**
 * A protocol may answer locally (metadata/error) or dispatch exactly one RSF request.
 */
public final class TestHttpInvocation {

    private final RequestPayload          request;
    private final HttpResponse            immediate;
    private final TestHttpResponseEncoder encoder;

    private TestHttpInvocation(RequestPayload request, HttpResponse immediate, TestHttpResponseEncoder encoder) {
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

    public TestHttpResponseEncoder encoder() {
        return this.encoder;
    }

    public static TestHttpInvocation respond(HttpResponse response) {
        return new TestHttpInvocation(null, response, null);
    }

    public static TestHttpInvocation dispatch(RequestPayload request, TestHttpResponseEncoder encoder) {
        return new TestHttpInvocation(request, null, encoder);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/**
 * Thread-safe HTTP application protocol; all per-request state belongs to TestHttpInvocation.
 */
public interface TestHttpCodec {

    TestHttpInvocation receive(HttpRequest request) throws Exception;

    HttpRequest encode(InterAddress target, RequestPayload request) throws Exception;

    ResponsePayload decode(long requestId, HttpResponse response) throws Exception;

    HttpResponse error(Throwable error);
}

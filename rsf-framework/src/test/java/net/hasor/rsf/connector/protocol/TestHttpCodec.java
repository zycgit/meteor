/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.transport.http.HttpRequest;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;

/**
 * Thread-safe HTTP application protocol; all per-request state belongs to TestHttpInvocation.
 */
public interface TestHttpCodec {

    TestHttpInvocation receive(HttpRequest request) throws Exception;

    HttpRequest encode(InterAddress target, RequestPayload request) throws Exception;

    ResponsePayload decode(long requestId, HttpResponse response) throws Exception;

    HttpResponse error(Throwable error);
}

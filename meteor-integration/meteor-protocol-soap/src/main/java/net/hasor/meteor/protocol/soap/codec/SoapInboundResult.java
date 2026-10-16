/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionResource;
import net.hasor.meteor.protocol.soap.definition.SoapOperation;

/** SOAP 入站解析结果：定义资源响应或一个业务调用。 */
public final class SoapInboundResult {
    private final RequestPayload         request;
    private final SoapOperation          operation;
    private final HttpResponse           response;
    private final MetBindInfo<?>         service;
    private final SoapDefinitionResource resource;

    private SoapInboundResult(RequestPayload request, SoapOperation operation, HttpResponse response, //
            MetBindInfo<?> service, SoapDefinitionResource resource) {
        this.request = request;
        this.operation = operation;
        this.response = response;
        this.service = service;
        this.resource = resource;
    }

    static SoapInboundResult request(RequestPayload request, SoapOperation operation, //
            MetBindInfo<?> service, SoapDefinitionResource resource) {
        return new SoapInboundResult(request, operation, null, service, resource);
    }

    public static SoapInboundResult response(HttpResponse response) {
        return new SoapInboundResult(null, null, response, null, null);
    }

    public RequestPayload request() {
        return this.request;
    }

    public SoapOperation operation() {
        return this.operation;
    }

    public HttpResponse response() {
        return this.response;
    }

    public MetBindInfo<?> service() {
        return this.service;
    }

    public SoapDefinitionResource resource() {
        return this.resource;
    }
}

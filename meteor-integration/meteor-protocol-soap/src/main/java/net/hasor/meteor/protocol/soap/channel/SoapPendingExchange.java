/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.channel;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionResource;
import net.hasor.meteor.protocol.soap.definition.SoapOperation;

/** 等待 Dispatcher 返回结果的 SOAP HTTP 交换。 */
final class SoapPendingExchange {
    private final HttpExchange           exchange;
    private final RequestPayload         request;
    private final SoapOperation          operation;
    private final MetBindInfo<?>         service;
    private final SoapDefinitionResource resource;

    SoapPendingExchange(HttpExchange exchange, RequestPayload request, SoapOperation operation, //
            MetBindInfo<?> service, SoapDefinitionResource resource) {
        this.exchange = exchange;
        this.request = request;
        this.operation = operation;
        this.service = service;
        this.resource = resource;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    RequestPayload request() {
        return this.request;
    }

    SoapOperation operation() {
        return this.operation;
    }

    MetBindInfo<?> service() {
        return this.service;
    }

    SoapDefinitionResource resource() {
        return this.resource;
    }
}

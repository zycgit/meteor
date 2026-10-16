/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.example.connector.protocol.TextProtocol;

/** 复用本模块已有 HTTP 应用协议，只为同端口场景固定独立挂载点。 */
public final class OrdinaryHttpProtocol extends TextProtocol {
    @Override
    public String name() {
        return "wshttp";
    }

    @Override
    public HttpRequest encode(InterAddress target, RequestPayload request) {
        HttpRequest ordinary = super.encode(target, request);
        return HttpRequest.newBuffered(ordinary.method(), "/http", ordinary.headers(), //
                ordinary.body(), ordinary.version(), ordinary.trailers());
    }
}

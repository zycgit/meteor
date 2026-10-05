/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.http;
import java.io.IOException;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.FullHttpRequest;

import static net.hasor.rsf.connector.transport.http.HttpMessages.*;

/**
 * Copies and releases Neta messages before passing them to the ordered channel executor.
 */
final class HttpServerHandler implements ProtoHandler<FullHttpRequest, Object> {
    private final String route;

    public HttpServerHandler(String route) {
        this.route = route;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<FullHttpRequest> src, ProtoSndQueue<Object> dst) throws Exception {
        while (src.hasMore() && dst.hasSlot()) {
            FullHttpRequest request = src.takeMessage();
            try {
                if (request.isBad()) {
                    throw new IOException("Malformed HTTP request: " + request.badReason());
                }

                HttpRequest copy = new HttpRequest(request.method().name(), request.uri(), headers(request), body(request.content()));
                dst.offerMessage(new HttpInbound(this.route, copy, keepAlive(request.protocolVersion(), request)));
            } finally {
                request.release();
            }
        }
        return ProtoStatus.Next;
    }
}

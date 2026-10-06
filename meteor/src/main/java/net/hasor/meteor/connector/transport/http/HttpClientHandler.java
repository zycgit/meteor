/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.FullHttpResponse;

import static net.hasor.meteor.connector.transport.http.HttpMessages.*;

final class HttpClientHandler implements ProtoHandler<FullHttpResponse, Object> {
    private final HttpClientChannel channel;
    private final NetChannel        socket;

    public HttpClientHandler(HttpClientChannel channel, NetChannel socket) {
        this.channel = channel;
        this.socket = socket;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<FullHttpResponse> src, ProtoSndQueue<Object> dst) throws Exception {
        while (src.hasMore() && dst.hasSlot()) {
            FullHttpResponse response = src.takeMessage();
            try {
                if (response.isBad()) {
                    throw new IOException("Malformed HTTP response: " + response.badReason());
                }
                HttpResponse copy = new HttpResponse(response.status().code(), headers(response), body(response.content()));
                boolean keep = keepAlive(response.protocolVersion(), response);
                dst.offerMessage(new HttpInboundResponse(copy, keep));
            } finally {
                response.release();
            }
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.channel.dispatch(() -> this.channel.failed(this.socket, new IOException("HTTP connection closed before response")));
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable failure, ProtoExceptionHolder holder) {
        this.channel.dispatch(() -> {
            this.channel.failed(this.socket, failure);
            this.socket.closeNow();
        });
        return ProtoStatus.Next;
    }
}

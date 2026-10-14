/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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

                int status = response.status().code();
                Map<String, List<String>> responseHeaders = headers(response);
                byte[] content = body(response.content());
                Map<String, List<String>> trailers = Map.of();
                if (response.hasTrailingHeaders()) {
                    trailers = headers(response.trailingHeaders());
                }

                HttpResponse copy = HttpResponse.newBuffered(status, responseHeaders, content, trailers);
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
        this.channel.execute(() -> {
            IOException failure = new IOException("HTTP connection closed before response");
            this.channel.failed(this.socket, failure);
        });
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable failure, ProtoExceptionHolder holder) {
        this.channel.execute(() -> {
            this.channel.failed(this.socket, failure);
            this.socket.closeNow();
        });
        return ProtoStatus.Next;
    }
}

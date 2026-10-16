/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.HttpMessageUtils;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpObject;

/** 将聚合后的 HTTP/2 请求复制并交付给对应 stream。 */
final class Http2BufferedRequestHandler implements ProtoHandler<HttpObject, Object> {
    private final Supplier<RouteSelection>      selectedRoute;
    private final BiConsumer<Long, HttpRequest> received;
    private final BiConsumer<Long, String>      failed;

    Http2BufferedRequestHandler(Supplier<RouteSelection> selectedRoute, BiConsumer<Long, HttpRequest> received, BiConsumer<Long, String> failed) {
        this.selectedRoute = selectedRoute;
        this.received = received;
        this.failed = failed;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, //
            ProtoSndQueue<Object> target) {
        while (source.hasMore()) {
            HttpObject message = source.takeMessage();
            try {
                if (!(message instanceof FullHttpRequest request)) {
                    this.failed.accept(0L, "HTTP/2 request is not complete");
                    continue;
                }
                if (request.isBad()) {
                    this.failed.accept(request.streamId(), request.badReason());
                    continue;
                }

                String method = request.method().name();
                Map<String, List<String>> headers = HttpMessageUtils.headers(request);
                byte[] content = HttpMessageUtils.body(request.content());
                Map<String, List<String>> trailers = Map.of();
                if (request.hasTrailingHeaders()) {
                    trailers = HttpMessageUtils.headers(request.trailingHeaders());
                }

                int version = request.protocolVersion().majorVersion();
                HttpRequest copy = HttpRequest.newBuffered(method, request.uri(), headers, content, version, trailers);
                HttpMessageUtils.route(copy, this.selectedRoute.get());
                this.received.accept(request.streamId(), copy);
            } finally {
                message.release();
            }
        }
        return ProtoStatus.Next;
    }
}

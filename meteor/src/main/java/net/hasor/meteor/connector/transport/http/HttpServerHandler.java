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
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpObject;
import static net.hasor.meteor.connector.transport.http.HttpMessages.*;

/**
 * Copies and releases Neta messages before passing them to the ordered channel executor.
 */
final class HttpServerHandler implements ProtoHandler<HttpObject, Object> {
    private final Supplier<ContextRoutes.Route> selectedRoute;
    private final BiConsumer<Long, HttpRequest> received;
    private final BiConsumer<Long, String>      failed;

    public HttpServerHandler(Supplier<ContextRoutes.Route> selectedRoute, BiConsumer<Long, HttpRequest> received, BiConsumer<Long, String> failed) {
        this.selectedRoute = selectedRoute;
        this.received = received;
        this.failed = failed;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Exception {
        while (src.hasMore() && dst.hasSlot()) {
            HttpObject message = src.takeMessage();
            try {
                FullHttpRequest request;
                ContextRoutes.Route route;
                if (message instanceof Http1RequestAggregator.RoutedRequest routed) {
                    request = routed.request();
                    route = routed.route();
                } else if (message instanceof FullHttpRequest full && this.selectedRoute != null) {
                    request = full;
                    route = this.selectedRoute.get();
                } else {
                    throw new IOException("HTTP request has no header route selection");
                }

                if (request.isBad()) {
                    if (this.failed != null) {
                        this.failed.accept(request.streamId(), request.badReason());
                        continue;
                    }
                    throw new IOException("Malformed HTTP request: " + request.badReason());
                }

                String method = request.method().name();
                Map<String, List<String>> requestHeaders = headers(request);
                byte[] content = body(request.content());
                Map<String, List<String>> trailers = Map.of();
                if (request.hasTrailingHeaders()) {
                    trailers = headers(request.trailingHeaders());
                }

                int version = request.protocolVersion().majorVersion();
                HttpRequest copy = HttpRequest.newBuffered(method, request.uri(), requestHeaders, content, version, trailers);
                copy.route(route);
                if (this.received == null) {
                    dst.offerMessage(new HttpInbound(copy, keepAlive(request.protocolVersion(), request)));
                } else {
                    this.received.accept(request.streamId(), copy);
                }
            } finally {
                message.release();
            }
        }
        return ProtoStatus.Next;
    }
}

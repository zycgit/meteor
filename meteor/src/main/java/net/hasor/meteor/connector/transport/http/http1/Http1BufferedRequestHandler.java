/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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

/** 将聚合后的 HTTP/1 请求复制成不依赖 Neta 缓冲区的入站消息。 */
final class Http1BufferedRequestHandler implements ProtoHandler<HttpObject, Object> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, ProtoSndQueue<Object> target) throws Exception {
        while (source.hasMore() && target.hasSlot()) {
            HttpObject message = source.takeMessage();
            try {
                if (!(message instanceof Http1RoutedRequestAggregator.RoutedRequest routed)) {
                    throw new IOException("HTTP/1 request has no header route selection");
                }

                FullHttpRequest request = routed.request();
                if (request.isBad()) {
                    throw new IOException("Malformed HTTP/1 request: " + request.badReason());
                }

                RouteSelection route = routed.route();
                String method = request.method().name();
                Map<String, List<String>> headers = HttpMessageUtils.headers(request);
                byte[] content = HttpMessageUtils.body(request.content());
                Map<String, List<String>> trailers = Map.of();
                if (request.hasTrailingHeaders()) {
                    trailers = HttpMessageUtils.headers(request.trailingHeaders());
                }

                int version = request.protocolVersion().majorVersion();
                HttpRequest copy = HttpRequest.newBuffered(method, request.uri(), headers, content, version, trailers);
                HttpMessageUtils.route(copy, route);
                boolean keepAlive = HttpMessageUtils.keepAlive(request.protocolVersion(), request);
                target.offerMessage(new Http1InboundRequest(copy, keepAlive));
            } finally {
                message.release();
            }
        }
        return ProtoStatus.Next;
    }
}

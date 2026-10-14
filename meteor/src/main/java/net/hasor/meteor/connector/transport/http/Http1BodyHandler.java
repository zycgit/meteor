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
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/** 按挂载点交付增量正文；普通消息继续交给 Neta 聚合器。 */
final class Http1BodyHandler implements ProtoHandler<HttpObject, HttpObject> {
    private final HttpNetworkChannel        channel;
    private final NetChannel                socket;
    private final BooleanSupplier           streamResponse;
    private final Consumer<Object>          received;
    private final Map<String, List<String>> headers  = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> trailers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private       HttpBody                  body;
    private       ContextRoutes.Route       route;
    private       boolean                   routePending;
    private       String                    method;
    private       String                    uri;
    private       int                       status;
    private       HttpVersion               version;
    private       ReadPause                 pause;

    public Http1BodyHandler(HttpNetworkChannel channel, NetChannel socket, BooleanSupplier streamResponse, Consumer<Object> received) {
        this.channel = channel;
        this.socket = socket;
        this.streamResponse = streamResponse;
        this.received = received;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, ProtoSndQueue<HttpObject> target) throws Exception {
        if (this.pause != null && (this.body == null || this.body.writableBytes() > 0)) {
            this.socket.resumeRead(this.pause);
            this.pause = null;
        }

        while (source.hasMore() && target.hasSlot()) {
            if (this.routePending) {
                // 真实头仍留在源队列，容量为一个槽位时也能在下轮继续。
                HttpObject headers = new Http1RequestAggregator.RouteHeaders(this.route);
                boolean offered = false;
                try {
                    offered = target.offerMessage(headers);
                    if (!offered) {
                        return ProtoStatus.Next;
                    }
                } finally {
                    if (!offered) {
                        headers.release();
                    }
                }

                this.routePending = false;
                this.route = null;
                continue;
            }

            HttpObject message = source.peekMessage();
            this.begin(message);
            if (this.body == null) {
                target.offerMessage(source.takeMessage());
                continue;
            }

            if (message.isBad()) {
                throw new IOException("Malformed HTTP message: " + message.badReason());
            }

            // 缓冲耗尽时保留当前正文块，不阻塞协议回调，也不继续读取网络。
            if (message instanceof HttpContent content && content.content().readableBytes() > 0) {
                int count = Math.min(this.body.writableBytes(), content.content().readableBytes());
                if (count == 0) {
                    if (this.pause == null) {
                        this.pause = this.socket.pauseRead();
                    }
                    return ProtoStatus.Next;
                }

                byte[] bytes = new byte[count];
                content.content().readBytes(bytes);
                this.body.offer(bytes, () -> this.channel.execute(context::resumeReceive));
                if (content.content().readableBytes() > 0) {
                    continue;
                }
            }

            source.takeMessage();
            try {
                this.consume(message);
            } finally {
                message.release();
            }
        }
        return ProtoStatus.Next;
    }

    private void begin(HttpObject message) {
        if (message instanceof DefaultHttpRequest request) {
            this.route = this.channel.route(request.uri());
            if (this.channel.streamBody(this.route)) {
                this.method = request.method().name();
                this.uri = request.uri();
                this.version = request.protocolVersion();
                this.body = new HttpBody(65536);
            } else {
                this.routePending = true;
            }
        } else if (message instanceof DefaultHttpResponse response) {
            if (response.status().code() >= 200 && this.streamResponse.getAsBoolean()) {
                this.status = response.status().code();
                this.version = response.protocolVersion();
                this.body = new HttpBody(65536);
            }
        }
    }

    private void consume(HttpObject message) {
        if (message instanceof HttpHeaders fields) {
            Map<String, List<String>> destination = this.headers;
            if (message instanceof TrailerHttpHeaders) {
                destination = this.trailers;
            }
            HttpMessages.append(destination, fields);
        }

        // 头部结束即可交付正文读取端；只有被选中的增量消息绕过完整聚合。
        if (message instanceof LastHttpHeaders) {
            HttpHeaders fields = new DefaultHttpHeaders();
            HttpMessages.write(this.headers, fields);
            boolean keepAlive = HttpMessages.keepAlive(this.version, fields);
            if (this.method != null) {
                HttpRequest request = HttpRequest.newStreaming(this.method, this.uri, this.headers, this.body, 1);
                request.route(this.route);
                this.received.accept(new HttpInbound(request, keepAlive));
            } else {
                HttpResponse response = HttpResponse.newStreaming(this.status, this.headers, this.body, Map.of());
                this.received.accept(new HttpInboundResponse(response, keepAlive));
            }
        }

        if (message instanceof LastHttpContent) {
            this.body.finish(this.trailers);
            this.body = null;
            this.route = null;
            this.method = null;
            this.uri = null;
            this.headers.clear();
            this.trailers.clear();
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.routePending = false;
        this.route = null;
        this.fail(new IOException("HTTP connection closed before body ended"));
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable error, ProtoExceptionHolder holder) {
        this.fail(error);
        return ProtoStatus.Next;
    }

    private void fail(Throwable error) {
        if (this.body != null) {
            this.body.fail(error);
        }
    }
}

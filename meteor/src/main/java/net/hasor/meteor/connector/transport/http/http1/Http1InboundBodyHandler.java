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
import java.util.TreeMap;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/** 按挂载点交付增量正文；普通消息继续交给 Neta 聚合器。 */
final class Http1InboundBodyHandler implements ProtoHandler<HttpObject, HttpObject> {
    private final HttpChannel               channel;
    private final Map<String, List<String>> headers  = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> trailers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private       HttpBodyStream            bodyStream;
    private       RouteSelection            route;
    private       boolean                   routePending;
    private       String                    method;
    private       String                    uri;
    private       int                       status;
    private       HttpVersion               version;
    private       ReadPause                 pause;

    Http1InboundBodyHandler(HttpChannel channel) {
        this.channel = channel;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, ProtoSndQueue<HttpObject> target) throws Exception {
        // 使用本次回调所属的物理连接，客户端重连不会改变旧 Handler 的归属。
        NetChannel socket = (NetChannel) context.getChannel();
        if (this.pause != null && (this.bodyStream == null || this.bodyStream.writableBytes() > 0)) {
            socket.resumeRead(this.pause);
            this.pause = null;
        }

        while (source.hasMore() && target.hasSlot()) {
            if (this.routePending) {
                // 真实头仍留在源队列，容量为一个槽位时也能在下轮继续。
                HttpObject headers = new Http1RoutedRequestAggregator.RoutedHeaders(this.route);
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
            if (this.bodyStream == null) {
                target.offerMessage(source.takeMessage());
                continue;
            }

            if (message.isBad()) {
                throw new IOException("Malformed HTTP message: " + message.badReason());
            }

            // 缓冲耗尽时保留当前正文块，不阻塞协议回调，也不继续读取网络。
            if (message instanceof HttpContent content && content.content().readableBytes() > 0) {
                int count = Math.min(this.bodyStream.writableBytes(), content.content().readableBytes());
                if (count == 0) {
                    if (this.pause == null) {
                        this.pause = socket.pauseRead();
                    }
                    return ProtoStatus.Next;
                }

                byte[] bytes = new byte[count];
                content.content().readBytes(bytes);
                this.bodyStream.offer(bytes, () -> this.channel.execute(context::resumeReceive));
                if (content.content().readableBytes() > 0) {
                    continue;
                }
            }

            source.takeMessage();
            try {
                this.consume(message, socket);
            } finally {
                message.release();
            }
        }
        return ProtoStatus.Next;
    }

    private void begin(HttpObject message) {
        if (message instanceof DefaultHttpRequest request) {
            // 请求头到达时固定本次路由，并据此选择正文模式。
            Http1ServerChannel server = (Http1ServerChannel) this.channel;
            this.route = server.selectRoute(request.uri());
            if (server.streamRequest(this.route)) {
                this.method = request.method().name();
                this.uri = request.uri();
                this.version = request.protocolVersion();
                this.bodyStream = new HttpBodyStream(65536);
            } else {
                this.routePending = true;
            }
        } else if (message instanceof DefaultHttpResponse response) {
            // 响应模式需要结合当前请求，交换状态仍由客户端通道管理。
            Http1ClientChannel client = (Http1ClientChannel) this.channel;
            if (response.status().code() >= 200 && client.streamResponse()) {
                this.status = response.status().code();
                this.version = response.protocolVersion();
                this.bodyStream = new HttpBodyStream(65536);
            }
        }
    }

    private void consume(HttpObject message, NetChannel socket) {
        if (message instanceof HttpHeaders fields) {
            Map<String, List<String>> destination = this.headers;
            if (message instanceof TrailerHttpHeaders) {
                destination = this.trailers;
            }
            HttpMessageUtils.append(destination, fields);
        }

        // 头部结束即可交付正文读取端；只有被选中的增量消息绕过完整聚合。
        if (message instanceof LastHttpHeaders) {
            HttpHeaders fields = new DefaultHttpHeaders();
            HttpMessageUtils.write(this.headers, fields);
            boolean keepAlive = HttpMessageUtils.keepAlive(this.version, fields);
            if (this.method != null) {
                HttpRequest request = HttpRequest.newStreaming(this.method, this.uri, this.headers, this.bodyStream, 1);
                HttpMessageUtils.route(request, this.route);
                Http1ServerChannel server = (Http1ServerChannel) this.channel;
                server.received(request, keepAlive);
            } else {
                HttpResponse response = HttpResponse.newStreaming(this.status, this.headers, this.bodyStream, Map.of());
                Http1ClientChannel client = (Http1ClientChannel) this.channel;
                client.received(socket, response, keepAlive);
            }
        }

        if (message instanceof LastHttpContent) {
            this.bodyStream.finish(this.trailers);
            this.bodyStream = null;
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
        if (this.bodyStream != null) {
            this.bodyStream.fail(error);
        }
    }
}

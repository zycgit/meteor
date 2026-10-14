/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ContextRoutes;
import net.hasor.meteor.connector.transport.ChannelFactory;
import net.hasor.meteor.connector.transport.ConnectionEvents;
import net.hasor.meteor.connector.transport.InputCloseHandler;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.*;
import net.hasor.neta.codec.ssl.SslHandshakeEvent;
import net.hasor.neta.codec.ssl.SslHandshakePhaseEnum;

/** HTTP/2 完整消息通道；Neta 管理帧、流控和 stream，业务关联仅保留在 HttpExchange 中。 */
final class Http2Channel extends HttpNetworkChannel implements ProtoHandler<HttpObject, HttpObject> {
    private final    NetChannel              socket;
    private final    InterAddress            remote;
    private final    boolean                 server;
    private final    Map<Long, HttpExchange> pending      = new ConcurrentHashMap<>();
    private final    Map<HttpExchange, Long> streams      = new IdentityHashMap<>();
    private volatile boolean                 selected;
    private          long                    nextStreamId = 1;
    private volatile boolean                 accepting    = true;
    private volatile Throwable               closeCause;

    Http2Channel(ConnectorConfig config, SocketTransport transport, NetChannel socket, InterAddress remote, Executor executor, boolean server) {
        super(config, transport, executor);
        this.socket = socket;
        this.remote = remote;
        this.server = server;
    }

    void initialize(ProtoBuildContext stack, ChannelFactory<HttpExchange> factory, ProtoRoutingControl routing) throws Exception {
        this.receiver(factory.create(this));
        ConnectionEvents lifecycle = new ConnectionEvents(() -> {
            this.selected = true;
            this.execute(this::manageChannel);
        }, () -> {
            this.execute(this::terminated);
        }, failure -> {
            this.closeCause = failure;
            this.execute(this::close);
        });
        stack.addLastDecoder("lifecycle", lifecycle);

        //
        int limit = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
        ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplex(this.server))//
                .nextDuplex("h2-message", new Http2ObjectDuplex(this.server, routing))//
                .nextDecoder("h2-events", this)//
                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partitions -> {
                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                    partitions.policy(policy).byDefault(context -> {
                        context.addLast("lifecycle", new Http2ObjectStreamManager(partitions.control(), policy));
                    }).byInitializer(context -> {
                        this.initializeBody(context, limit);
                    });
                }).config(stack);

        stack.addLastDecoder("input-close", new InputCloseHandler());

        // 回调只复制消息，释放 Neta 缓冲；收发及关闭统一进入连接执行器。
        this.socket.subscribe(event -> this.selected && (event.isInbound() || !event.isSuccess()), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                this.closeCause = event.getError();
                this.execute(this::close);
            } else if (event.getData() instanceof FullHttpResponse response) {
                try {
                    long id = response.streamId();
                    if (response.isBad()) {
                        this.invalidMessage(id, response.badReason());
                        return;
                    }

                    Map<String, List<String>> trailers = Map.of();
                    if (response.hasTrailingHeaders()) {
                        trailers = HttpMessages.headers(response.trailingHeaders());
                    }

                    int status = response.status().code();
                    Map<String, List<String>> headers = HttpMessages.headers(response);
                    byte[] body = HttpMessages.body(response.content());
                    HttpResponse copy = HttpResponse.newBuffered(status, headers, body, trailers);
                    this.execute(() -> this.response(id, copy));
                } finally {
                    response.release();
                }
            } else if (event.getData() instanceof SoInputCloseEvent) {
                this.execute(this::close);
            }
        });
    }

    private void initializeBody(ProtoBuildContext stack, int limit) {
        // 每个 stream 的分区独立保存头部匹配结果，不能放在连接字段中。
        AtomicReference<ContextRoutes.Route> selectedRoute = new AtomicReference<>();
        ProtoRoutingDataSelector<HttpObject, HttpObject> selector = (context, source, target) -> {
            boolean streaming;
            if (!this.server) {
                // 出站分区先发送请求，必须在 onActive 就确定正文模式。
                PartitionKey key = PartitionKey.findKey(context);
                long id = Long.parseLong(key.getKey());
                HttpExchange exchange = this.pending.get(id);
                HttpRequest request = exchange == null ? null : exchange.request();
                streaming = request != null && (request.stream() != null || this.streamResponseBody());
            } else {
                HttpObject first = source.peekMessage();
                if (first == null) {
                    return null;
                }
                if (first instanceof DefaultHttpRequest request) {
                    ContextRoutes.Route route = this.route(request.uri());
                    selectedRoute.set(route);
                    streaming = this.streamBody(route);
                } else if (first instanceof FullHttpRequest request) {
                    // h2c 升级首请求已经聚合，仍须为 stream 1 保存匹配结果。
                    selectedRoute.set(this.route(request.uri()));
                    streaming = false;
                } else {
                    streaming = false;
                }
            }
            return streaming ? "stream" : "complete";
        };

        ProtoHelper.standard().nextRouteAsStatic("body-mode", selector, routing -> {
            routing.branchByInitializer("stream", branch -> {
                Http2BodyHandler handler = new Http2BodyHandler((id, request) -> {
                    request.route(selectedRoute.get());
                    this.execute(() -> this.request(id, request));
                }, (id, response) -> {
                    this.execute(() -> this.response(id, response));
                }, (id, failure) -> {
                    this.invalidMessage(id, failure.getMessage());
                });
                branch.addLastDecoder("body", handler);
            });
            routing.branchByInitializer("complete", branch -> {
                if (this.server) {
                    branch.addLastDecoder("aggregate", new HttpRequestAggregator(limit));
                    Supplier<ContextRoutes.Route> routes = selectedRoute::get;
                    BiConsumer<Long, HttpRequest> received = (id, request) -> {
                        this.execute(() -> this.request(id, request));
                    };
                    HttpServerHandler handler = new HttpServerHandler(routes, received, this::invalidMessage);
                    branch.addLastDecoder("messages", handler);
                } else {
                    branch.addLastDecoder("aggregate", new HttpResponseAggregator(limit));
                }
            });
        }).config(stack);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent notification) {
        if (notification.getData() instanceof SslHandshakeEvent handshake && handshake.getPhase() == SslHandshakePhaseEnum.COMPLETE) {
            String protocol = handshake.getContext().getApplicationProtocol();
            if (!StringUtils.equals("h2", protocol)) {
                this.closeCause = new IOException("HTTP/2 requires ALPN h2, negotiated: " + protocol);
                this.execute(this::close);
                return false;
            }
        }

        if (notification.getData() instanceof Http2ResetEvent event && !event.isResponseComplete()) {
            IOException failure = new IOException("HTTP/2 stream reset: " + event.errorCode());
            this.execute(() -> this.reset(event.streamId(), failure));
        } else if (notification.getData() instanceof Http2GoawayEvent event) {
            this.execute(() -> this.goaway(event));
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, ProtoSndQueue<HttpObject> target) {
        while (source.hasMore() && target.hasSlot()) {
            target.offerMessage(source.takeMessage());
        }
        return ProtoStatus.Next;
    }

    @Override
    public InterAddress getLocal() {
        return this.localAddress(this.socket);
    }

    @Override
    public InterAddress getRemote() {
        return this.remote;
    }

    @Override
    public boolean isOpen() {
        return !this.socket.isClose();
    }

    @Override
    public Future<Void> write(HttpExchange exchange) {
        BasicFuture<Void> sent = new BasicFuture<>();
        sent.onFailed(done -> exchange.complete(done.getCause()));
        if (this.admitWrite(sent)) {
            this.executeWrite(this.executor, sent, () -> this.write(exchange, sent));
        }

        sent.onCancel(done -> exchange.complete(new CancellationException("HTTP/2 write cancelled")));
        return sent;
    }

    private void write(HttpExchange exchange, BasicFuture<Void> sent) {
        if (sent.isCancelled()) {
            return;
        }

        HttpHeaders message;
        long id;
        if (this.server) {
            Long stream = this.streams.get(exchange);
            HttpResponse response = exchange.response().getResult();
            if (stream == null || response == null) {
                sent.failed(new IllegalStateException("HTTP/2 response has no pending exchange"));
                return;
            }

            id = stream;
            if (response.stream() != null) {
                DefaultHttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.valueOf(response.status()));
                this.writeBody(id, exchange, headers, response.headers(), response.stream(), response.trailers(), sent);
                return;
            }

            FullHttpResponse out = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.valueOf(response.status()), ByteBuf.wrap(response.body()));
            HttpMessages.write(response.headers(), out);
            HttpMessages.write(response.trailers(), out.trailingHeaders());
            message = out;
        } else {
            boolean full = this.pending.size() >= this.config.integer("maxPendingRequests", 1024);
            if (!this.isOpen() || !this.accepting || full || exchange.completion().isDone()) {
                IOException failure = new IOException("HTTP/2 channel unavailable or request completed");
                sent.failed(failure);
                exchange.complete(failure);
                return;
            }

            id = this.nextStreamId;
            this.nextStreamId += 2;
            this.track(id, exchange);
            HttpRequest request = exchange.request();
            if (request.stream() != null) {
                DefaultHttpRequest headers = new DefaultHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.valueOf(request.method()), request.uri());
                headers.scheme(this.config.option("httpScheme", "http"));
                Map<String, List<String>> fields = new HashMap<>(request.headers());
                fields.put("Host", List.of(this.remote.getHostPort()));
                this.writeBody(id, exchange, headers, fields, request.stream(), request.trailers(), sent);
                return;
            }

            FullHttpRequest out = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.valueOf(request.method()), request.uri(), ByteBuf.wrap(request.body()));
            out.scheme(this.config.option("httpScheme", "http"));
            HttpMessages.write(request.headers(), out);
            out.setHeader("Host", this.remote.getHostPort());
            HttpMessages.write(request.trailers(), out.trailingHeaders());
            message = out;
        }
        message.streamId(id);
        int length = ((HttpContent) message).content().readableBytes();
        if (length > this.config.integer("maxFrameSize", 16 * 1024 * 1024)) {
            message.release();
            IOException failure = new IOException("HTTP/2 message too large");
            exchange.complete(failure);
            sent.failed(failure);
            return;
        }

        message.setHeader("Content-Length", String.valueOf(length));
        if (this.server) {
            this.remove(id);
        }

        this.writeSocket(this.socket, message).onFinal(done -> this.execute(() -> {
            Throwable failure = done.getCause();
            if (done.isCancelled()) {
                failure = new CancellationException("HTTP/2 write cancelled");
            }

            this.completeWrite(sent, null, failure);
            if (failure != null || this.server) {
                exchange.complete(failure);
            }

            this.closeAfterGoaway();
        }));
    }

    private void writeBody(long id, HttpExchange exchange, HttpObject start, Map<String, List<String>> fields, HttpBody body, Map<String, List<String>> trailers, BasicFuture<Void> sent) {
        start.streamId(id);
        LastHttpHeaders headers = new DefaultLastHttpHeaders();
        HttpMessages.write(fields, headers);
        headers.removeHeader("Content-Length");
        headers.streamId(id);
        HttpBodyWriter writer = new HttpBodyWriter(id, body, trailers, this::execute, message -> this.writeSocket(this.socket, message), sent);
        this.writeSocket(this.socket, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
            if (done.getCause() != null || done.isCancelled()) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new IOException("HTTP/2 headers cancelled");
                }
                body.fail(failure);
                sent.failed(failure);
                return;
            }
            writer.start();
        }));

        sent.onCompleted(done -> this.execute(() -> {
            if (this.server) {
                this.remove(id);
                exchange.complete(null);
            }
            this.closeAfterGoaway();
        }));
    }

    private void invalidMessage(long id, String reason) {
        this.execute(() -> {
            this.reset(id, new IOException("Malformed HTTP/2 message: " + reason));
            this.socket.fireEvent(Http2ResetEvent.class, new Http2ResetEvent(id, Http2ResetEvent.INTERNAL_ERROR));
        });
    }

    private void request(long id, HttpRequest request) {
        if (!this.accepting || this.pending.size() >= this.config.integer("maxPendingRequests", 1024)) {
            this.socket.fireEvent(Http2ResetEvent.class, new Http2ResetEvent(id, Http2ResetEvent.REFUSED));
            if (request.stream() != null) {
                request.stream().fail(new IOException("HTTP/2 request refused"));
            }
            return;
        }

        HttpExchange exchange = new HttpExchange(request);
        this.track(id, exchange);
        try {
            this.received(exchange);
        } catch (Exception failure) {
            exchange.complete(failure);
        }
    }

    private void response(long id, HttpResponse response) {
        if (response.status() < 200) {
            return;
        }

        HttpExchange exchange = this.pending.get(id);
        if (exchange != null) {
            if (response.stream() == null) {
                this.remove(id);
                exchange.respond(response);
                exchange.complete(null);
            } else {
                response.stream().completion().onFinal(done -> this.execute(() -> {
                    // 失败由交换的终止回调发出 RST；提前摘除会丢失远端取消通知。
                    if (done.getCause() == null) {
                        this.remove(id);
                    }
                    exchange.complete(done.getCause());
                    this.closeAfterGoaway();
                }));
                exchange.respond(response);
            }
        } else if (response.stream() != null) {
            response.stream().close();
        }
        this.closeAfterGoaway();
    }

    private void track(long id, HttpExchange exchange) {
        this.pending.put(id, exchange);
        this.streams.put(exchange, id);
        exchange.completion().onFinal(done -> this.execute(() -> {
            if (this.remove(id) != null) {
                this.socket.fireEvent(Http2ResetEvent.class, new Http2ResetEvent(id, Http2ResetEvent.CANCEL));
                this.closeAfterGoaway();
            }
        }));
    }

    private HttpExchange remove(long id) {
        HttpExchange exchange = this.pending.remove(id);
        if (exchange != null) {
            this.streams.remove(exchange);
        }
        return exchange;
    }

    private void reset(long id, Throwable failure) {
        HttpExchange exchange = this.remove(id);
        if (exchange != null) {
            exchange.complete(failure);
        }
        this.closeAfterGoaway();
    }

    private void goaway(Http2GoawayEvent event) {
        this.accepting = false;
        for (Long id : new ArrayList<>(this.pending.keySet())) {
            if ((!this.server && id > event.lastAcceptedId()) || event.errorCode() != 0) {
                this.reset(id, new IOException("HTTP/2 peer no longer accepts stream " + id));
            }
        }

        this.closeAfterGoaway();
    }

    private void closeAfterGoaway() {
        if (!this.accepting && this.pending.isEmpty()) {
            this.drainAndClose();
        }
    }

    private void terminated() {
        this.accepting = false;
        for (HttpExchange exchange : new ArrayList<>(this.pending.values())) {
            Throwable failure = this.closeCause;
            if (failure == null) {
                failure = new IOException("HTTP/2 connection closed");
            }
            exchange.complete(failure);
        }

        this.pending.clear();
        this.streams.clear();
        this.forgetChannel();
    }

    @Override
    public Future<Void> close() {
        this.accepting = false;
        this.socket.closeNow();
        return this.closeFuture();
    }
}

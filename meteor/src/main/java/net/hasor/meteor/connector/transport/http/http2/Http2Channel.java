/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
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
final class Http2Channel extends HttpChannel implements ProtoHandler<HttpObject, HttpObject> {
    private final    NetChannel                 socket;
    private final    InterAddress               remote;
    private final    boolean                    server;
    private final    String                     scheme;
    private final    boolean                    upgrades;
    private final    HttpApplicationInitializer application;
    private final    BasicFuture<Void>          remoteSettings = new BasicFuture<>();
    private final    Map<Long, HttpExchange>    pending        = new ConcurrentHashMap<>();
    private final    Map<HttpExchange, Long>    streams        = new IdentityHashMap<>();
    private volatile boolean                    selected;
    private          long                       nextStreamId   = 1;
    private volatile boolean                    accepting      = true;
    private volatile Throwable                  closeCause;

    Http2Channel(TransportConfig config, SocketRuntime transport, //
            NetChannel socket, InterAddress remote, Executor executor, //
            RouteTable<HttpExchange> routes, String initialMountId, boolean server, String scheme, boolean upgrades, HttpApplicationInitializer application) {
        super(config, transport, executor, routes, initialMountId);
        this.socket = socket;
        this.remote = remote;
        this.server = server;
        this.scheme = scheme;
        this.upgrades = upgrades;
        this.application = application;
    }

    void initialize(ProtoBuildContext stack, ChannelListenerFactory<HttpExchange> factory, //
            ProtoRoutingControl routing) throws Exception {
        if (!this.server && !this.attachReceiver(factory)) {
            this.socket.closeNow();
            return;
        }

        ChannelLifecycleHandler lifecycleHandler = new ChannelLifecycleHandler(() -> {
            try {
                // Neta 会预初始化所有 HTTP 分支，此处只发布已被当前连接选中的分支。
                if (this.server && !this.attachReceiver(factory)) {
                    this.socket.closeNow();
                    return;
                }
                this.selected = true;
                this.execute(() -> {
                    if (!this.server && this.upgrades) {
                        // Neta 在首次 flush 才发送前言；等待 SETTINGS 前先启动连接协商。
                        this.socket.flush().onFailed(done -> this.remoteSettings.failed(done.getCause()));
                    }
                    this.notifyConnected();
                });
            } catch (Exception failure) {
                this.closeCause = failure;
                this.socket.closeNow();
            }
        }, () -> {
            this.execute(this::terminated);
        }, this::fail);
        stack.addLastDecoder("lifecycle", lifecycleHandler);

        // 先完成连接级帧处理，再按 HTTP/2 stream 分区处理消息。
        int limit = this.config.integer("maxFrameSize", 16 * 1024 * 1024);
        ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplex(this.server))//
                .nextEncoder("h2-settings-ready", new Http2RemoteSettingsAckHandler())//
                .nextDuplex("h2-message", new Http2ObjectDuplex(this.server, routing))//
                .nextDecoder("h2-events", this)//
                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partitions -> {
                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                    partitions.policy(policy).byDefault(context -> {
                        context.addLast("lifecycle", new Http2ObjectStreamManager(partitions.control(), policy));
                    }).byInitializer(context -> {
                        if (!this.upgrades) {
                            this.initializeBody(context, limit);
                        } else {
                            long id = Long.parseLong(PartitionKey.findKey(context).getKey());
                            HttpUpgradeCoordinator upgradeCoordinator = new HttpUpgradeCoordinator(//
                                    this, this.socket, this.server, 2, id, //
                                    request -> this.upgraded(id, request), () -> this.closeUpgrade(id), //
                                    this::isOpen, failure -> this.failUpgrade(id, failure));
                            ProtoInitializer body = branch -> this.initializeBody(branch, limit);
                            this.application.initialize(context, upgradeCoordinator, body);
                        }
                    });
                }).config(stack);

        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());

        // 回调只复制消息，释放 Neta 缓冲；收发及关闭统一进入连接执行器。
        this.socket.subscribe(//
                event -> (this.selected || !this.server) && (event.isInbound() || !event.isSuccess()), //
                SubscribeMode.SYNC, event -> {
                    if (!event.isSuccess()) {
                        this.fail(event.getError());
                    } else if (event.getData() instanceof FullHttpResponse response) {
                        try {
                            long id = response.streamId();
                            if (response.isBad()) {
                                this.invalidMessage(id, response.badReason());
                                return;
                            }

                            Map<String, List<String>> trailers = Map.of();
                            if (response.hasTrailingHeaders()) {
                                trailers = HttpMessageUtils.headers(response.trailingHeaders());
                            }

                            int status = response.status().code();
                            Map<String, List<String>> headers = HttpMessageUtils.headers(response);
                            byte[] body = HttpMessageUtils.body(response.content());
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

    private void fail(Throwable failure) {
        this.closeCause = failure;
        this.execute(() -> {
            this.terminated();
            this.socket.closeNow();
        });
    }

    private void initializeBody(ProtoBuildContext stack, int limit) {
        // 每个 stream 的分区独立保存头部匹配结果，不能放在连接字段中。
        AtomicReference<RouteSelection> selectedRoute = new AtomicReference<>();
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
                    RouteSelection route = this.route(request.uri());
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
                Http2InboundBodyHandler handler = new Http2InboundBodyHandler(this, selectedRoute::get);
                branch.addLastDecoder("body", handler);
            });
            routing.branchByInitializer("complete", branch -> {
                if (this.server) {
                    branch.addLastDecoder("aggregate", new HttpRequestAggregator(limit));
                    Http2BufferedRequestHandler handler = new Http2BufferedRequestHandler(this, selectedRoute::get);
                    branch.addLastDecoder("messages", handler);
                } else {
                    branch.addLastDecoder("aggregate", new HttpResponseAggregator(limit));
                }
            });
        }).config(stack);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent notification) {
        Object eventData = notification.getData();
        if (eventData instanceof SslHandshakeEvent handshake && //
                handshake.getPhase() == SslHandshakePhaseEnum.COMPLETE) {
            String protocol = handshake.getContext().getApplicationProtocol();
            if (!StringUtils.equals("h2", protocol)) {
                this.closeCause = new IOException("HTTP/2 requires ALPN h2, negotiated: " + protocol);
                this.execute(this::close);
                return false;
            }
        }

        if (eventData instanceof Http2ResetEvent event && !event.isResponseComplete()) {
            IOException failure = new IOException("HTTP/2 stream reset: " + event.errorCode());
            this.execute(() -> this.reset(event.streamId(), failure));
        } else if (eventData instanceof Http2GoawayEvent event) {
            this.execute(() -> this.goaway(event));
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, //
            ProtoRcvQueue<HttpObject> source, ProtoSndQueue<HttpObject> target) {
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
        exchange.completion().onFailed(done -> sent.failed(done.getCause()));
        if (this.admitWrite(sent)) {
            this.executeWrite(sent, () -> this.write(exchange, sent));
        }

        sent.onCancel(done -> exchange.complete(new CancellationException("HTTP/2 write cancelled")));
        return sent;
    }

    private void write(HttpExchange exchange, BasicFuture<Void> sent) {
        if (sent.isDone()) {
            return;
        }

        HttpRequest opening = exchange.request();
        boolean connect = StringUtils.equalsIgnoreCase("CONNECT", opening.method());
        boolean extendedConnect = !this.server && connect && opening.header(":protocol") != null;
        if (extendedConnect) {
            if (!this.remoteSettings.isDone()) {
                Runnable retry = () -> this.write(exchange, sent);
                this.remoteSettings.onFinal(done -> this.executeWrite(sent, retry));
                return;
            }
            if (this.remoteSettings.getCause() != null) {
                sent.failed(this.remoteSettings.getCause());
                return;
            }
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
                HttpStatus status = HttpStatus.valueOf(response.status());
                DefaultHttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_2_0, status);
                this.writeBody(id, exchange, headers, response.headers(), response.stream(), response.trailers(), sent);
                return;
            }

            HttpStatus status = HttpStatus.valueOf(response.status());
            ByteBuf content = ByteBuf.wrap(response.body());
            FullHttpResponse out = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, status, content);
            HttpMessageUtils.write(response.headers(), out);
            HttpMessageUtils.write(response.trailers(), out.trailingHeaders());
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
            if (StringUtils.equalsIgnoreCase("CONNECT", request.method()) && request.header(":protocol") != null) {
                // 扩展 CONNECT 的头部不结束 stream；后续字节属于升级后的双向通道。
                DefaultHttpRequest start = new DefaultHttpRequest(//
                        HttpVersion.HTTP_2_0, HttpMethod.CONNECT, request.uri());
                start.scheme(this.scheme);
                start.streamId(id);
                LastHttpHeaders headers = new DefaultLastHttpHeaders();
                headers.streamId(id);
                HttpMessageUtils.write(request.headers(), headers);
                headers.setHeader("Host", this.remote.getHostPort());
                this.writeSocket(this.socket, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
                    Throwable failure = done.getCause();
                    if (done.isCancelled()) {
                        failure = new CancellationException("HTTP upgrade write cancelled");
                    }
                    this.completeWrite(sent, null, failure);
                    if (failure != null) {
                        exchange.complete(failure);
                    }
                }));
                return;
            }
            if (request.stream() != null) {
                HttpMethod method = HttpMethod.valueOf(request.method());
                DefaultHttpRequest headers = new DefaultHttpRequest(HttpVersion.HTTP_2_0, method, request.uri());
                headers.scheme(this.scheme);
                Map<String, List<String>> fields = new HashMap<>(request.headers());
                fields.put("Host", List.of(this.remote.getHostPort()));
                this.writeBody(id, exchange, headers, fields, request.stream(), request.trailers(), sent);
                return;
            }

            HttpMethod method = HttpMethod.valueOf(request.method());
            ByteBuf content = ByteBuf.wrap(request.body());
            FullHttpRequest out = new DefaultFullHttpRequest(//
                    HttpVersion.HTTP_2_0, method, request.uri(), content);
            out.scheme(this.scheme);
            HttpMessageUtils.write(request.headers(), out);
            out.setHeader("Host", this.remote.getHostPort());
            HttpMessageUtils.write(request.trailers(), out.trailingHeaders());
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

    private void writeBody(long id, HttpExchange exchange, HttpObject start, //
            Map<String, List<String>> fields, HttpBodyStream bodyStream, //
            Map<String, List<String>> trailers, BasicFuture<Void> sent) {
        start.streamId(id);
        LastHttpHeaders headers = new DefaultLastHttpHeaders();
        HttpMessageUtils.write(fields, headers);
        headers.removeHeader("Content-Length");
        headers.streamId(id);
        this.writeSocket(this.socket, new Object[] { start, headers }).onFinal(done -> this.execute(() -> {
            if (done.getCause() != null || done.isCancelled()) {
                Throwable failure = done.getCause();
                if (failure == null) {
                    failure = new IOException("HTTP/2 headers cancelled");
                }
                bodyStream.fail(failure);
                sent.failed(failure);
                return;
            }
            Function<Object, Future<?>> writer = message -> this.writeSocket(this.socket, message);
            this.startStreamingBodyWrite(id, bodyStream, trailers, writer, sent);
        }));

        sent.onCompleted(done -> this.execute(() -> {
            if (this.server) {
                this.remove(id);
                exchange.complete(null);
            }
            this.closeAfterGoaway();
        }));
    }

    void receiveRequest(long id, HttpRequest request, RouteSelection selectedRoute) {
        HttpMessageUtils.route(request, selectedRoute);
        this.execute(() -> this.request(id, request));
    }

    void receiveResponse(long id, HttpResponse response) {
        this.execute(() -> this.response(id, response));
    }

    void rejectMessage(long id, Throwable failure) {
        this.invalidMessage(id, failure.getMessage());
    }

    void rejectMessage(long id, String reason) {
        this.invalidMessage(id, reason);
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

    private HttpExchange upgraded(long id, HttpRequest request) {
        if (this.server) {
            if (!this.accepting || this.pending.size() >= this.config.integer("maxPendingRequests", 1024)) {
                // 握手已完成但 exchange 尚未登记，普通 reset 的 pending 守卫无法结束该 stream。
                this.socket.fireEvent(Http2ResetEvent.class, new Http2ResetEvent(id, Http2ResetEvent.REFUSED));
                throw new IllegalStateException("HTTP/2 endpoint does not accept another stream");
            }
            HttpMessageUtils.route(request, this.route(request.uri()));
            HttpExchange exchange = new HttpExchange(request);
            this.track(id, exchange);
            return exchange;
        }
        return this.pending.get(id);
    }

    private void closeUpgrade(long id) {
        this.execute(() -> this.failUpgrade(id, new IOException("HTTP upgrade stream closed")));
    }

    private void failUpgrade(long id, Throwable failure) {
        if (this.reset(id, failure)) {
            this.socket.fireEvent(Http2ResetEvent.class, new Http2ResetEvent(id, Http2ResetEvent.CANCEL));
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

    private boolean reset(long id, Throwable failure) {
        HttpExchange exchange = this.remove(id);
        if (exchange != null) {
            this.terminate(exchange, failure);
        }
        this.closeAfterGoaway();
        return exchange != null;
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
        Throwable settingsFailure = this.closeCause;
        if (settingsFailure == null) {
            settingsFailure = new IOException("HTTP/2 connection closed before settings");
        }
        this.remoteSettings.failed(settingsFailure);
        for (HttpExchange exchange : new ArrayList<>(this.pending.values())) {
            Throwable failure = this.closeCause;
            if (failure == null) {
                failure = new IOException("HTTP/2 connection closed");
            }
            this.terminate(exchange, failure);
        }

        this.pending.clear();
        this.streams.clear();
        this.notifyClosed();
    }

    @Override
    protected void prepareDrain() {
        if (this.server) {
            this.accepting = false;
        }
        // 尚未完成协商的 CONNECT 没有物理写出；排空时必须释放其 admission。
        this.remoteSettings.failed(new IOException("HTTP/2 channel is draining before settings"));
    }

    @Override
    public Future<Void> close() {
        this.accepting = false;
        this.socket.closeNow();
        return this.closeFuture();
    }

    /** 只有 Neta 已应用远端 SETTINGS，出站编码器才会生成对应 ACK。 */
    private final class Http2RemoteSettingsAckHandler implements ProtoHandler<Http2Frame, Http2Frame> {
        private static final Http2Frame ACK = Http2Frame.settingsAck();

        @Override
        public ProtoStatus onMessage(ProtoContext context, //
                ProtoRcvQueue<Http2Frame> source, ProtoSndQueue<Http2Frame> target) {
            while (source.hasMore() && target.hasSlot()) {
                Http2Frame frame = source.takeMessage();
                target.offerMessage(frame);
                if (frame.type() == ACK.type() && (frame.flags() & ACK.flags()) != 0) {
                    Http2Channel.this.execute(() -> Http2Channel.this.remoteSettings.completed(null));
                }
            }
            return ProtoStatus.Next;
        }
    }

}

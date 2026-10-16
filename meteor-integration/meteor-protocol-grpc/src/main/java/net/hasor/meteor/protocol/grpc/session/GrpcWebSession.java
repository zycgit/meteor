/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.util.Map;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcMethodShape;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCors;

/** Internal HTTP envelope adaptation; invocation and Flow lifecycles remain owned by GrpcSession. */
public final class GrpcWebSession implements ProtocolSession<HttpExchange> {
    private final NetworkChannel<HttpExchange> network;
    private final GrpcWebChannel               channel;
    private final GrpcSession                  delegate;
    private final GrpcHttpCodec                codec;
    private final GrpcWebCors                  cors;

    public GrpcWebSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> network, ObjLongConsumer<Payload> messages, GrpcMountRuntime runtime) {
        this.network = network;
        this.codec = runtime.codec();
        this.cors = new GrpcWebCors(config);
        this.channel = new GrpcWebChannel(config, network, runtime.workers());
        this.delegate = new GrpcSession(context, this.channel, messages, runtime);
    }

    @Override
    public Future<Void> ready() {
        return this.delegate.ready();
    }

    @Override
    public void connected() {
        this.delegate.connected();
    }

    @Override
    public void receive(HttpExchange exchange) {
        // 预检及 HTTP 媒体类型在浏览器协议边界处理，不进入 RPC 分派。
        HttpResponse immediate = this.cors.intercept(exchange.request());
        if (immediate == null && !GrpcWebCodec.accepts(exchange.request().header("content-type"))) {
            HttpResponse unsupported = HttpResponse.newBuffered(415, Map.of(), new byte[0], Map.of());
            immediate = this.cors.decorate(exchange.request(), unsupported);
        }
        if (immediate != null) {
            this.reply(exchange, immediate);
            return;
        }

        // 完整校验单请求消息后，再交由已有 gRPC 会话执行普通或服务端流式调用。
        this.channel.receive(exchange, nativeExchange -> {
            try {
                RequestPayload request = this.codec.open(nativeExchange.request());
                this.requireUnaryInput(request);
                this.delegate.receive(nativeExchange);
            } catch (Exception failure) {
                if (nativeExchange.respond(this.codec.error(failure))) {
                    this.channel.write(nativeExchange);
                }
            }
        });
    }

    @Override
    public Future<Void> send(Payload payload) {
        try {
            if (payload instanceof RequestPayload request) {
                this.requireUnaryInput(request);
            }
            return this.delegate.send(payload);
        } catch (Exception failure) {
            BasicFuture<Void> failed = new BasicFuture<>();
            failed.failed(failure);
            return failed;
        }
    }

    private void requireUnaryInput(RequestPayload request) {
        if (request.isMessage()) {
            throw new GrpcStatusException(12, "gRPC-Web requires a response", null, null);
        }
        GrpcMethodShape method = new GrpcMethodShape(this.codec.target(request));
        if (method.streamingRequest()) {
            throw new GrpcStatusException(12, "gRPC-Web does not support client streaming", null, null);
        }
    }

    private void reply(HttpExchange exchange, HttpResponse response) {
        if (!exchange.respond(response)) {
            return;
        }
        try {
            this.network.write(exchange).onFinal(done -> {
                if (done.getCause() != null) {
                    exchange.complete(done.getCause());
                } else if (done.isCancelled()) {
                    exchange.complete(new IOException("gRPC-Web HTTP response cancelled"));
                }
            });
        } catch (Exception failure) {
            exchange.complete(failure);
        }
    }

    @Override
    public void prepareDrain() {
        this.delegate.prepareDrain();
    }

    @Override
    public void closed(Throwable cause) {
        this.channel.closed(cause);
        this.delegate.closed(cause);
    }
}

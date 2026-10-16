/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCors;

/** Owns envelope conversions and exchange correlation while borrowing the physical HTTP channel. */
final class GrpcWebChannel implements NetworkChannel<HttpExchange> {
    private static final int                             BUFFER_CAPACITY = 65535;
    private final        ProtocolConfig                  config;
    private final        NetworkChannel<HttpExchange>    network;
    private final        Executor                        workers;
    private final        GrpcWebCodec                    codec;
    private final        GrpcWebCors                     cors;
    private final        Map<HttpExchange, HttpExchange> incoming        = new HashMap<>();
    private final        Set<HttpExchange>               active          = new HashSet<>();
    private              Throwable                       closeCause;

    GrpcWebChannel(ProtocolConfig config, NetworkChannel<HttpExchange> network, Executor workers) {
        this.config = config;
        this.network = network;
        this.workers = workers;
        this.codec = new GrpcWebCodec(config);
        this.cors = new GrpcWebCors(config);
    }

    void receive(HttpExchange wire, Consumer<HttpExchange> receiver) {
        if (this.closeCause != null) {
            wire.complete(this.closeCause);
            return;
        }
        this.active.add(wire);
        wire.completion().onFinal(done -> this.execute(() -> this.active.remove(wire)));
        HttpRequest request = wire.request();
        boolean text = GrpcWebCodec.isText(request.header("content-type"));
        if (request.stream() == null) {
            try {
                byte[] bytes = this.codec.decodeRequest(request.body(), text);
                this.deliver(wire, bytes, receiver);
            } catch (Exception failure) {
                this.reject(wire, failure);
            }
            return;
        }

        // gRPC-Web 只有单请求消息；校验 EOF 之后才允许 Dispatcher 看见请求。
        try {
            this.workers.execute(() -> {
                try {
                    ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                    this.codec.decodeRequest(request.stream(), decoded, text);
                    byte[] bytes = decoded.toByteArray();
                    this.execute(() -> this.deliver(wire, bytes, receiver));
                } catch (Exception failure) {
                    this.execute(() -> this.reject(wire, failure));
                }
            });
        } catch (RuntimeException failure) {
            wire.complete(failure);
        }
    }

    private void deliver(HttpExchange wire, byte[] bytes, Consumer<HttpExchange> receiver) {
        if (wire.completion().isDone() || this.closeCause != null) {
            if (this.closeCause != null) {
                wire.complete(this.closeCause);
            }
            return;
        }
        try {
            HttpRequest original = wire.request();
            String contentType = this.codec.grpcContentType(original.header("content-type"));
            Map<String, List<String>> headers = this.headers(original.headers(), contentType);
            headers.put("te", List.of("trailers"));

            // 已完整验证的单帧仍以流视图交付，复用 native gRPC 的服务端 Flow 链路。
            HttpBodyStream body = new HttpBodyStream(Math.max(1, bytes.length));
            body.offer(bytes, () -> {
            });
            body.finish(Map.of());
            HttpRequest request = HttpRequest.newStreaming(original.method(), original.uri(), headers, body, 2);
            HttpMessageUtils.route(request, original.route());
            HttpExchange translated = new HttpExchange(request);
            this.incoming.put(translated, wire);
            this.link(translated, wire, true);
            receiver.accept(translated);
        } catch (Exception failure) {
            this.reject(wire, failure);
        }
    }

    @Override
    public Future<Void> write(HttpExchange message) {
        if (this.closeCause != null) {
            message.complete(this.closeCause);
            return this.failed(this.closeCause);
        }
        try {
            HttpExchange wire = this.incoming.get(message);
            if (wire != null) {
                HttpResponse response = message.response().getResult();
                HttpResponse encoded = this.encodeResponse(wire, response);
                if (!wire.respond(this.cors.decorate(wire.request(), encoded))) {
                    throw new IOException("gRPC-Web HTTP exchange already responded");
                }
            } else {
                wire = this.request(message);
            }
            return this.submit(message, wire);
        } catch (Exception failure) {
            message.complete(failure);
            return this.failed(failure);
        }
    }

    private HttpExchange request(HttpExchange nativeExchange) throws IOException {
        HttpRequest request = nativeExchange.request();
        boolean text = Boolean.parseBoolean(this.config.option("webText", "false"));
        String contentType = this.codec.webContentType(request.header("content-type"), text);
        Map<String, List<String>> headers = this.headers(request.headers(), contentType);
        headers.put("x-grpc-web", List.of("1"));
        headers.put("accept", List.of(contentType));
        int version = this.config.integer("httpVersion", 1);
        HttpBodyStream target = request.stream() == null ? null : new HttpBodyStream(BUFFER_CAPACITY);
        HttpRequest encoded;
        if (target == null) {
            byte[] bytes = this.codec.encodeRequest(request.body(), text);
            encoded = HttpRequest.newBuffered(request.method(), request.uri(), headers, bytes, version, Map.of());
        } else {
            encoded = HttpRequest.newStreaming(request.method(), request.uri(), headers, target, version);
        }
        HttpExchange wire = new HttpExchange(encoded);
        this.link(nativeExchange, wire, false);
        wire.response().onFinal(done -> this.execute(() -> {
            if (done.getCause() != null) {
                nativeExchange.complete(done.getCause());
            } else if (!nativeExchange.completion().isDone()) {
                this.receiveResponse(nativeExchange, done.getResult());
            }
        }));
        if (target != null) {
            this.pump(nativeExchange, target, () -> {
                this.codec.encodeRequest(request.stream(), target.output(), text);
                return Map.of();
            });
        }
        return wire;
    }

    private HttpResponse encodeResponse(HttpExchange wire, HttpResponse response) throws IOException {
        boolean text = this.textResponse(wire.request());
        String contentType = this.codec.webContentType(response.header("content-type"), text);
        Map<String, List<String>> headers = this.headers(response.headers(), contentType);
        Map<String, List<String>> trailers = new LinkedHashMap<>(response.trailers());
        for (String name : List.of("grpc-status", "grpc-message", "grpc-status-details-bin")) {
            String value = response.header(name);
            if (value != null) {
                trailers.put(name, List.of(value));
            }
            this.remove(headers, name);
        }
        if (response.stream() == null) {
            byte[] body = this.codec.encodeResponse(response.body(), trailers, text);
            return HttpResponse.newBuffered(response.status(), headers, body, Map.of());
        }

        // 响应不聚合；有界队列把浏览器消费速度传递给原生 Flow 输出。
        HttpBodyStream output = new HttpBodyStream(BUFFER_CAPACITY);
        this.pump(wire, output, () -> {
            try {
                this.codec.encodeResponse(response.stream(), output.output(), text, () -> {
                    Map<String, List<String>> fields = new LinkedHashMap<>(trailers);
                    fields.putAll(response.stream().trailers());
                    return fields;
                });
            } catch (IOException failure) {
                if (!this.deadline(failure)) {
                    throw failure;
                }
                // 原生调用已取消订阅；Web 仍需把截止状态作为尾帧送达客户端。
                Map<String, List<String>> fields = new LinkedHashMap<>(trailers);
                fields.put("grpc-status", List.of("4"));
                fields.put("grpc-message", List.of("Deadline%20exceeded"));
                byte[] ending = this.codec.encodeResponse(new byte[0], fields, text);
                output.output().write(ending);
            }
            return Map.of();
        });
        return HttpResponse.newStreaming(response.status(), headers, output, Map.of());
    }

    private void receiveResponse(HttpExchange owner, HttpResponse response) {
        try {
            // HTTP 错误保留状态供原生 gRPC 映射，空正文的 header-only 错误也无需解帧。
            boolean headerOnly = response.header("grpc-status") != null;
            if (response.status() != 200 || headerOnly) {
                Map<String, List<String>> headers = new LinkedHashMap<>(response.headers());
                if (GrpcWebCodec.accepts(response.header("content-type"))) {
                    headers = this.headers(headers, this.codec.grpcContentType(response.header("content-type")));
                }
                owner.respond(HttpResponse.newBuffered(response.status(), headers, new byte[0], response.trailers()));
                return;
            }
            String contentType = this.codec.grpcContentType(response.header("content-type"));
            Map<String, List<String>> headers = this.headers(response.headers(), contentType);
            HttpBodyStream decoded = new HttpBodyStream(BUFFER_CAPACITY);
            HttpResponse translated = HttpResponse.newStreaming(response.status(), headers, decoded, Map.of());
            if (!owner.respond(translated)) {
                throw new IOException("gRPC response already delivered");
            }

            boolean text = GrpcWebCodec.isText(response.header("content-type"));
            String encoding = response.header("grpc-encoding");
            InputStream input = response.stream();
            if (input == null) {
                input = new ByteArrayInputStream(response.body());
            }
            InputStream source = input;
            this.pump(owner, decoded, () -> this.codec.decodeResponse(source, decoded.output(), text, encoding));
        } catch (Exception failure) {
            owner.complete(failure);
        }
    }

    private void pump(HttpExchange owner, HttpBodyStream output, //
            Callable<Map<String, List<String>>> transfer) {
        // 转换流可能尚未交给 HTTP 交换；创建者先关联取消，释放阻塞中的写入。
        owner.completion().onFailed(done -> output.fail(done.getCause()));
        try {
            this.workers.execute(() -> {
                try {
                    Map<String, List<String>> trailers = transfer.call();
                    // 正文 EOF 只结束该方向；HTTP 交换仍由物理读写和 RPC 生命周期结束。
                    output.finish(trailers);
                } catch (Exception failure) {
                    output.fail(failure);
                    owner.complete(failure);
                }
            });
        } catch (RuntimeException failure) {
            output.fail(failure);
            owner.complete(failure);
        }
    }

    private void link(HttpExchange nativeExchange, HttpExchange wire, boolean inbound) {
        this.active.add(nativeExchange);
        this.active.add(wire);
        nativeExchange.completion().onFinal(done -> {
            if (inbound && this.deadline(done.getCause())) {
                this.execute(() -> {
                    if (!wire.response().isDone()) {
                        this.reject(wire, new GrpcStatusException(4, "Deadline exceeded", null, null));
                    }
                });
            } else {
                wire.complete(done.getCause());
            }
            this.execute(() -> this.active.remove(nativeExchange));
        });
        wire.completion().onFinal(done -> this.execute(() -> {
            this.active.remove(wire);
            this.incoming.remove(nativeExchange);
        }));
        wire.completion().onFailed(done -> nativeExchange.complete(done.getCause()));
        wire.completion().onCompleted(done -> this.execute(() -> {
            // 真实网络结束可能先于排队中的解码；成功要等待 native 响应及其正文消费完成。
            nativeExchange.response().onFinal(response -> {
                if (response.getCause() != null) {
                    nativeExchange.complete(response.getCause());
                    return;
                }
                HttpBodyStream body = response.getResult().stream();
                if (body == null) {
                    nativeExchange.complete(null);
                } else {
                    body.completion().onFinal(consumed -> nativeExchange.complete(consumed.getCause()));
                }
            });
        }));
    }

    private boolean deadline(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MetTimeoutException) {
                return true;
            }
            if (current instanceof GrpcStatusException status && status.getCode() == 4) {
                return true;
            }
        }
        return false;
    }

    private Future<Void> submit(HttpExchange owner, HttpExchange wire) {
        BasicFuture<Void> sent = new BasicFuture<>();
        owner.completion().onFailed(done -> sent.failed(done.getCause()));
        if (wire.completion().getCause() != null) {
            sent.failed(wire.completion().getCause());
            return sent;
        }
        try {
            Future<Void> writing = this.network.write(wire);
            sent.onCancel(done -> {
                writing.cancel();
                CancellationException failure = new CancellationException("gRPC-Web write cancelled");
                wire.complete(failure);
                owner.complete(failure);
            });
            writing.onFinal(done -> {
                if (done.isCancelled()) {
                    sent.cancel();
                    CancellationException failure = new CancellationException("gRPC-Web write cancelled");
                    wire.complete(failure);
                    owner.complete(failure);
                } else if (done.getCause() != null) {
                    wire.complete(done.getCause());
                    owner.complete(done.getCause());
                    sent.failed(done.getCause());
                } else {
                    sent.completed(null);
                }
            });
        } catch (Exception failure) {
            wire.complete(failure);
            owner.complete(failure);
            sent.failed(failure);
        }
        return sent;
    }

    private void reject(HttpExchange wire, Throwable failure) {
        if (wire.completion().isDone()) {
            return;
        }
        try {
            GrpcStatusException status = GrpcStatusException.find(failure);
            int code = status == null ? 13 : status.getCode();
            String description = status == null ? "Invalid gRPC-Web request" : status.getDescription();
            String message = URLEncoder.encode(description, StandardCharsets.UTF_8).replace("+", "%20");
            Map<String, List<String>> trailers = Map.of( //
                    "grpc-status", List.of(Integer.toString(code)), "grpc-message", List.of(message));
            boolean text = this.textResponse(wire.request());
            byte[] body = this.codec.encodeResponse(new byte[0], trailers, text);
            String nativeType = this.codec.grpcContentType(wire.request().header("content-type"));
            String webType = this.codec.webContentType(nativeType, text);
            HttpResponse response = HttpResponse.newBuffered( //
                    200, Map.of("content-type", List.of(webType)), body, Map.of());
            if (wire.respond(this.cors.decorate(wire.request(), response))) {
                this.submit(wire, wire);
            }
            HttpBodyStream unread = wire.request().stream();
            if (unread != null) {
                wire.completion().onCompleted(done -> unread.fail(failure));
            }
        } catch (Exception encodingFailure) {
            wire.complete(encodingFailure);
        }
    }

    private boolean textResponse(HttpRequest request) {
        String accept = request.header("accept");
        if (accept != null) {
            for (String type : accept.split(",")) {
                if (GrpcWebCodec.isText(type.trim())) {
                    return true;
                }
            }
        }
        return GrpcWebCodec.isText(request.header("content-type"));
    }

    private Map<String, List<String>> headers(Map<String, List<String>> source, String contentType) {
        Map<String, List<String>> headers = new LinkedHashMap<>(source);
        for (String name : List.of("content-type", "content-length", "transfer-encoding", "connection", "te")) {
            this.remove(headers, name);
        }
        headers.put("content-type", List.of(contentType));
        return headers;
    }

    private void remove(Map<String, List<String>> headers, String name) {
        headers.keySet().removeIf(key -> StringUtils.equalsIgnoreCase(key, name));
    }

    private Future<Void> failed(Throwable failure) {
        BasicFuture<Void> failed = new BasicFuture<>();
        failed.failed(failure);
        return failed;
    }

    void closed(Throwable cause) {
        if (this.closeCause != null) {
            return;
        }
        this.closeCause = cause == null ? new IOException("gRPC-Web session closed") : cause;
        for (HttpExchange exchange : new ArrayList<>(this.active)) {
            exchange.complete(this.closeCause);
        }
        this.active.clear();
        this.incoming.clear();
    }

    @Override
    public InterAddress getLocal() {
        return this.network.getLocal();
    }

    @Override
    public InterAddress getRemote() {
        return this.network.getRemote();
    }

    @Override
    public boolean isOpen() {
        return this.closeCause == null && this.network.isOpen();
    }

    @Override
    public void execute(Runnable task) {
        this.network.execute(task);
    }

    @Override
    public Future<Void> close() {
        this.closed(null);
        return new BasicFuture<>((Void) null);
    }

    @Override
    public Future<Void> drainAndClose() {
        return this.close();
    }
}

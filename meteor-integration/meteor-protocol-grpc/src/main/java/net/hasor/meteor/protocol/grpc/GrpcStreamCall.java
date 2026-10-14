/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.http.HttpBody;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.rpc.MetCaller;

/** 将一次 RPC 的两条消息序列映射到 HTTP/2 的请求和响应方向。 */
final class GrpcStreamCall {
    private final    RequestPayload            request;
    private final    GrpcCodec                 codec;
    private final    GrpcMethod                method;
    private final    boolean                   client;
    private final    Executor                  workers;
    private final    Consumer<Runnable>        serial;
    private final    BiConsumer<Long, Payload> messages;
    private final    HttpBody                  output           = new HttpBody(65535);
    private final    BasicFuture<Void>         completion       = new BasicFuture<>();
    private final    AtomicBoolean             terminal         = new AtomicBoolean();
    private final    AtomicBoolean             responseStarted  = new AtomicBoolean();
    private final    ResponsePayload           receivedResponse = new ResponsePayload();
    private volatile Consumer<HttpResponse>    responseWriter;
    private volatile ResponsePayload           outgoingResponse;
    private final    GrpcOutbound              sending          = new GrpcOutbound(this);
    private volatile HttpExchange              exchange;
    private volatile HttpBody                  input;
    private volatile GrpcInbound               receiving;
    private volatile Map<String, List<String>> trailers         = Map.of("grpc-status", List.of("0"));

    public GrpcStreamCall(RequestPayload request, GrpcCodec codec, boolean client, Executor workers, Consumer<Runnable> serial, BiConsumer<Long, Payload> messages) {
        this.request = request;
        this.codec = codec;
        this.method = new GrpcMethod(codec.target(request));
        this.client = client;
        this.workers = workers;
        this.serial = serial;
        this.messages = messages;
        this.completion.onCancel(done -> this.cancel());
        // 调用期限与取消必须立即释放传输，不能等待慢消费方接收错误 trailers。
        this.request.completion().onFailed(done -> this.fail(done.getCause()));
    }

    public HttpBody output() {
        return this.output;
    }

    public Future<Void> completion() {
        return this.completion;
    }

    public void attach(HttpExchange exchange, Consumer<HttpResponse> responseWriter) {
        this.exchange = exchange;
        this.responseWriter = responseWriter;
        exchange.completion().onFailed(done -> this.fail(done.getCause()));
    }

    public void startRequest() {
        Object argument = this.request.getParameterValues().get(0);
        Flow.Publisher<?> source;
        if (this.method.streamingRequest()) {
            source = (Flow.Publisher<?>) argument;
        } else {
            source = MetCaller.fromIterable(Collections.singletonList(argument));
        }
        this.sending.start(source);
    }

    public void receiveRequest() {
        this.input = this.exchange.request().stream();
        String encoding = this.exchange.request().header("grpc-encoding");
        if (this.method.streamingRequest()) {
            this.receiving = new GrpcInbound(this, () -> this.readMessage(this.input, encoding, true), () -> {
            });
            this.request.addParameter(Flow.Publisher.class.getName(), this.receiving);
            this.deliver(this.request);
        } else {
            this.execute(() -> {
                try {
                    Object value = this.readMessage(this.input, encoding, true);
                    Object extra = this.readMessage(this.input, encoding, true);
                    if (value == null || extra != null) {
                        throw new IOException("gRPC method requires exactly one request message");
                    }
                    this.request.addParameter(this.method.requestType().getName(), value);
                    this.deliver(this.request);
                } catch (Throwable failure) {
                    this.receiveFailure(failure);
                }
            });
        }
    }

    public void receiveResponse(HttpResponse response) {
        try {
            if (response.header("grpc-status") != null) {
                this.codec.validateStatus(response, response.trailers());
            }
            this.codec.validateResponseHeaders(this.request, response);
            GrpcMetadata.from(this.receivedResponse).receive(response.headers(), false);
            this.codec.readMetadata(response.headers(), this.receivedResponse);
            this.input = response.stream();
            InputStream body = this.input;
            if (body == null) {
                body = new ByteArrayInputStream(response.body());
            }
            InputStream source = body;
            String encoding = response.header("grpc-encoding");
            if (this.method.streamingResponse()) {
                this.receiving = new GrpcInbound(this, () -> {
                    Object value = this.readMessage(source, encoding, false);
                    if (value == null) {
                        this.validateEnd(response);
                    }
                    return value;
                }, this::succeed);
                ResponsePayload payload = this.response(this.receiving);
                payload.completion(this.completion);
                this.deliver(payload);
                this.receiving.wake();
            } else {
                this.execute(() -> this.receiveResult(response, source, encoding));
            }
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private void receiveResult(HttpResponse response, InputStream source, String encoding) {
        try {
            Object value = this.readMessage(source, encoding, false);
            Object extra = this.readMessage(source, encoding, false);
            this.validateEnd(response);
            if (value == null || extra != null) {
                throw new IOException("gRPC method requires exactly one response message");
            }
            this.deliver(this.response(value));
            this.succeed();
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private void validateEnd(HttpResponse response) throws IOException {
        Map<String, List<String>> fields = this.input == null ? response.trailers() : this.input.trailers();
        // 响应已交付时仍写回同一组选项，调用方可在流结束后读取 trailers。
        GrpcMetadata.from(this.receivedResponse).receive(fields, true);
        this.codec.readMetadata(fields, this.receivedResponse);
        this.codec.validateStatus(response, fields);
    }

    private Object readMessage(InputStream body, String encoding, boolean parameter) throws Exception {
        byte[] message = this.codec.readMessage(body, encoding);
        if (message == null) {
            return null;
        }
        Class<?> type = parameter ? this.method.requestType() : this.method.responseType();
        Object value = this.codec.decodeValue(this.request, type, message, parameter);
        if (value == null) {
            throw new IOException("Flow messages cannot be null");
        }
        return value;
    }

    public void respond(ResponsePayload response) {
        this.outgoingResponse = response;
        if (GrpcStatusException.find(response.getFailure()) != null) {
            this.sendFailure(response.getFailure());
            return;
        }
        if (response.getStatus() != ProtocolStatus.OK) {
            this.sendFailure(new MetException(response.getStatus(), response.getOption("message")));
            return;
        }
        Map<String, List<String>> headers = this.codec.responseHeaders(this.request, response);
        HttpResponse message = HttpResponse.newStreaming(200, headers, this.output, Map.of());
        if (!this.beginResponse(message)) {
            return;
        }
        Flow.Publisher<?> source;
        if (this.method.streamingResponse()) {
            source = (Flow.Publisher<?>) response.getReturnData();
        } else {
            source = MetCaller.fromIterable(Collections.singletonList(response.getReturnData()));
        }
        this.sending.start(source);
    }

    public void writeMessage(Object value) throws Exception {
        if (this.terminal.get()) {
            return;
        }
        byte[] bytes = this.codec.encodeValue(this.request, value, this.client);
        String encoding = this.client ? this.codec.requestCompression() : this.codec.responseCompression(this.request);
        this.output.output().write(this.codec.frame(bytes, encoding));
    }

    public void finishOutput() {
        Map<String, List<String>> fields = this.trailers;
        if (this.outgoingResponse != null) {
            fields = this.codec.responseTrailers(this.outgoingResponse);
        }
        this.output.finish(this.client ? Map.of() : fields);
    }

    public void responseWritten(Future<Void> written) {
        written.onFinal(done -> {
            if (done.getCause() != null) {
                this.fail(done.getCause());
            } else {
                // 物理响应写完后再回收请求方向；不会等待应用主动关闭对象。
                this.serial.accept(this::succeed);
            }
        });
    }

    private ResponsePayload response(Object value) {
        ResponsePayload response = this.receivedResponse;
        response.setRequestID(this.request.getRequestID());
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType(this.request.getSerializeType());
        response.setReturnType(this.method.method().getReturnType().getName());
        response.setReturnData(value);
        return response;
    }

    public void receiveFailure(Throwable failure) {
        if (this.receiving != null) {
            this.receiving.fail(failure);
        }
        this.sendFailure(failure);
    }

    void sendFailure(Throwable failure) {
        if (this.client || this.exchange == null || this.exchange.completion().isDone()) {
            this.fail(failure);
            return;
        }
        // 服务异常通过标准 trailers 返回；先发出错误状态，再结束 HTTP 交换。
        if (this.receiving != null) {
            this.receiving.fail(failure);
        }
        HttpResponse error = this.codec.error(failure, this.outgoingResponse);
        this.outgoingResponse = null;
        this.trailers = error.trailers();
        this.sending.stop();
        if (this.beginResponse(this.codec.trailersOnly(error))) {
            return;
        }
        this.finishOutput();
    }

    private boolean beginResponse(HttpResponse response) {
        if (!this.responseStarted.compareAndSet(false, true)) {
            return false;
        }
        // 业务返回后才提交响应头，让过滤器设置的元数据也参与发送。
        this.serial.accept(() -> this.responseWriter.accept(response));
        return true;
    }

    public void cancel() {
        this.fail(new CancellationException("RPC stream cancelled"));
    }

    public void fail(Throwable failure) {
        if (!this.terminal.compareAndSet(false, true)) {
            return;
        }
        // 先释放框架状态和字节缓存，之后才调度用户订阅的取消与失败通知。
        if (this.receiving != null) {
            this.receiving.recordFailure(failure);
        }

        this.completion.failed(failure);
        HttpExchange exchange = this.exchange;
        if (exchange != null) {
            exchange.complete(failure);
        }

        this.output.fail(failure);
        if (this.input != null) {
            this.input.fail(failure);
        }

        this.request.complete(failure);
        this.sending.stop();
        if (this.receiving != null) {
            this.receiving.fail(failure);
        }

        if (this.client) {
            this.deliver(new ThrowPayload(failure));
        }
    }

    private void succeed() {
        if (!this.terminal.compareAndSet(false, true)) {
            return;
        }

        this.completion.completed(null);
        this.exchange.complete(null);
        this.sending.stop();
        this.output.finish(Map.of());
        if (!this.client) {
            if (this.receiving != null) {
                this.receiving.close();
            }
            if (this.input != null) {
                this.input.close();
            }
            this.request.complete(null);
        }
    }

    public boolean execute(Runnable task) {
        try {
            this.workers.execute(task);
            return true;
        } catch (RuntimeException failure) {
            this.fail(failure);
            return false;
        }
    }

    private void deliver(Payload payload) {
        this.serial.accept(() -> this.messages.accept(this.request.getRequestID(), payload));
    }
}

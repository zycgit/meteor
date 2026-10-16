/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.connect.codec.ConnectEnvelope;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;
import net.hasor.meteor.protocol.grpc.codec.GrpcMethodShape;
import net.hasor.meteor.rpc.MetCaller;

/** 一次 Connect 流调用独占其 HTTP 交换、两个有界字节方向和业务 Flow 订阅。 */
final class ConnectStreamingCall {
    private final    RequestPayload                   request;
    private final    ConnectHttpCodec                 codec;
    private final    GrpcMethodShape                  method;
    private final    boolean                          client;
    private final    Executor                         workers;
    private final    Consumer<Runnable>               serial;
    private final    ObjLongConsumer<Payload>         messages;
    private final    HttpBodyStream                   output           = new HttpBodyStream(65535);
    private final    BasicFuture<Void>                completion       = new BasicFuture<>();
    private final    AtomicBoolean                    terminal         = new AtomicBoolean();
    private final    AtomicBoolean                    responseStarted  = new AtomicBoolean();
    private final    Object                           outputLock       = new Object();
    private          boolean                          outputEnded;
    private final    ResponsePayload                  receivedResponse = new ResponsePayload();
    private final    ConnectOutboundMessageSubscriber sending          = new ConnectOutboundMessageSubscriber(this);
    private volatile Consumer<HttpResponse>           responseWriter;
    private volatile ResponsePayload                  outgoingResponse;
    private volatile HttpExchange                     exchange;
    private volatile HttpBodyStream                   input;
    private volatile ConnectInboundMessagePublisher   receiving;

    ConnectStreamingCall(RequestPayload request, ConnectHttpCodec codec, boolean client, Executor workers, //
            Consumer<Runnable> serial, ObjLongConsumer<Payload> messages) {
        this.request = request;
        this.codec = codec;
        this.method = new GrpcMethodShape(codec.target(request));
        this.client = client;
        this.workers = workers;
        this.serial = serial;
        this.messages = messages;
        this.completion.onCancel(done -> this.cancel());
        // RPC 期限和取消立即释放交换，不等待业务订阅方再申请下一条消息。
        this.request.completion().onFailed(done -> this.fail(done.getCause()));
    }

    HttpBodyStream output() {
        return this.output;
    }

    Future<Void> completion() {
        return this.completion;
    }

    void attach(HttpExchange exchange, Consumer<HttpResponse> responseWriter) {
        this.exchange = exchange;
        this.responseWriter = responseWriter;
        exchange.completion().onFailed(done -> this.fail(done.getCause()));
    }

    void startRequest() {
        Object argument = this.request.getParameterValues().get(0);
        Flow.Publisher<?> source;
        if (this.method.streamingRequest()) {
            source = (Flow.Publisher<?>) argument;
        } else {
            source = MetCaller.fromIterable(Collections.singletonList(argument));
        }
        this.sending.start(source);
    }

    void receiveRequest() {
        this.input = this.exchange.request().stream();
        String encoding = this.exchange.request().header("connect-content-encoding");
        if (this.method.streamingRequest()) {
            Callable<Object> next = () -> this.readRequestMessage(this.input, encoding);
            this.receiving = new ConnectInboundMessagePublisher(this, next, () -> {
            });
            this.request.addParameter(Flow.Publisher.class.getName(), this.receiving);
            this.deliver(this.request);
        } else {
            this.execute(() -> {
                try {
                    Object value = this.readRequestMessage(this.input, encoding);
                    Object extra = this.readRequestMessage(this.input, encoding);
                    if (value == null || extra != null) {
                        throw new ConnectStatusException("invalid_argument", //
                                "Connect method requires exactly one request message", null, null);
                    }
                    this.request.addParameter(this.method.requestType().getName(), value);
                    this.deliver(this.request);
                } catch (Throwable failure) {
                    this.receiveFailure(failure);
                }
            });
        }
    }

    private Object readRequestMessage(InputStream body, String encoding) throws Exception {
        try {
            ConnectEnvelope envelope = this.codec.readEnvelope(body, encoding);
            if (envelope == null) {
                return null;
            }
            if (envelope.end()) {
                throw new IOException("Connect request must not contain an end-stream envelope");
            }
            return this.decodeValue(envelope, true);
        } catch (IOException failure) {
            throw new ConnectStatusException("invalid_argument", failure.getMessage(), null, null);
        }
    }

    void receiveResponse(HttpResponse response) {
        HttpBodyStream body = response.stream();
        if (this.terminal.get()) {
            if (body != null) {
                body.fail(new CancellationException("Connect call already completed"));
            }
            return;
        }
        this.input = body;
        this.execute(() -> {
            if (this.terminal.get()) {
                if (body != null) {
                    body.fail(new CancellationException("Connect call already completed"));
                }
                return;
            }
            try {
                this.codec.validateStatus(response);
                this.codec.validateResponseHeaders(this.request, response);
                this.codec.readHeaders(response.headers(), this.receivedResponse);
                InputStream source = this.input;
                if (source == null) {
                    source = new ByteArrayInputStream(response.body());
                }
                InputStream sourceBody = source;
                String encoding = response.header("connect-content-encoding");
                if (this.method.streamingResponse()) {
                    Callable<Object> next = () -> this.readResponseMessage(sourceBody, encoding);
                    this.receiving = new ConnectInboundMessagePublisher(this, next, this::succeed);
                    ResponsePayload payload = this.response(this.receiving);
                    payload.completion(this.completion);
                    this.deliver(payload);
                    this.receiving.wake();
                } else {
                    this.receiveResult(sourceBody, encoding);
                }
            } catch (Throwable failure) {
                this.fail(failure);
            }
        });
    }

    private void receiveResult(InputStream source, String encoding) {
        try {
            Object value = this.readResponseMessage(source, encoding);
            Object extra = this.readResponseMessage(source, encoding);
            if (value == null || extra != null) {
                throw new ConnectStatusException("internal", //
                        "Connect method requires exactly one response message", null, null);
            }
            this.deliver(this.response(value));
            this.succeed();
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private Object readResponseMessage(InputStream body, String encoding) throws Exception {
        ConnectEnvelope envelope = this.codec.readEnvelope(body, encoding);
        if (envelope == null) {
            throw new ConnectStatusException("internal", //
                    "Connect response is missing its end-stream envelope", null, null);
        }
        if (!envelope.end()) {
            return this.decodeValue(envelope, false);
        }
        this.codec.readEnd(envelope.metadata(), this.receivedResponse);
        if (envelope.failure() != null) {
            throw envelope.failure();
        }
        if (this.codec.readEnvelope(body, encoding) != null) {
            throw new ConnectStatusException("internal", //
                    "Connect response contains data after its end-stream envelope", null, null);
        }
        return null;
    }

    private Object decodeValue(ConnectEnvelope envelope, boolean parameter) throws Exception {
        Class<?> type = parameter ? this.method.requestType() : this.method.responseType();
        Object value = this.codec.decodeValue(this.request, type, envelope.message(), parameter);
        if (value == null) {
            throw new IOException("Flow messages cannot be null");
        }
        return value;
    }

    void respond(ResponsePayload response) {
        this.outgoingResponse = response;
        if (response.getFailure() != null) {
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

    void writeMessage(Object value) throws Exception {
        byte[] bytes = this.codec.encodeValue(this.request, value, this.client);
        String encoding = this.client ? this.codec.requestCompression() : this.codec.responseCompression(this.request);
        byte[] frame = this.codec.frame(bytes, encoding);
        synchronized (this.outputLock) {
            if (this.terminal.get() || this.outputEnded) {
                return;
            }
            // 整帧持有同一写边界，失败终态不能插入正在分块写出的数据帧。
            this.output.output().write(frame);
        }
    }

    void finishOutput() throws IOException {
        synchronized (this.outputLock) {
            if (this.outputEnded || this.terminal.get()) {
                return;
            }
            if (!this.client) {
                byte[] end = this.codec.end(this.outgoingResponse);
                this.output.output().write(end);
            }
            this.outputEnded = true;
            this.output.finish(Map.of());
        }
    }

    void responseWritten(Future<Void> written) {
        written.onFinal(done -> {
            if (done.getCause() != null) {
                this.fail(done.getCause());
            } else {
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

    void receiveFailure(Throwable failure) {
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
        this.execute(() -> this.writeFailure(failure));
    }

    private void writeFailure(Throwable failure) {
        if (this.terminal.get() || this.exchange.completion().isDone()) {
            this.fail(failure);
            return;
        }
        if (this.receiving != null) {
            this.receiving.fail(failure);
        }
        this.sending.stop();
        // Connect 在正文的最终 envelope 中携带失败；HTTP trailers 不承担协议状态。
        try {
            Map<String, List<String>> headers = this.codec.responseHeaders(this.request, this.outgoingResponse);
            this.beginResponse(HttpResponse.newStreaming(200, headers, this.output, Map.of()));
            synchronized (this.outputLock) {
                if (!this.outputEnded && !this.terminal.get()) {
                    this.output.output().write(this.codec.end(failure));
                    this.outputEnded = true;
                    this.output.finish(Map.of());
                }
            }
        } catch (Throwable encodingFailure) {
            this.fail(encodingFailure);
        }
    }

    private boolean beginResponse(HttpResponse response) {
        if (!this.responseStarted.compareAndSet(false, true)) {
            return false;
        }
        // 响应提交在连接串行队列中完成，写正文只在工作线程中执行。
        this.serial.accept(() -> this.responseWriter.accept(response));
        return true;
    }

    void cancel() {
        this.fail(new CancellationException("Connect stream cancelled"));
    }

    void fail(Throwable failure) {
        if (!this.terminal.compareAndSet(false, true)) {
            return;
        }
        // 先释放交换和缓冲区，用户取消或错误回调随后在工作线程中执行。
        if (this.receiving != null) {
            this.receiving.recordFailure(failure);
        }
        this.completion.failed(failure);
        HttpExchange current = this.exchange;
        if (current != null) {
            current.complete(failure);
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

    boolean execute(Runnable task) {
        try {
            this.workers.execute(task);
            return true;
        } catch (RuntimeException failure) {
            this.fail(failure);
            return false;
        }
    }

    private void deliver(Payload payload) {
        this.serial.accept(() -> {
            if (!(payload instanceof ThrowPayload) && this.completion.getCause() != null) {
                return;
            }
            this.messages.accept(payload, this.request.getRequestID());
        });
    }
}

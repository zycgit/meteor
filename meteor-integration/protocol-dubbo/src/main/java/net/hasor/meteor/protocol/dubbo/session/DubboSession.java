/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.dubbo.codec.DubboCodec;
import net.hasor.meteor.protocol.dubbo.codec.DubboFrame;
import net.hasor.meteor.protocol.dubbo.codec.DubboFrameDecoder;

/** 一条有序 TCP 通道上的增量收帧、双向请求关联和 Dubbo 交换事件。 */
public final class DubboSession implements ProtocolSession<byte[]> {
    private final ProtocolSessionContext         context;
    private final NetworkChannel<byte[]>         channel;
    private final ObjLongConsumer<Payload>       messages;
    private final DubboCodec                     codec;
    private final DubboFrameDecoder              decoder;
    private final int                            maxPendingRequests;
    private final int                            heartbeatIntervalMillis;
    private final int                            heartbeatTimeoutMillis;
    private final BasicFuture<Void>              ready           = new BasicFuture<>();
    private final Map<Long, DubboPendingRequest> outbound        = new HashMap<>();
    private final Map<Long, RequestPayload>      inbound         = new HashMap<>();
    private       Cancellable                    heartbeatTimer;
    private       long                           lastReadNanos;
    private       long                           lastWriteNanos;
    private       long                           nextHeartbeatId = -1;
    private       Throwable                      closeCause;
    private       boolean                        readOnly;

    public DubboSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> channel, ObjLongConsumer<Payload> messages) {
        this.context = context;
        this.channel = channel;
        this.messages = messages;
        this.codec = new DubboCodec(config, context.routes(), context.metContext());
        int maxFrameSize = config.integer("maxFrameSize", DubboCodec.DEFAULT_MAX_FRAME_SIZE);
        this.decoder = new DubboFrameDecoder(maxFrameSize);
        this.maxPendingRequests = config.integer("maxPendingRequests", 1024);
        this.heartbeatIntervalMillis = Integer.parseInt(config.option("heartbeatInterval", "60000"));
        this.heartbeatTimeoutMillis = config.integer("heartbeatTimeout", 180000);
        if (this.heartbeatIntervalMillis < 0) {
            throw new IllegalArgumentException("heartbeatInterval must not be negative");
        }
        if (this.heartbeatIntervalMillis >= this.heartbeatTimeoutMillis) {
            throw new IllegalArgumentException("heartbeatTimeout must exceed heartbeatInterval");
        }
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        if (this.closeCause != null || this.ready.isDone()) {
            return;
        }

        // Dubbo 建连后即可交换数据，不引入 RSF 的应用握手。
        this.lastReadNanos = System.nanoTime();
        this.lastWriteNanos = this.lastReadNanos;
        this.scheduleHeartbeat();
        this.ready.completed(null);
    }

    @Override
    public void receive(byte[] bytes) throws Exception {
        if (this.closeCause != null) {
            return;
        }

        // 解码器持有未完成帧；同一网络块可以包含任意数量的完整帧。
        this.lastReadNanos = System.nanoTime();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining() && this.closeCause == null) {
            DubboFrame frame = this.decoder.readFrame(input);
            if (frame == null) {
                return;
            }
            if (frame.event()) {
                this.receiveEvent(frame);
            } else if (frame.request()) {
                this.receiveRequest(frame);
            } else {
                this.receiveResponse(frame);
            }
        }
    }

    private void receiveEvent(DubboFrame frame) throws IOException {
        Object value = this.codec.eventValue(frame);
        if (value == null) {
            if (frame.request() && frame.twoWay()) {
                this.writeEvent(this.codec.heartbeat(frame));
            }
        } else if (frame.request() && ObjectUtils.equals("R", value)) {
            this.readOnly = true;
        } else if (frame.request() && ObjectUtils.equals("W", value)) {
            this.readOnly = false;
        }
    }

    private void receiveRequest(DubboFrame frame) throws IOException {
        long id = frame.requestId();
        if (this.inbound.containsKey(id) || this.inbound.size() >= this.maxPendingRequests) {
            IOException failure = new IOException("Duplicate Dubbo request ID or pending limit reached");
            if (frame.twoWay()) {
                this.writeEvent(this.codec.failure(frame, failure));
            }
            return;
        }

        RequestPayload request;
        try {
            request = this.codec.decodeRequest(frame);
        } catch (Exception failure) {
            if (frame.twoWay()) {
                this.writeEvent(this.codec.failure(frame, failure));
            }
            return;
        }

        // 入站和出站分别登记，允许对端同时使用相同请求 ID。
        this.inbound.put(id, request);
        request.completion().onFinal(done -> this.channel.execute(() -> this.inbound.remove(id, request)));
        try {
            this.messages.accept(request, id);
        } catch (Throwable failure) {
            this.inbound.remove(id, request);
            request.complete(failure);
            if (frame.twoWay()) {
                this.writeEvent(this.codec.failure(frame, failure));
            }
        }
    }

    private void receiveResponse(DubboFrame frame) {
        long id = frame.requestId();
        DubboPendingRequest call = this.outbound.remove(id);
        if (call == null) {
            return;
        }

        // RPC 已结束或超时后，迟到响应不再进入上层收口。
        if (call.request.completion().isDone()) {
            return;
        }
        try {
            ResponsePayload response = this.codec.decodeResponse(call.request, frame);
            Throwable failure = response.getFailure();
            call.request.complete(failure);
            if (failure == null) {
                this.messages.accept(response, id);
            } else {
                this.messages.accept(new ThrowPayload(failure), id);
            }
        } catch (Throwable failure) {
            call.request.complete(failure);
            this.messages.accept(new ThrowPayload(failure), id);
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        if (this.closeCause != null || !this.channel.isOpen() || !this.ready.isDone()) {
            return failed(new IOException("Dubbo session is not ready"));
        }
        if (payload instanceof RequestPayload request) {
            return this.sendRequest(request);
        }
        if (payload instanceof ResponsePayload response) {
            return this.sendResponse(response);
        }
        return failed(new UnsupportedOperationException("Dubbo sends requests and responses only"));
    }

    private Future<Void> sendRequest(RequestPayload request) {
        if (request.completion().isDone()) {
            return failed(completionCause(request));
        }
        if (this.readOnly) {
            return failed(new IOException("Dubbo peer is read-only"));
        }

        long id = request.getRequestID();
        if (this.outbound.containsKey(id)) {
            return failed(new IOException("Duplicate Dubbo request ID"));
        }
        if (this.outbound.size() >= this.maxPendingRequests) {
            return failed(new IOException("Dubbo pending request limit reached"));
        }

        DubboPendingRequest call = new DubboPendingRequest(request);
        this.outbound.put(id, call);
        call.sent.onCancel(done -> request.complete(new CancellationException("Dubbo send cancelled")));
        request.completion().onFinal(done -> this.onRequestCompleted(call));
        if (!request.completion().isDone()) {
            try {
                call.writing = this.channel.write(this.codec.encode(request));
                this.lastWriteNanos = System.nanoTime();
                call.writing.onFinal(done -> this.execute(call.sent, () -> this.finishWrite(call, failure(done))));
            } catch (Throwable failure) {
                this.finishWrite(call, failure);
            }
        }
        return call.sent;
    }

    private void onRequestCompleted(DubboPendingRequest call) {
        this.execute(call.sent, () -> {
            if (this.outbound.remove(call.request.getRequestID(), call)) {
                if (call.writing != null) {
                    call.writing.cancel();
                }
                call.sent.failed(completionCause(call.request));
            }
        });
    }

    private void finishWrite(DubboPendingRequest call, Throwable failure) {
        long id = call.request.getRequestID();
        if (failure != null) {
            boolean removed = this.outbound.remove(id, call);
            call.sent.failed(failure);
            if (removed) {
                call.request.complete(failure);
                this.messages.accept(new ThrowPayload(failure), id);
            }
            return;
        }

        // 原生 oneway 没有线上回执，完整写出才完成内部提交确认。
        call.sent.completed(null);
        if (call.request.isMessage() && this.outbound.remove(id, call)) {
            call.request.complete(null);
            ResponsePayload accepted = new ResponsePayload();
            accepted.setRequestID(id);
            accepted.setStatus(ProtocolStatus.Accept);
            this.messages.accept(accepted, id);
        }
    }

    private Future<Void> sendResponse(ResponsePayload response) {
        long id = response.getRequestID();
        RequestPayload request = this.inbound.get(id);
        if (request == null) {
            return failed(new IllegalStateException("Dubbo response has no pending request on this channel"));
        }

        // Dispatcher 的入队通知是内核信号；Dubbo 线上没有临时响应帧。
        if (request.isMessage() || response.getStatus() == ProtocolStatus.Accept) {
            if (request.isMessage()) {
                this.inbound.remove(id, request);
                request.complete(null);
            }
            return new BasicFuture<>((Void) null);
        }

        this.inbound.remove(id, request);
        try {
            Future<Void> writing = this.channel.write(this.codec.encode(request, response));
            this.lastWriteNanos = System.nanoTime();
            writing.onFinal(done -> request.complete(failure(done)));
            return writing;
        } catch (Throwable failure) {
            request.complete(failure);
            return failed(failure);
        }
    }

    private void writeEvent(byte[] frame) {
        try {
            Future<Void> writing = this.channel.write(frame);
            this.lastWriteNanos = System.nanoTime();
            writing.onFinal(done -> {
                Throwable failure = failure(done);
                if (failure != null) {
                    this.channel.execute(() -> this.failConnection(failure));
                }
            });
        } catch (Throwable failure) {
            this.failConnection(failure);
        }
    }

    private void scheduleHeartbeat() {
        if (this.heartbeatIntervalMillis > 0 && this.closeCause == null) {
            Runnable check = () -> this.channel.execute(this::checkHeartbeat);
            this.heartbeatTimer = this.context.schedule(check, this.heartbeatIntervalMillis);
        }
    }

    private void checkHeartbeat() {
        if (this.closeCause != null || this.heartbeatTimer == null) {
            return;
        }

        long now = System.nanoTime();
        long readIdle = TimeUnit.NANOSECONDS.toMillis(now - this.lastReadNanos);
        if (readIdle >= this.heartbeatTimeoutMillis) {
            this.failConnection(new TimeoutException("Dubbo heartbeat timed out"));
            return;
        }

        long writeIdle = TimeUnit.NANOSECONDS.toMillis(now - this.lastWriteNanos);
        if (readIdle >= this.heartbeatIntervalMillis && writeIdle >= this.heartbeatIntervalMillis) {
            try {
                this.writeEvent(this.codec.heartbeat(this.nextHeartbeatId--));
            } catch (IOException failure) {
                this.failConnection(failure);
            }
        }
        this.scheduleHeartbeat();
    }

    private void failConnection(Throwable failure) {
        this.closed(failure);
        this.channel.close();
    }

    @Override
    public void prepareDrain() {
        // 连接器负责停止新提交并排空写入；这里只停止会产生额外写入的心跳。
        if (this.heartbeatTimer != null) {
            this.heartbeatTimer.cancel();
            this.heartbeatTimer = null;
        }
    }

    @Override
    public void closed(Throwable cause) {
        if (this.closeCause != null) {
            return;
        }
        this.closeCause = cause == null ? new IOException("Dubbo connection closed") : cause;
        this.prepareDrain();
        this.decoder.reset();
        this.ready.failed(this.closeCause);

        // 先摘除两侧关联，再触发生命周期通知，防止同步完成回调重入。
        List<DubboPendingRequest> outgoing = new ArrayList<>(this.outbound.values());
        List<RequestPayload> incoming = new ArrayList<>(this.inbound.values());
        this.outbound.clear();
        this.inbound.clear();
        for (DubboPendingRequest call : outgoing) {
            call.request.complete(this.closeCause);
            call.sent.failed(this.closeCause);
            this.messages.accept(new ThrowPayload(this.closeCause), call.request.getRequestID());
        }
        for (RequestPayload request : incoming) {
            request.complete(this.closeCause);
        }
    }

    private void execute(BasicFuture<?> result, Runnable task) {
        try {
            this.channel.execute(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    result.failed(failure);
                }
            });
        } catch (Throwable failure) {
            result.failed(failure);
        }
    }

    private static Throwable failure(Future<?> done) {
        if (done.isCancelled()) {
            return new CancellationException("Dubbo write cancelled");
        }
        return done.getCause();
    }

    private static Throwable completionCause(RequestPayload request) {
        Throwable failure = request.completion().getCause();
        return failure == null ? new CancellationException("RPC already completed") : failure;
    }

    private static Future<Void> failed(Throwable failure) {
        BasicFuture<Void> result = new BasicFuture<>();
        result.failed(failure);
        return result;
    }
}

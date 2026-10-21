/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.codec.ThriftFrameDecoder;
import net.hasor.meteor.protocol.thrift.codec.ThriftMessage;
import net.hasor.meteor.protocol.thrift.codec.ThriftWireCodec;
import org.apache.thrift.protocol.TMessageType;

/** 一条有序 TCP 通道上的 Thrift 增量收帧及双向调用关联。 */
public final class ThriftSession implements ProtocolSession<byte[]> {
    private final NetworkChannel<byte[]>          channel;
    private final ObjLongConsumer<Payload>        messages;
    private final ThriftCodec                     codec;
    private final ThriftFrameDecoder              decoder;
    private final boolean                         framed;
    private final int                             maxPendingRequests;
    private final BasicFuture<Void>               ready    = new BasicFuture<>();
    private final Map<Integer, ThriftPendingCall> outbound = new HashMap<>();
    private final Map<Long, ThriftInboundCall>    inbound  = new HashMap<>();
    private       int                             nextSequenceId;
    private       long                            nextIncomingId;
    private       Throwable                       closeCause;
    private       boolean                         draining;

    public ThriftSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> channel, ObjLongConsumer<Payload> messages) {
        this.channel = channel;
        this.messages = messages;
        this.codec = new ThriftCodec(config, context.routes(), context.metContext());
        int maxMessageSize = config.integer("maxMessageSize", 8 * 1024 * 1024);
        int maxContainerSize = config.integer("maxContainerSize", 100000);
        int maxDepth = config.integer("maxDepth", 64);
        ThriftWireCodec wire = new ThriftWireCodec(maxMessageSize, maxContainerSize, maxDepth);
        this.framed = Boolean.parseBoolean(config.option("framed", "true"));
        this.decoder = new ThriftFrameDecoder(wire, this.framed, maxMessageSize);
        this.maxPendingRequests = config.integer("maxPendingRequests", 1024);
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        if (this.closeCause == null && !this.draining) {
            this.ready.completed(null);
        }
    }

    @Override
    public void receive(byte[] bytes) throws Exception {
        if (this.closeCause != null) {
            return;
        }

        // 同一网络块可以完成多个消息，也可以只补齐部分信封或结构。
        for (ThriftMessage message : this.decoder.feed(bytes)) {
            if (this.closeCause != null) {
                return;
            }
            byte type = message.messageType();
            if (type == TMessageType.CALL || type == TMessageType.ONEWAY) {
                this.receiveRequest(message);
            } else if (type == TMessageType.REPLY || type == TMessageType.EXCEPTION) {
                this.receiveResponse(message);
            }
        }
    }

    private void receiveRequest(ThriftMessage message) {
        if (this.draining) {
            this.writeError(message, new IOException("Thrift session is closing"));
            return;
        }
        if (this.inbound.size() >= this.maxPendingRequests || this.hasIncomingSequence(message)) {
            this.writeError(message, new IOException("Duplicate Thrift sequence ID or pending limit reached"));
            return;
        }

        RequestPayload request;
        try {
            request = this.codec.decodeRequest(message);
        } catch (Throwable failure) {
            this.writeError(message, failure);
            return;
        }

        // 内核身份与 wire seqid 分离，避免旧业务回应命中新复用的线上序号。
        long id = this.nextIncomingId++;
        request.setRequestID(id);
        ThriftInboundCall call = new ThriftInboundCall(request, message);
        this.inbound.put(id, call);
        request.completion().onFinal(done -> this.execute(() -> this.inbound.remove(id, call)));
        try {
            this.messages.accept(request, id);
        } catch (Throwable failure) {
            this.inbound.remove(id, call);
            request.complete(failure);
            this.writeError(message, failure);
        }
    }

    private boolean hasIncomingSequence(ThriftMessage message) {
        if (message.messageType() == TMessageType.ONEWAY) {
            return false;
        }
        for (ThriftInboundCall call : this.inbound.values()) {
            boolean ordinary = call.message().messageType() == TMessageType.CALL;
            if (ordinary && call.message().sequenceId() == message.sequenceId()) {
                return true;
            }
        }
        return false;
    }

    private void receiveResponse(ThriftMessage message) {
        ThriftPendingCall call = this.outbound.get(message.sequenceId());
        if (call == null || !StringUtils.equals(call.request.getTargetMethod(), message.methodName())) {
            return;
        }
        if (!this.outbound.remove(message.sequenceId(), call) || call.request.completion().isDone()) {
            return;
        }

        long id = call.request.getRequestID();
        try {
            ResponsePayload response = this.codec.decodeResponse(call.request, message);
            response.setRequestID(id);
            Throwable failure = response.getFailure();
            call.request.complete(failure);
            this.messages.accept(failure == null ? response : new ThrowPayload(failure), id);
        } catch (Throwable failure) {
            call.request.complete(failure);
            this.messages.accept(new ThrowPayload(failure), id);
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        if (this.closeCause != null || this.draining || !this.channel.isOpen() || !this.ready.isDone()) {
            return failed(new IOException("Thrift session is not ready"));
        }
        if (payload instanceof RequestPayload request) {
            return this.sendRequest(request);
        }
        if (payload instanceof ResponsePayload response) {
            return this.sendResponse(response);
        }
        return failed(new UnsupportedOperationException("Thrift sends requests and responses only"));
    }

    private Future<Void> sendRequest(RequestPayload request) {
        if (request.completion().isDone()) {
            return failed(completionCause(request));
        }
        if (this.outbound.size() >= this.maxPendingRequests) {
            return failed(new IOException("Thrift pending request limit reached"));
        }
        for (ThriftPendingCall pending : this.outbound.values()) {
            if (pending.request.getRequestID() == request.getRequestID()) {
                return failed(new IOException("Duplicate Thrift request ID"));
            }
        }

        // Java int 自然回绕；只跳过仍活跃的线上序号，不截断内核 long ID。
        int sequenceId = this.nextSequenceId++;
        while (this.outbound.containsKey(sequenceId)) {
            sequenceId = this.nextSequenceId++;
        }
        ThriftPendingCall call = new ThriftPendingCall(sequenceId, request);
        this.outbound.put(sequenceId, call);
        call.sent.onCancel(done -> request.complete(new CancellationException("Thrift send cancelled")));
        request.completion().onFinal(done -> this.onRequestCompleted(call));
        if (!request.completion().isDone()) {
            try {
                call.writing = this.write(this.codec.encodeRequest(request, sequenceId));
                call.writing.onFinal(done -> this.execute(() -> this.finishWrite(call, failure(done))));
            } catch (Throwable failure) {
                this.finishWrite(call, failure);
            }
        }
        return call.sent;
    }

    private void onRequestCompleted(ThriftPendingCall call) {
        this.execute(() -> {
            if (this.outbound.remove(call.sequenceId, call)) {
                call.sent.failed(completionCause(call.request));
                if (call.writing != null) {
                    call.writing.cancel();
                }
            }
        });
    }

    private void finishWrite(ThriftPendingCall call, Throwable failure) {
        // RPC completion 可先于已排队的写入回调，不能在之后重复通知失败或 Accept。
        boolean active = this.outbound.get(call.sequenceId) == call;
        if (active && call.request.completion().isDone()) {
            this.outbound.remove(call.sequenceId, call);
            call.sent.failed(completionCause(call.request));
            return;
        }
        if (failure != null) {
            boolean removed = this.outbound.remove(call.sequenceId, call);
            call.sent.failed(failure);
            if (removed) {
                call.request.complete(failure);
                this.messages.accept(new ThrowPayload(failure), call.request.getRequestID());
            }
            return;
        }

        // 原生 ONEWAY 在完整写出后发出内部 Accept，线上没有 REPLY。
        call.sent.completed(null);
        if (call.request.isMessage() && this.outbound.remove(call.sequenceId, call)) {
            call.request.complete(null);
            ResponsePayload accepted = new ResponsePayload();
            accepted.setRequestID(call.request.getRequestID());
            accepted.setStatus(ProtocolStatus.Accept);
            this.messages.accept(accepted, call.request.getRequestID());
        }
    }

    private Future<Void> sendResponse(ResponsePayload response) {
        long id = response.getRequestID();
        ThriftInboundCall call = this.inbound.get(id);
        if (call == null) {
            return failed(new IllegalStateException("Thrift response has no pending request on this channel"));
        }
        if (call.request().isMessage() || response.getStatus() == ProtocolStatus.Accept) {
            if (call.request().isMessage()) {
                this.inbound.remove(id, call);
                call.request().complete(null);
            }
            return new BasicFuture<>((Void) null);
        }

        this.inbound.remove(id, call);
        try {
            Future<Void> writing = this.write(this.codec.encodeResponse(call.request(), response, call.message()));
            writing.onFinal(done -> call.request().complete(failure(done)));
            return writing;
        } catch (Throwable failure) {
            call.request().complete(failure);
            return failed(failure);
        }
    }

    private void writeError(ThriftMessage message, Throwable cause) {
        if (message.messageType() == TMessageType.ONEWAY) {
            return;
        }
        try {
            Future<Void> writing = this.write(this.codec.encodeError(message, cause));
            writing.onFinal(done -> {
                Throwable failure = failure(done);
                if (failure != null) {
                    this.execute(() -> this.closed(failure));
                }
            });
        } catch (Throwable failure) {
            this.closed(failure);
        }
    }

    private Future<Void> write(byte[] bytes) {
        if (!this.framed) {
            return this.channel.write(bytes);
        }
        byte[] framedBytes = ByteBuffer.allocate(4 + bytes.length).putInt(bytes.length).put(bytes).array();
        return this.channel.write(framedBytes);
    }

    @Override
    public void prepareDrain() {
        if (this.draining || this.closeCause != null) {
            return;
        }
        this.draining = true;

        // 尚未交给传输的业务回应不能无限阻止排空；已提交写入由外层继续排空。
        IOException closing = new IOException("Thrift session is closing");
        List<ThriftInboundCall> incoming = new ArrayList<>(this.inbound.values());
        this.inbound.clear();
        for (ThriftInboundCall call : incoming) {
            if (!call.request().completion().isDone()) {
                this.writeError(call.message(), closing);
                call.request().complete(closing);
            }
        }
    }

    @Override
    public void closed(Throwable cause) {
        if (this.closeCause != null) {
            return;
        }
        this.closeCause = cause == null ? new IOException("Thrift connection closed") : cause;
        this.decoder.reset();
        this.ready.failed(this.closeCause);

        // 先摘除关联再通知，防止 completion 回调重入关闭时重复交付。
        List<ThriftPendingCall> outgoing = new ArrayList<>(this.outbound.values());
        List<ThriftInboundCall> incoming = new ArrayList<>(this.inbound.values());
        this.outbound.clear();
        this.inbound.clear();
        for (ThriftPendingCall call : outgoing) {
            boolean completed = call.request.completion().isDone();
            call.sent.failed(completed ? completionCause(call.request) : this.closeCause);
            if (call.writing != null) {
                call.writing.cancel();
            }
            if (!completed) {
                call.request.complete(this.closeCause);
                this.messages.accept(new ThrowPayload(this.closeCause), call.request.getRequestID());
            }
        }
        for (ThriftInboundCall call : incoming) {
            call.request().complete(this.closeCause);
        }
    }

    private void execute(Runnable task) {
        try {
            this.channel.execute(task);
        } catch (Throwable failure) {
            this.closed(failure);
        }
    }

    private static Throwable failure(Future<?> done) {
        if (done.isCancelled()) {
            return new CancellationException("Thrift write cancelled");
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

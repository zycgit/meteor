/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
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
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcMessageMapper;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcAuth;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcMessage;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcRecordDecoder;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcWireCodec;

/** TCP 记录或单个 UDP 对端上的调用关联、有限重传和重复应答缓存。 */
public final class OncRpcSession implements ProtocolSession<byte[]> {
    private final ProtocolSessionContext        context;
    private final NetworkChannel<byte[]>        channel;
    private final ObjLongConsumer<Payload>      messages;
    private final OncRpcMessageMapper           mapper;
    private final OncRpcWireCodec               wire;
    private final OncRpcRecordDecoder           records;
    private final boolean                       datagram;
    private final int                           fragmentSize;
    private final int                           maxPendingRequests;
    private final int                           duplicateCacheSize;
    private final long                          duplicateCacheNanos;
    private final int                           retryAttempts;
    private final long                          retryInitialDelay;
    private final long                          retryMaxDelay;
    private final BasicFuture<Void>             ready    = new BasicFuture<>();
    private final Map<Long, OncRpcPendingCall>  outgoing = new HashMap<>();
    private final Map<Long, OncRpcIncomingCall> incoming = new HashMap<>();
    private final Map<Long, OncRpcIncomingCall> received = new LinkedHashMap<>();
    private       long                          nextXid;
    private       long                          nextIncomingId;
    private       boolean                       draining;
    private       Throwable                     closeCause;

    public OncRpcSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> channel, ObjLongConsumer<Payload> messages, boolean datagram) {
        this.context = context;
        this.channel = channel;
        this.messages = messages;
        this.mapper = new OncRpcMessageMapper(config, context.routes(), context.metContext());
        this.datagram = datagram;
        int maxMessageSize = config.integer("maxMessageSize", 8 * 1024 * 1024);
        this.wire = new OncRpcWireCodec(maxMessageSize);
        this.records = new OncRpcRecordDecoder(maxMessageSize, config.integer("maxRecordFragments", 1024));
        this.fragmentSize = config.integer("recordFragmentSize", 65536);
        this.maxPendingRequests = config.integer("maxPendingRequests", 1024);
        this.duplicateCacheSize = config.integer("duplicateCacheSize", 1024);
        int cacheMillis = config.integer("duplicateCacheTtl", 30000);
        this.duplicateCacheNanos = TimeUnit.MILLISECONDS.toNanos(cacheMillis);
        this.retryAttempts = Integer.parseInt(config.option("retryAttempts", "4"));
        this.retryInitialDelay = config.integer("retryInitialDelay", 200);
        this.retryMaxDelay = config.integer("retryMaxDelay", 2000);
        if (this.retryAttempts < 0 || this.retryMaxDelay < this.retryInitialDelay) {
            throw new IllegalArgumentException("Invalid ONC RPC retry attempts or delay range");
        }
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

        // UDP 的畸形报文只丢弃本包；TCP 的畸形记录则无法可靠继续定界。
        if (this.datagram) {
            OncRpcMessage message;
            try {
                message = this.wire.decode(bytes);
            } catch (IllegalArgumentException malformed) {
                return;
            }
            this.receiveMessage(message, bytes);
            return;
        }
        try {
            for (byte[] record : this.records.feed(bytes)) {
                this.receiveMessage(this.wire.decode(record), record);
            }
        } catch (Exception malformed) {
            this.closed(malformed);
            throw malformed;
        }
    }

    private void receiveMessage(OncRpcMessage message, byte[] bytes) {
        if (this.closeCause != null) {
            return;
        }
        if (message.isCall()) {
            this.receiveRequest(message, bytes);
        } else {
            this.receiveResponse(message);
        }
    }

    private void receiveRequest(OncRpcMessage message, byte[] bytes) {
        if (this.draining) {
            this.writeError(message, new IOException("ONC RPC session is closing"));
            return;
        }

        // 同一会话限定一个 UDP 对端，XID 加完整 CALL 正文共同确定是否为重传。
        this.expireReplies();
        long xid = message.getXid();
        OncRpcIncomingCall previous = this.received.get(xid);
        if (previous != null) {
            if (Arrays.equals(previous.bytes, bytes)) {
                if (previous.reply != null) {
                    this.writeCachedReply(previous);
                }
                return;
            }
            if (previous.reply == null || previous.pendingWrites != 0) {
                this.writeError(message, new IOException("ONC RPC XID is already active"));
                return;
            }
            this.received.remove(xid, previous);
        }
        if (this.incoming.size() >= this.maxPendingRequests || !this.reserveReplySlot()) {
            this.writeError(message, new IOException("ONC RPC pending request limit reached"));
            return;
        }

        OncRpcIncomingCall call = new OncRpcIncomingCall(message, bytes);
        this.received.put(xid, call);
        RequestPayload request;
        try {
            request = this.mapper.decodeRequest(message);
        } catch (Throwable failure) {
            this.completeIncoming(call, this.mapper.replyError(message, failure));
            return;
        }
        if (request == null) {
            OncRpcMessage reply = OncRpcMessage.newSuccess(xid, OncRpcAuth.newNone(), new byte[0]);
            this.completeIncoming(call, reply);
            return;
        }

        // 内核 ID 单独分配，旧业务回应不能写入后续复用相同 XID 的调用。
        long id = this.nextIncomingId++;
        request.setRequestID(id);
        call.request = request;
        this.incoming.put(id, call);
        request.completion().onFinal(done -> this.execute(() -> this.incomingCompleted(id, call)));
        try {
            this.messages.accept(request, id);
        } catch (Throwable failure) {
            this.incoming.remove(id, call);
            this.completeIncoming(call, this.mapper.replyError(message, failure));
            request.complete(failure);
        }
    }

    private void incomingCompleted(long id, OncRpcIncomingCall call) {
        if (!this.incoming.remove(id, call)) {
            return;
        }
        Throwable cause = completionCause(call.request);
        this.completeIncoming(call, this.mapper.replyError(call.message, cause));
    }

    private boolean reserveReplySlot() {
        if (this.received.size() < this.duplicateCacheSize) {
            return true;
        }
        Iterator<OncRpcIncomingCall> iterator = this.received.values().iterator();
        while (iterator.hasNext()) {
            OncRpcIncomingCall call = iterator.next();
            if (call.reply != null && call.pendingWrites == 0) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void expireReplies() {
        long now = System.nanoTime();
        this.received.values().removeIf(call -> {
            boolean completed = call.reply != null && call.pendingWrites == 0;
            return completed && now - call.expiresAt >= 0;
        });
    }

    private void completeIncoming(OncRpcIncomingCall call, OncRpcMessage reply) {
        call.reply = this.wire.encode(reply);
        this.writeCachedReply(call);
    }

    private Future<Void> writeCachedReply(OncRpcIncomingCall call) {
        // 回复已生成但仍在写队列时保留原身份，不能被 TTL 或容量淘汰后再次执行业务。
        call.pendingWrites++;
        Future<Void> writing = this.writeReply(call.reply);
        writing.onFinal(done -> this.execute(() -> {
            call.pendingWrites--;
            if (call.pendingWrites == 0) {
                call.expiresAt = System.nanoTime() + this.duplicateCacheNanos;
            }
        }));
        return writing;
    }

    private void receiveResponse(OncRpcMessage message) {
        OncRpcPendingCall call = this.outgoing.remove(message.getXid());
        if (call == null) {
            return;
        }
        call.cancelRetry();
        if (call.request.completion().isDone()) {
            call.sent.failed(completionCause(call.request));
            return;
        }

        // 匹配的回应证明请求已经写出；调用结果仍由协议结果或原始异常决定。
        call.sent.completed(null);
        long id = call.request.getRequestID();
        try {
            ResponsePayload response = this.mapper.decodeResponse(call.request, message);
            response.setRequestID(id);
            Throwable cause = response.getFailure();
            call.request.complete(cause);
            this.messages.accept(cause == null ? response : new ThrowPayload(cause), id);
        } catch (Throwable failure) {
            call.request.complete(failure);
            this.messages.accept(new ThrowPayload(failure), id);
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        if (this.closeCause != null || this.draining || !this.channel.isOpen() || !this.ready.isDone()) {
            return failed(new IOException("ONC RPC session is not ready"));
        }
        if (payload instanceof RequestPayload request) {
            return this.sendRequest(request);
        }
        if (payload instanceof ResponsePayload response) {
            return this.sendResponse(response);
        }
        return failed(new UnsupportedOperationException("ONC RPC sends requests and responses only"));
    }

    private Future<Void> sendRequest(RequestPayload request) {
        if (request.completion().isDone()) {
            return failed(completionCause(request));
        }
        if (this.outgoing.size() >= this.maxPendingRequests) {
            return failed(new IOException("ONC RPC pending request limit reached"));
        }
        for (OncRpcPendingCall active : this.outgoing.values()) {
            if (active.request.getRequestID() == request.getRequestID()) {
                return failed(new IOException("Duplicate ONC RPC request ID"));
            }
        }

        // XID 是无符号 32 位值，不从内核的 long 请求 ID 截断。
        long xid = this.nextXid;
        do {
            this.nextXid = (this.nextXid + 1) & 0xffff_ffffL;
            if (!this.outgoing.containsKey(xid)) {
                break;
            }
            xid = this.nextXid;
        } while (true);
        byte[] bytes;
        try {
            bytes = this.wire.encode(this.mapper.encodeRequest(request, xid));
        } catch (Throwable failure) {
            return failed(failure);
        }
        OncRpcPendingCall call = new OncRpcPendingCall(xid, request, bytes, this.retryInitialDelay);
        this.outgoing.put(xid, call);
        call.sent.onCancel(done -> request.complete(new CancellationException("ONC RPC send cancelled")));
        request.completion().onFinal(done -> this.execute(() -> this.outgoingCompleted(call)));
        if (!request.completion().isDone()) {
            this.writeRequest(call);
        }
        return call.sent;
    }

    private void outgoingCompleted(OncRpcPendingCall call) {
        if (!this.outgoing.remove(call.xid, call)) {
            return;
        }
        call.cancelRetry();
        call.sent.failed(completionCause(call.request));
        if (call.writing != null) {
            call.writing.cancel();
        }
    }

    private void writeRequest(OncRpcPendingCall call) {
        try {
            call.writing = this.write(call.bytes);
            call.writing.onFinal(done -> this.execute(() -> this.requestWritten(call, failure(done))));
        } catch (Throwable failure) {
            this.failOutgoing(call, failure, true);
        }
    }

    private void requestWritten(OncRpcPendingCall call, Throwable cause) {
        if (this.outgoing.get(call.xid) != call) {
            return;
        }
        if (call.request.completion().isDone()) {
            this.outgoingCompleted(call);
            return;
        }
        if (cause != null) {
            this.failOutgoing(call, cause, true);
            return;
        }
        call.sent.completed(null);
        if (this.datagram && this.retryAttempts > 0) {
            try {
                call.retry = this.context.schedule(() -> this.execute(() -> this.retry(call)), call.retryDelay);
            } catch (Throwable failure) {
                this.failOutgoing(call, failure, true);
            }
        }
    }

    private void retry(OncRpcPendingCall call) {
        call.retry = null;
        if (this.outgoing.get(call.xid) != call || call.request.completion().isDone()) {
            this.outgoingCompleted(call);
            return;
        }
        if (call.retries >= this.retryAttempts) {
            this.failOutgoing(call, new IOException("ONC RPC UDP retry limit reached"), true);
            return;
        }

        // 每次只重发原始 CALL，不重新编码认证时间戳，也不更换 XID。
        call.retries++;
        call.retryDelay = Math.min(this.retryMaxDelay, call.retryDelay * 2);
        this.writeRequest(call);
    }

    private void failOutgoing(OncRpcPendingCall call, Throwable cause, boolean cancelWrite) {
        if (!this.outgoing.remove(call.xid, call)) {
            return;
        }
        call.cancelRetry();
        call.sent.failed(cause);
        if (cancelWrite && call.writing != null) {
            call.writing.cancel();
        }
        if (!call.request.completion().isDone()) {
            call.request.complete(cause);
            this.messages.accept(new ThrowPayload(cause), call.request.getRequestID());
        }
    }

    private Future<Void> sendResponse(ResponsePayload response) {
        long id = response.getRequestID();
        OncRpcIncomingCall call = this.incoming.get(id);
        if (call == null) {
            return failed(new IllegalStateException("ONC RPC response has no pending request on this channel"));
        }
        if (response.getStatus() == ProtocolStatus.Accept) {
            return new BasicFuture<>((Void) null);
        }
        this.incoming.remove(id, call);
        try {
            OncRpcMessage reply = this.mapper.encodeResponse(call.request, response, call.message);
            call.reply = this.wire.encode(reply);
            Future<Void> writing = this.writeCachedReply(call);
            writing.onFinal(done -> call.request.complete(failure(done)));
            return writing;
        } catch (Throwable failure) {
            this.completeIncoming(call, this.mapper.replyError(call.message, failure));
            call.request.complete(failure);
            return failed(failure);
        }
    }

    private void writeError(OncRpcMessage message, Throwable cause) {
        this.writeReply(this.wire.encode(this.mapper.replyError(message, cause)));
    }

    private Future<Void> writeReply(byte[] bytes) {
        try {
            Future<Void> writing = this.write(bytes);
            writing.onFinal(done -> {
                Throwable cause = failure(done);
                if (cause != null && !this.datagram) {
                    this.execute(() -> this.closed(cause));
                }
            });
            return writing;
        } catch (Throwable failure) {
            if (!this.datagram) {
                this.closed(failure);
            }
            return failed(failure);
        }
    }

    private Future<Void> write(byte[] bytes) {
        if (this.datagram) {
            return this.channel.write(bytes);
        }
        return this.channel.write(OncRpcRecordDecoder.encodeRecord(bytes, this.fragmentSize));
    }

    @Override
    public void prepareDrain() {
        if (this.draining || this.closeCause != null) {
            return;
        }
        this.draining = true;
        IOException cause = new IOException("ONC RPC session is closing");
        this.ready.failed(cause);

        // 排空不撤回已提交的传输写入，只释放协议等待与重传任务。
        for (OncRpcPendingCall call : new ArrayList<>(this.outgoing.values())) {
            this.failOutgoing(call, cause, false);
        }
        List<OncRpcIncomingCall> pending = new ArrayList<>(this.incoming.values());
        this.incoming.clear();
        for (OncRpcIncomingCall call : pending) {
            this.completeIncoming(call, this.mapper.replyError(call.message, cause));
            call.request.complete(cause);
        }
    }

    @Override
    public void closed(Throwable cause) {
        if (this.closeCause != null) {
            return;
        }
        this.closeCause = cause == null ? new IOException("ONC RPC connection closed") : cause;
        this.ready.failed(this.closeCause);
        this.records.reset();

        // 先摘除入站身份再通知，避免 completion 重入时写回已经关闭的连接。
        List<OncRpcIncomingCall> pending = new ArrayList<>(this.incoming.values());
        this.incoming.clear();
        this.received.clear();
        for (OncRpcPendingCall call : new ArrayList<>(this.outgoing.values())) {
            this.failOutgoing(call, this.closeCause, true);
        }
        for (OncRpcIncomingCall call : pending) {
            call.request.complete(this.closeCause);
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
        return done.isCancelled() ? new CancellationException("ONC RPC write cancelled") : done.getCause();
    }

    private static Throwable completionCause(RequestPayload request) {
        Throwable cause = request.completion().getCause();
        return cause == null ? new CancellationException("RPC already completed") : cause;
    }

    private static Future<Void> failed(Throwable cause) {
        BasicFuture<Void> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.protocol.rsf.codec.CodecAdapterForV1;
import net.hasor.meteor.connector.protocol.rsf.codec.RsfFrameDecoder;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/** One connection's handshake, request correlation and RSF/1 message lifecycle. */
final class RsfSession implements ProtocolSession<byte[]> {
    private final ProtocolContext           context;
    private final NetworkChannel<byte[]>    connection;
    private final BiConsumer<Long, Payload> messages;
    private final CodecAdapterForV1         codec;
    private final RsfFrameDecoder           decoder;
    private final int                       maxFrameSize;
    private final int                       maxPendingRequests;
    private final int                       handshakeTimeout;
    private final BasicFuture<Void>         ready   = new BasicFuture<>();
    private final Map<Long, PendingRequest> pending = new HashMap<>();
    private final Map<Long, RequestPayload> inbound = new HashMap<>();
    private       Cancellable               handshakeTimer;
    private       boolean                   closed;

    RsfSession(ProtocolConfig config, ProtocolContext context, NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
        this.context = context;
        this.connection = connection;
        this.messages = messages;
        this.codec = new CodecAdapterForV1(context.context());
        this.maxFrameSize = config.integer("maxFrameSize", 16 * 1024 * 1024);
        this.maxPendingRequests = config.integer("maxPendingRequests", 1024);
        this.handshakeTimeout = config.integer("handshakeTimeout", config.integer("connectTimeout", 3000));
        this.decoder = new RsfFrameDecoder(this.maxFrameSize);
    }

    @Override
    public Future<Void> ready() {
        return this.ready;
    }

    @Override
    public void connected() {
        if (this.closed || this.handshakeTimer != null) {
            return;
        }

        this.handshakeTimer = this.context.schedule(() -> this.connection.execute(this::handshakeExpired), this.handshakeTimeout);
        ResponsePayload hello = new ResponsePayload();
        hello.setRequestID(-1);
        hello.setStatus(ProtocolStatus.OK);
        hello.addOption("SERVER_INFO", this.context.bindAddress() == null ? "" : this.context.bindAddress().toHostSchema());

        this.write(hello).onFinal(done -> {
            Throwable error = failure(done);
            if (error != null) {
                this.connection.execute(() -> this.failHandshake(error));
            }
        });
    }

    private void handshakeExpired() {
        if (!this.ready.isDone()) {
            this.failHandshake(new TimeoutException("RSF handshake timed out"));
        }
    }

    private void failHandshake(Throwable error) {
        this.closed(error);
        this.connection.close();
    }

    @Override
    public void receive(byte[] bytes) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining() && !this.closed) {
            byte[] frame = this.decoder.read(input);
            if (frame == null) {
                return;
            }

            Payload message = this.codec.decode(frame);
            if (!this.ready.isDone()) {
                if (!(message instanceof ResponsePayload response) || response.getRequestID() != -1 || response.getOption("SERVER_INFO") == null) {
                    throw new IOException("Expected RSF handshake before application data");
                }
                if (this.handshakeTimer != null) {
                    this.handshakeTimer.cancel();
                }
                this.ready.completed(null);
            } else if (message instanceof RequestPayload request) {
                this.receiveRequest(request);
            } else if (message instanceof ResponsePayload response) {
                this.receiveResponse(response);
            }
        }
    }

    @Override
    public Future<Void> send(Payload payload) {
        if (!(payload instanceof RequestPayload) && !(payload instanceof ResponsePayload)) {
            return failed(new UnsupportedOperationException("RSF/1 only sends requests and responses"));
        }

        if (this.closed || !this.connection.isOpen() || !this.ready.isDone() || this.ready.isCancelled() || this.ready.getCause() != null) {
            return failed(new IOException("RSF session is not ready"));
        }

        if (payload instanceof RequestPayload request) {
            return this.sendRequest(request);
        }

        return this.sendResponse((ResponsePayload) payload);
    }

    private Future<Void> sendRequest(RequestPayload request) {
        BasicFuture<Void> sent = new BasicFuture<>();
        sent.onCancel(ignored -> request.complete(new CancellationException("RSF send cancelled")));
        if (request.completion().isDone()) {
            sent.failed(completionCause(request));
            return sent;
        }

        long id = request.getRequestID();
        if (this.pending.size() >= this.maxPendingRequests || this.pending.containsKey(id)) {
            IOException error = new IOException("Duplicate RSF request ID or pending limit reached");
            sent.failed(error);
            this.messages.accept(id, new ThrowPayload(error));
            return sent;
        }

        PendingRequest entry = new PendingRequest(request, sent);
        this.pending.put(id, entry);

        try {
            request.completion().onFinal(done -> this.execute(sent, () -> {
                if (this.pending.remove(id, entry)) {
                    sent.failed(completionCause(request));
                }
            }));
            // RPC can complete on another thread while the completion callback is being installed.
            if (!request.completion().isDone()) {
                this.write(request).onFinal(done -> this.execute(sent, () -> this.finishRequestWrite(entry, failure(done))));
            }
        } catch (Throwable error) {
            this.finishRequestWrite(entry, error);
        }
        return sent;
    }

    private void finishRequestWrite(PendingRequest entry, Throwable error) {
        if (error == null) {
            entry.sent().completed(null);
        } else {
            long id = entry.request().getRequestID();
            boolean removed = this.pending.remove(id, entry);
            entry.sent().failed(error);
            if (removed) {
                this.messages.accept(id, new ThrowPayload(error));
            }
        }
    }

    private Future<Void> sendResponse(ResponsePayload response) {
        long id = response.getRequestID();
        RequestPayload incoming = this.inbound.get(id);
        if (incoming == null) {
            return failed(new IllegalStateException("RSF response has no pending request on this channel"));
        }

        if (incoming.isMessage() || response.getStatus() != ProtocolStatus.Accept) {
            this.inbound.remove(id);
            incoming.complete(null);
        }

        return this.write(response);
    }

    private Future<Void> write(Payload message) {
        try {
            byte[] bytes = this.codec.encode(message);
            if (bytes.length > this.maxFrameSize) {
                throw new IOException("RSF frame exceeds configured limit");
            }

            return this.connection.write(bytes);
        } catch (Exception error) {
            return failed(error);
        }
    }

    private void receiveRequest(RequestPayload request) {
        long id = request.getRequestID();
        if (id <= 0) {
            return;
        }

        if (this.inbound.size() >= this.maxPendingRequests || this.inbound.containsKey(id)) {
            this.connection.close();
            return;
        }

        this.inbound.put(id, request);
        request.completion().onFinal(done -> this.connection.execute(() -> this.inbound.remove(id, request)));
        this.messages.accept(id, request);
    }

    private void receiveResponse(ResponsePayload response) {
        long id = response.getRequestID();
        PendingRequest entry = this.pending.get(id);
        if (entry == null) {
            return;
        }

        if (entry.request().isMessage() || response.getStatus() != ProtocolStatus.Accept) {
            this.pending.remove(id);
            entry.request().complete(null);
        }

        this.messages.accept(id, response);
    }

    @Override
    public void prepareDrain() {
        // RSF frames have no ordered response slots to release before write draining.
    }

    @Override
    public void closed(Throwable cause) {
        if (this.closed) {
            return;
        }

        this.closed = true;
        Throwable error = cause == null ? new IOException("Connection closed") : cause;
        if (this.handshakeTimer != null) {
            this.handshakeTimer.cancel();
        }

        this.decoder.reset();
        List<PendingRequest> outgoing = new ArrayList<>(this.pending.values());
        List<RequestPayload> incoming = new ArrayList<>(this.inbound.values());
        this.pending.clear();
        this.inbound.clear();
        this.ready.failed(error);

        for (PendingRequest entry : outgoing) {
            entry.request().complete(error);
            entry.sent().failed(error);
            this.messages.accept(entry.request().getRequestID(), new ThrowPayload(error));
        }

        for (RequestPayload request : incoming) {
            request.complete(error);
        }
    }

    private void execute(BasicFuture<Void> result, Runnable task) {
        try {
            this.connection.execute(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    result.failed(error);
                }
            });
        } catch (Throwable error) {
            result.failed(error);
        }
    }

    private static Throwable failure(Future<?> done) {
        return done.isCancelled() ? new CancellationException("RSF write cancelled") : done.getCause();
    }

    private static Throwable completionCause(RequestPayload request) {
        Throwable error = request.completion().getCause();
        return error != null ? error : new CancellationException("RPC already completed");
    }

    private static Future<Void> failed(Throwable error) {
        BasicFuture<Void> result = new BasicFuture<>();
        result.failed(error);
        return result;
    }
}

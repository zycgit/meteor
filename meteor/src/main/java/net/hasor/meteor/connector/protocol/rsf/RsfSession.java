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
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.protocol.rsf.codec.RsfV1Codec;
import net.hasor.meteor.connector.protocol.rsf.codec.RsfV1FrameDecoder;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/** One connection's handshake, request correlation and RSF/1 message lifecycle. */
final class RsfSession implements ProtocolSession<byte[]> {
    private final ProtocolSessionContext          sessionContext;
    private final NetworkChannel<byte[]>          networkChannel;
    private final ObjLongConsumer<Payload>        messageConsumer;
    private final RsfV1Codec                      codec;
    private final RsfV1FrameDecoder               decoder;
    private final int                             maxFrameSizeBytes;
    private final int                             maxPendingRequests;
    private final int                             handshakeTimeoutMillis;
    private final BasicFuture<Void>               ready            = new BasicFuture<>();
    private final Map<Long, OutboundRequestState> outboundRequests = new HashMap<>();
    private final Map<Long, RequestPayload>       inboundRequests  = new HashMap<>();
    private       Cancellable                     handshakeTimer;
    private       boolean                         closed;

    RsfSession(ProtocolConfig config, ProtocolSessionContext sessionContext, //
            NetworkChannel<byte[]> networkChannel, ObjLongConsumer<Payload> messageConsumer) {
        this.sessionContext = sessionContext;
        this.networkChannel = networkChannel;
        this.messageConsumer = messageConsumer;
        this.codec = new RsfV1Codec(sessionContext.metContext());
        this.maxFrameSizeBytes = config.integer("maxFrameSize", 16 * 1024 * 1024);
        this.maxPendingRequests = config.integer("maxPendingRequests", 1024);
        this.handshakeTimeoutMillis = config.integer("handshakeTimeout", config.integer("connectTimeout", 3000));
        this.decoder = new RsfV1FrameDecoder(this.maxFrameSizeBytes);
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

        Runnable expireHandshake = () -> this.networkChannel.execute(this::handshakeExpired);
        this.handshakeTimer = this.sessionContext.schedule(expireHandshake, this.handshakeTimeoutMillis);

        ResponsePayload hello = new ResponsePayload();
        hello.setRequestID(-1);
        hello.setStatus(ProtocolStatus.OK);
        InterAddress address = this.sessionContext.bindAddress();
        String serverInfo = address == null ? "" : address.toHostSchema();
        hello.addOption("SERVER_INFO", serverInfo);

        this.writePayload(hello).onFinal(done -> {
            Throwable error = failure(done);
            if (error != null) {
                this.networkChannel.execute(() -> this.failHandshake(error));
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
        this.networkChannel.close();
    }

    @Override
    public void receive(byte[] bytes) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        while (input.hasRemaining() && !this.closed) {
            byte[] frame = this.decoder.readFrame(input);
            if (frame == null) {
                return;
            }

            Payload message = this.codec.decode(frame);
            if (!this.ready.isDone()) {
                boolean handshake = false;
                if (message instanceof ResponsePayload response) {
                    handshake = response.getRequestID() == -1 && response.getOption("SERVER_INFO") != null;
                }
                if (!handshake) {
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

        if (this.closed || !this.networkChannel.isOpen()) {
            return failed(new IOException("RSF session is not ready"));
        }

        boolean handshakeComplete = this.ready.isDone() && !this.ready.isCancelled() && this.ready.getCause() == null;
        if (!handshakeComplete) {
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
        if (this.outboundRequests.size() >= this.maxPendingRequests || this.outboundRequests.containsKey(id)) {
            IOException error = new IOException("Duplicate RSF request ID or pending limit reached");
            sent.failed(error);
            this.messageConsumer.accept(new ThrowPayload(error), id);
            return sent;
        }

        OutboundRequestState requestState = new OutboundRequestState(request, sent);
        this.outboundRequests.put(id, requestState);

        try {
            request.completion().onFinal(done -> this.executeOnChannel(sent, () -> {
                if (this.outboundRequests.remove(id, requestState)) {
                    sent.failed(completionCause(request));
                }
            }));
            // RPC can complete on another thread while the completion callback is being installed.
            if (!request.completion().isDone()) {
                this.writePayload(request).onFinal(done -> {
                    this.executeOnChannel(sent, () -> this.finishRequestWrite(requestState, failure(done)));
                });
            }
        } catch (Throwable error) {
            this.finishRequestWrite(requestState, error);
        }
        return sent;
    }

    private void finishRequestWrite(OutboundRequestState requestState, Throwable error) {
        if (error == null) {
            requestState.sent().completed(null);
        } else {
            long id = requestState.request().getRequestID();
            boolean removed = this.outboundRequests.remove(id, requestState);
            requestState.sent().failed(error);
            if (removed) {
                this.messageConsumer.accept(new ThrowPayload(error), id);
            }
        }
    }

    private Future<Void> sendResponse(ResponsePayload response) {
        long id = response.getRequestID();
        RequestPayload incoming = this.inboundRequests.get(id);
        if (incoming == null) {
            return failed(new IllegalStateException("RSF response has no pending request on this channel"));
        }

        if (incoming.isMessage() || response.getStatus() != ProtocolStatus.Accept) {
            this.inboundRequests.remove(id);
            incoming.complete(null);
        }

        return this.writePayload(response);
    }

    private Future<Void> writePayload(Payload message) {
        try {
            byte[] bytes = this.codec.encode(message);
            if (bytes.length > this.maxFrameSizeBytes) {
                throw new IOException("RSF frame exceeds configured limit");
            }

            return this.networkChannel.write(bytes);
        } catch (Exception error) {
            return failed(error);
        }
    }

    private void receiveRequest(RequestPayload request) {
        long id = request.getRequestID();
        if (id <= 0) {
            return;
        }

        if (this.inboundRequests.size() >= this.maxPendingRequests || this.inboundRequests.containsKey(id)) {
            this.networkChannel.close();
            return;
        }

        this.inboundRequests.put(id, request);
        request.completion().onFinal(done -> {
            this.networkChannel.execute(() -> this.inboundRequests.remove(id, request));
        });
        this.messageConsumer.accept(request, id);
    }

    private void receiveResponse(ResponsePayload response) {
        long id = response.getRequestID();
        OutboundRequestState requestState = this.outboundRequests.get(id);
        if (requestState == null) {
            return;
        }

        if (requestState.request().isMessage() || response.getStatus() != ProtocolStatus.Accept) {
            this.outboundRequests.remove(id);
            requestState.request().complete(null);
        }

        this.messageConsumer.accept(response, id);
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
        List<OutboundRequestState> outgoingRequests = new ArrayList<>(this.outboundRequests.values());
        List<RequestPayload> incomingRequests = new ArrayList<>(this.inboundRequests.values());
        this.outboundRequests.clear();
        this.inboundRequests.clear();
        this.ready.failed(error);

        for (OutboundRequestState requestState : outgoingRequests) {
            requestState.request().complete(error);
            requestState.sent().failed(error);
            this.messageConsumer.accept(new ThrowPayload(error), requestState.request().getRequestID());
        }

        for (RequestPayload request : incomingRequests) {
            request.complete(error);
        }
    }

    private void executeOnChannel(BasicFuture<Void> result, Runnable task) {
        try {
            this.networkChannel.execute(() -> {
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

    private static final class OutboundRequestState {
        private final RequestPayload    request;
        private final BasicFuture<Void> sent;

        private OutboundRequestState(RequestPayload request, BasicFuture<Void> sent) {
            this.request = request;
            this.sent = sent;
        }

        private RequestPayload request() {
            return this.request;
        }

        private BasicFuture<Void> sent() {
            return this.sent;
        }
    }
}

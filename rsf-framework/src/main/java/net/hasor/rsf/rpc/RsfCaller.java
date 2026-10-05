/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.*;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.connector.ConnectorManager;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates outbound invocations, inbound dispatch, connector messages and RPC shutdown. */
public final class RsfCaller implements AutoCloseable {
    private static final AtomicLong                REQUEST_IDS = new AtomicLong(1);
    private static final Logger                    invLogger   = LoggerFactory.getLogger(RsfConstants.LoggerName_Invoker);
    private final        Map<Long, PendingRequest> rsfResponse = new ConcurrentHashMap<>();
    private final        RsfContext                context;
    private final        Semaphore                 requestPermits;
    private volatile     boolean                   closed;
    private final        RsfDispatcher             dispatcher;
    private final        RsfFilterProvider         filterProvider;
    private final        ConnectorManager          connectors;

    public RsfCaller(ConnectorManager connectors, RsfFilterProvider filters) {
        if (!connectors.isInitialized()) {
            throw new IllegalStateException("Initialize ConnectorManager before creating RPC");
        }

        this.connectors = connectors;
        this.context = connectors.context();
        this.filterProvider = Objects.requireNonNull(filters, "filterProvider");
        this.requestPermits = new Semaphore(this.context.getSettings().getMaximumRequest());
        this.dispatcher = new RsfDispatcher(this.context, filters);

        try {
            this.connectors.subscribe(this::onMessage);
        } catch (RuntimeException | Error failure) {
            this.closeRpc();
            throw failure;
        }
    }

    public RsfContext getContext() {
        return this.context;
    }

    /** 获取正在进行中的调用请求。 */
    public RsfFuture getRequest(long requestID) {
        PendingRequest pending = this.rsfResponse.get(requestID);
        return pending == null ? null : pending.future;
    }

    //
    // for close
    //

    @Override
    public void close() {
        if (!this.stop()) {
            return;
        }

        try {
            this.connectors.close();
        } finally {
            this.closeRpc();
        }
    }

    /** Stop admission while allowing responses to complete already registered calls. */
    private synchronized boolean stop() {
        if (this.closed) {
            return false;
        }

        this.closed = true;
        return true;
    }

    private void closeRpc() {
        try {
            List<PendingRequest> pending;
            synchronized (this) {
                this.closed = true;
                pending = new ArrayList<>(this.rsfResponse.values());
                this.rsfResponse.clear();
                this.requestPermits.release(pending.size());
            }

            for (PendingRequest request : pending) {
                request.cancelTimeout();
                request.future.failed(new IllegalStateException("RSF runtime closed"));
            }
        } finally {
            this.dispatcher.close();
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    //
    // for request
    //

    /** Resolve the target method and address, then create the local RPC request. */
    public RsfRequestObject createRequest(AddressProvider target, RsfBindInfo<?> bindInfo, //
            String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) {
        short flags = 0;
        if (!target.isDistributed()) {
            flags = RsfFlags.P2PFlag.addTag(flags);
        }

        Method targetMethod = RsfRuntimeUtils.getServiceMethod(bindInfo.getBindType(), methodName, parameterTypes);
        InterAddress targetAddress = target.get(bindInfo.getBindID(), methodName, parameterObjects);
        RsfRequestObject request = new RsfRequestObject(REQUEST_IDS.incrementAndGet(), true, this.context, bindInfo, targetMethod, parameterObjects);
        request.setPeerAddress(targetAddress);
        request.setFlags(flags);
        request.setSerializeType(bindInfo.getSerializeType());
        request.setMessage(bindInfo.isMessage());
        request.setTimeout(bindInfo.getClientTimeout());
        return request;
    }

    /** 发送 RSF 调用请求，处理RsfFilter */
    public RsfFuture invoke(RsfRequestObject rsfRequest) {
        RsfBindInfo<?> bindInfo = rsfRequest.getBindInfo();
        String serviceID = bindInfo.getBindID();
        RsfFuture rsfFuture = this.createFuture(rsfRequest);
        if (this.isClosed()) {
            rsfFuture.failed(new IllegalStateException("RSF runtime closed"));
            return rsfFuture;
        }

        invLogger.info("request({}) -> doSendRequest, bindID ={}, callMethod ={}, isMessage ={}.", //
                rsfRequest.getRequestID(), serviceID, rsfRequest.getMethod(), bindInfo.isMessage());

        try {
            rsfRequest.addOptionMap(this.context.getSettings().getRequestOptions());
            RsfResponseObject res = new RsfResponseObject(rsfRequest);
            /*下面这段代码要负责 -> 执行rsfFilter过滤器链，并最终调用sendRequest发送请求。*/
            Supplier<RsfFilter>[] rsfFilterList = this.filterProvider.getFilterProviders(serviceID);
            new RsfFilterHandler(rsfFilterList, (request, response) -> {
                if (!response.isResponse()) {
                    invLogger.info("request({}) -> sendRequest, response wait for remote.", request.getRequestID());
                    this.sendRequest(rsfFuture);//发送请求到远方
                }
            }).doFilter(rsfRequest, res);
            // A filter may respond without continuing the chain, including LocalPref.
            if (res.isResponse()) {
                rsfFuture.completed(res);
            }
        } catch (Throwable e) {
            invLogger.error("request({}) -> errorFailed, sendRequest, doRsfFilterChain. error ={}.", rsfRequest.getRequestID(), e.getMessage(), e);
            this.removeRsfFuture(rsfFuture);
            rsfFuture.failed(e);
        }

        return rsfFuture;
    }

    private void sendRequest(RsfFuture future) throws Exception {
        RsfRequestObject request = (RsfRequestObject) future.getRequest();
        if (future.isDone()) {
            return;
        }

        InterAddress address = request.getTargetAddress();
        if (address == null) {
            future.failed(new RsfException(ProtocolStatus.Forbidden, "Service [" + request.getBindInfo().getBindID() + "] Address Unavailable."));
            return;
        }

        RequestPayload payload = this.prepareRequest(future, () -> {
            RequestPayload mapped = RsfPayloadBuilder.buildRequestPayload(request);
            mapped.setFlags(request.getFlags());
            return mapped;
        });

        if (payload == null) {
            return;
        }

        Future<?> sending = this.sendRequest(address, payload);
        sending.onFailed(sent -> this.putResponse(payload.getRequestID(), sent.getCause()));
        sending.onCancel(sent -> this.putResponse(payload.getRequestID(), new CancellationException("Request send cancelled")));
    }

    /** Reserve capacity before mapping; a registered entry owns its permit and timer. */
    private RequestPayload prepareRequest(RsfFuture future, Callable<RequestPayload> mapping) throws Exception {
        if (this.isClosed()) {
            throw new IllegalStateException("RSF runtime closed");
        }
        if (!this.acquireRequestPermit(future)) {
            return null;
        }

        boolean registered = false;
        try {
            RequestPayload payload = mapping.call();
            this.registerRequest(future);
            registered = true;

            this.startRequest(future);
            if (future.isDone()) {
                return null;
            }

            return payload;
        } finally {
            if (!registered) {
                this.requestPermits.release();
            }
        }
    }

    private synchronized void registerRequest(RsfFuture future) {
        if (this.isClosed()) {
            throw new IllegalStateException("RSF runtime closed");
        }
        if (future.isDone()) {
            throw new CancellationException("RPC ended before registration");
        }

        if (this.rsfResponse.putIfAbsent(future.getRequest().getRequestID(), new PendingRequest(future, this.timeout(future))) != null) {
            throw new IllegalStateException("Request is already pending");
        }
    }

    private void startRequest(RsfFuture future) {
        long requestId = future.getRequest().getRequestID();
        Cancellable timer = this.connectors.schedule(() -> {
            this.putResponse(requestId, new RsfTimeoutException("request(" + requestId + ") -> timeout for client."));
        }, this.timeout(future));

        synchronized (this) {
            PendingRequest pending = this.rsfResponse.get(requestId);
            if (pending != null) {
                pending.timer = timer;
                return;
            }
        }
        timer.cancel();
    }

    private boolean acquireRequestPermit(RsfFuture future) throws InterruptedException {
        if (this.requestPermits.tryAcquire()) {
            return true;
        }

        SendLimitPolicy policy = this.context.getSettings().getSendLimitPolicy();
        if (policy == SendLimitPolicy.WaitSecond) {
            try {
                if (this.requestPermits.tryAcquire(1, TimeUnit.SECONDS)) {
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }

        future.failed(new RsfException(ProtocolStatus.SendLimitPolicy, "request(" + future.getRequest().getRequestID() + ") -> maximum number of requests, apply SendPolicy = " + policy));
        return false;
    }

    private Future<?> sendRequest(InterAddress address, RequestPayload request) {
        BasicFuture<RsfChannel> sent = new BasicFuture<>();

        if (this.isClosed()) {
            sent.failed(new IllegalStateException("RPC is closed"));
        } else {
            this.connectors.connect(address).onFinal(connected -> {
                this.finishConnect(request, sent, connected);
            });
        }
        return sent;
    }

    private void finishConnect(RequestPayload request, BasicFuture<RsfChannel> sent, Future<RsfChannel> connected) {
        if (connected.isCancelled()) {
            sent.cancel();
            return;
        }

        if (connected.getCause() != null) {
            sent.failed(connected.getCause());
            return;
        }

        try {
            RsfChannel channel = connected.getResult();
            if (!this.attachChannel(request.getRequestID(), channel)) {
                sent.failed(new CancellationException("RPC ended while connecting"));
                return;
            }

            int remaining = this.remainingMillis(request.getRequestID());
            if (remaining <= 0) {
                sent.failed(new RsfTimeoutException("RPC expired while connecting"));
                return;
            }

            request.setClientTimeout(remaining);
            channel.sendData(request).onFinal(written -> {
                if (written.isCancelled()) {
                    sent.cancel();
                } else if (written.getCause() != null) {
                    sent.failed(written.getCause());
                } else {
                    sent.completed(written.getResult());
                }
            });
        } catch (Exception failure) {
            sent.failed(failure);
        }
    }

    /** Attach before sendData, including transports that respond synchronously. */
    private synchronized boolean attachChannel(long requestId, RsfChannel channel) {
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending == null || pending.future.isDone() || this.closed) {
            return false;
        }

        pending.channel = channel;
        return true;
    }

    private int remainingMillis(long requestId) {
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending == null || pending.future.isDone()) {
            return 0;
        }

        long remaining = pending.deadline - System.nanoTime();
        return remaining <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (remaining + 999999L) / 1000000L);
    }

    private RsfFuture createFuture(RsfRequest rsfRequest) {
        return new RsfFuture(rsfRequest) {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                try {
                    return super.cancel(mayInterruptIfRunning);
                } finally {
                    // Always remove this invocation even if a cancellation listener throws.
                    if (this.isCancelled()) {
                        RsfCaller.this.removeRsfFuture(this);
                    }
                }
            }
        };
    }

    private void removeRsfFuture(RsfFuture future) {
        long requestId = future.getRequest().getRequestID();
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending != null && pending.future == future) {
            this.removeRsfFuture(pending);
        }
    }

    private int timeout(RsfFuture future) {
        int timeout = future.getRequest().getTimeout();
        return timeout > 0 ? timeout : this.context.getSettings().getDefaultTimeout();
    }

    //
    // for subscription
    //

    /** Receive decoded messages only through the connector subscription. */
    private void onMessage(RsfChannel channel, long requestId, Payload payload) {
        // Responses and failures remain deliverable while connector writes drain.
        if (payload.getType() == Payload.Type.REQUEST && this.isClosed()) {
            return;
        }

        switch (payload.getType()) {
            case REQUEST:
                this.dispatcher.onRequest(channel, requestId, (RequestPayload) payload);
                break;
            case RESPONSE:
                ResponsePayload response = (ResponsePayload) payload;
                this.onResponse(channel, requestId, response);
                break;
            case THROW:
                Throwable failure = ((ThrowPayload) payload).getThrowable();
                this.onFailure(channel, requestId, failure);
                break;
        }
    }

    private void onResponse(RsfChannel channel, long requestId, ResponsePayload response) {
        if (requestId != response.getRequestID()) {
            return;
        }

        RsfFuture future = this.takeResponse(channel, requestId, response.getStatus() == ProtocolStatus.Accept);
        if (future != null) {
            this.putResponse(future, response);
        }
    }

    private void onFailure(RsfChannel channel, long requestId, Throwable failure) {
        RsfFuture future = this.takeResponse(channel, requestId, false);
        if (future != null) {
            invLogger.error("response({}) -> errorFailed, {}", requestId, failure.getMessage(), failure);
            future.failed(failure);
        }
    }

    /** Match the source, then remove that exact entry; ordinary ACKs keep the request pending. */
    private RsfFuture takeResponse(RsfChannel channel, long requestId, boolean acknowledgement) {
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending == null || channel == null || pending.channel != channel) {
            return null;
        }
        if (acknowledgement && !pending.future.getRequest().isMessage()) {
            return null;
        }
        return this.removeRsfFuture(pending);
    }

    /** Complete a matched request that has already released its permit and timer. */
    private void putResponse(RsfFuture future, ResponsePayload info) {
        RsfRequest request = future.getRequest();
        short successStatus = request.isMessage() ? ProtocolStatus.Accept : ProtocolStatus.OK;
        if (info.getStatus() != successStatus) {
            String errorInfo = request.isMessage() ? "errorCode = " + info.getStatus() + ", errorMessage=" + info.getOption("message") : "status.";
            invLogger.error("response({}) -> statusFailed, bindID ={}, status ={}.", request.getRequestID(), request.getBindInfo().getBindID(), info.getStatus());
            future.failed(new RsfException(info.getStatus(), errorInfo));
            return;
        }

        Object result = info.getReturnData();
        if (request.isMessage()) {
            Class<?> returnType = request.getMethod().getReturnType();
            result = returnType.isAssignableFrom(RsfResult.class) ? new RsfResultDO(request.getRequestID(), true) : null;
        }
        RsfResponseObject response = new RsfResponseObject(request);
        response.addOptionMap(info);
        response.sendData(result);
        invLogger.info("response({}) -> successful, bindID ={}.", request.getRequestID(), request.getBindInfo().getBindID());
        future.completed(response);
    }

    /** 响应挂起的Request请求。 */
    private void putResponse(long requestID, Throwable e) {
        RsfFuture rsfFuture = this.removeRsfFuture(this.rsfResponse.get(requestID));
        if (rsfFuture != null) {
            invLogger.error("response({}) -> errorFailed, {}", requestID, e.getMessage(), e);
            rsfFuture.failed(e);
        }
    }

    private RsfFuture removeRsfFuture(PendingRequest pending) {
        if (pending == null) {
            return null;
        }

        synchronized (this) {
            // Coordinate with closeRpc so only one terminal path releases this request's permit.
            if (!this.rsfResponse.remove(pending.future.getRequest().getRequestID(), pending)) {
                return null;
            }
            this.requestPermits.release();
        }
        pending.cancelTimeout();
        return pending.future;
    }

    //

    private static final class PendingRequest {
        private final    RsfFuture   future;
        private final    long        deadline;
        private volatile RsfChannel  channel;
        private          Cancellable timer;

        private PendingRequest(RsfFuture future, int timeout) {
            this.future = future;
            this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }

        private void cancelTimeout() {
            if (this.timer != null) {
                this.timer.cancel();
            }
        }
    }
}

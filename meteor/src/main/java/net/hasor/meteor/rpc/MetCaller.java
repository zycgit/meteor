/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/** Coordinates outbound invocations, inbound dispatch, connector messages and RPC shutdown. */
public final class MetCaller implements AutoCloseable {
    private static final Logger                    invLogger   = LoggerFactory.getLogger(MetCaller.class);
    private static final AtomicLong                REQUEST_IDS = new AtomicLong(1);
    private final        Map<Long, PendingRequest> rsfResponse = new ConcurrentHashMap<>();
    private final        MetContext                context;
    private final        Semaphore                 requestPermits;
    private volatile     boolean                   closed;
    private final        MetDispatcher             dispatcher;
    private final        MetFilterProvider         filterProvider;
    private final        ConnectorManager          connectors;

    public MetCaller(ConnectorManager connectors, MetFilterProvider filters) {
        if (!connectors.isInitialized()) {
            throw new IllegalStateException("Initialize ConnectorManager before creating RPC");
        }

        this.connectors = connectors;
        this.context = connectors.context();
        this.filterProvider = Objects.requireNonNull(filters, "filterProvider");
        this.requestPermits = new Semaphore(this.context.getSettings().getMaximumRequest());
        this.dispatcher = new MetDispatcher(this.context, filters);

        try {
            this.connectors.subscribe(this::onMessage);
        } catch (RuntimeException | Error failure) {
            this.closeRpc();
            throw failure;
        }
    }

    MetContext getContext() {
        return this.context;
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
                request.payload.complete(new IllegalStateException("RSF runtime closed"));
                request.future.failed(new IllegalStateException("RSF runtime closed"));
            }
        } finally {
            this.dispatcher.close();
        }
    }

    //
    // for request
    //

    /** Resolve the target method and address, then create the local RPC request. */
    public MetRequestObject createRequest(AddressProvider target, MetBindInfo<?> bindInfo, //
            String methodName, Class<?>[] parameterTypes, Object[] parameterObjects) {
        short flags = 0;
        if (!target.isDistributed()) {
            flags = MetFlags.P2PFlag.addTag(flags);
        }

        Method targetMethod = MetRuntimeUtils.getServiceMethod(bindInfo.getBindType(), methodName, parameterTypes);
        RouteContext routing = new RouteContext(bindInfo.getBindID(), methodName, parameterObjects);
        routing.getEnv().put("applicationName", this.context.getApplicationName());
        routing.getEnv().put("unitName", this.context.getSettings().getUnitName());
        routing.getService().put("name", bindInfo.getBindName());
        routing.getService().put("group", bindInfo.getBindGroup());
        routing.getService().put("version", bindInfo.getBindVersion());
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : bindInfo.getMetaKeys()) {
            metadata.put(key, bindInfo.getMetaData(key));
        }

        routing.getService().put("meta", metadata);
        routing.getMethod().put("signature", targetMethod.toGenericString());
        InterAddress targetAddress = target.get(routing);
        MetRequestObject request = new MetRequestObject(REQUEST_IDS.incrementAndGet(), true, this.context, bindInfo, targetMethod, parameterObjects);
        request.setPeerAddress(targetAddress);
        request.setFlags(flags);
        request.setSerializeType(bindInfo.getSerializeType());
        request.setMessage(bindInfo.isMessage());
        request.setTimeout(this.timeout(bindInfo.getClientTimeout()));

        // 创建时复制默认选项，后续由调用方覆盖或删除。
        request.addOptionMap(this.context.getSettings().getRequestOptions());
        return request;
    }

    /** 发送 RSF 调用请求，处理RsfFilter */
    public MetFuture invoke(MetRequestObject rsfRequest) {
        MetBindInfo<?> bindInfo = rsfRequest.getBindInfo();
        String serviceID = bindInfo.getBindID();
        MetFuture rsfFuture = this.createFuture(rsfRequest);
        if (this.closed) {
            rsfFuture.failed(new IllegalStateException("RSF runtime closed"));
            return rsfFuture;
        }

        invLogger.info("request(" + rsfRequest.getRequestID() + ") -> doSendRequest, bindID =" + serviceID + ", callMethod =" + rsfRequest.getMethod() + ", isMessage =" + bindInfo.isMessage() + ".");

        try {
            // 执行调用过滤器；未被本地处理的请求才进入网络发送。
            MetResponseObject res = new MetResponseObject(rsfRequest);
            /*下面这段代码要负责 -> 执行rsfFilter过滤器链，并最终调用sendRequest发送请求。*/
            Supplier<MetFilter>[] rsfFilterList = this.filterProvider.getFilterProviders(serviceID);
            MetFilterHandler filters = new MetFilterHandler(rsfFilterList, (request, response) -> {
                if (!response.isResponse()) {
                    invLogger.info("request(" + request.getRequestID() + ") -> sendRequest, response wait for remote.");
                    this.sendRequest(rsfFuture);//发送请求到远方
                }
            });
            filters.doFilter(rsfRequest, res);

            // A filter may respond without continuing the chain, including LocalPref.
            if (res.isResponse()) {
                if (res.getStatus() == ProtocolStatus.OK || res.getStatus() == ProtocolStatus.Accept) {
                    rsfFuture.completed(res);
                } else {
                    rsfFuture.failed(new MetException(res.getStatus(), res.getOption("message")));
                }
            }
        } catch (Throwable e) {
            invLogger.error("request(" + rsfRequest.getRequestID() + ") -> errorFailed, sendRequest, doRsfFilterChain. error =" + e.getMessage() + ".", e);
            this.removeRsfFuture(rsfFuture, e);
            rsfFuture.failed(e);
        }

        return rsfFuture;
    }

    private void sendRequest(MetFuture future) throws Exception {
        MetRequestObject request = (MetRequestObject) future.getRequest();
        if (future.isDone()) {
            return;
        }

        InterAddress address = request.getTargetAddress();
        if (address == null) {
            String serviceId = request.getBindInfo().getBindID();
            String message = "Service [" + serviceId + "] Address Unavailable.";
            future.failed(new MetException(ProtocolStatus.Forbidden, message));
            return;
        }

        RequestPayload payload = this.prepareRequest(future, () -> {
            RequestPayload mapped = MetPayloadBuilder.buildRequestPayload(request);
            mapped.setFlags(request.getFlags());
            return mapped;
        });

        if (payload == null) {
            return;
        }

        Future<?> sending = this.sendRequest(address, payload);
        sending.onFailed(sent -> this.putResponse(payload.getRequestID(), sent.getCause()));
        sending.onCancel(sent -> {
            long requestId = payload.getRequestID();
            CancellationException failure = new CancellationException("Request send cancelled");
            this.putResponse(requestId, failure);
        });
    }

    /** Reserve capacity before mapping; a registered entry owns its permit and timer. */
    private RequestPayload prepareRequest(MetFuture future, Callable<RequestPayload> mapping) throws Exception {
        if (this.closed) {
            throw new IllegalStateException("RSF runtime closed");
        }
        if (!this.acquireRequestPermit(future)) {
            return null;
        }

        boolean registered = false;
        try {
            RequestPayload payload = mapping.call();
            this.registerRequest(future, payload);
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

    private synchronized void registerRequest(MetFuture future, RequestPayload payload) {
        if (this.closed) {
            throw new IllegalStateException("RSF runtime closed");
        }
        if (future.isDone()) {
            throw new CancellationException("RPC ended before registration");
        }

        long requestId = future.getRequest().getRequestID();
        PendingRequest pending = new PendingRequest(future, payload, this.timeout(future.getRequest().getTimeout()));
        if (this.rsfResponse.putIfAbsent(requestId, pending) != null) {
            throw new IllegalStateException("Request is already pending");
        }
    }

    private void startRequest(MetFuture future) {
        long requestId = future.getRequest().getRequestID();
        Cancellable timer = this.connectors.schedule(() -> {
            this.putResponse(requestId, new MetTimeoutException("request(" + requestId + ") -> timeout for client."));
        }, this.timeout(future.getRequest().getTimeout()));

        synchronized (this) {
            PendingRequest pending = this.rsfResponse.get(requestId);
            if (pending != null) {
                pending.timer = timer;
                return;
            }
        }
        timer.cancel();
    }

    private boolean acquireRequestPermit(MetFuture future) throws InterruptedException {
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

        long requestId = future.getRequest().getRequestID();
        String message = "request(" + requestId + ") -> maximum number of requests, apply SendPolicy = " + policy;
        future.failed(new MetException(ProtocolStatus.SendLimitPolicy, message));
        return false;
    }

    private Future<?> sendRequest(InterAddress address, RequestPayload request) {
        BasicFuture<MetChannel> sent = new BasicFuture<>();

        if (this.closed) {
            sent.failed(new IllegalStateException("RPC is closed"));
        } else {
            this.connectors.connect(address).onFinal(connected -> {
                this.finishConnect(request, sent, connected);
            });
        }
        return sent;
    }

    private void finishConnect(RequestPayload request, BasicFuture<MetChannel> sent, Future<MetChannel> connected) {
        if (connected.isCancelled()) {
            sent.cancel();
            return;
        }

        if (connected.getCause() != null) {
            sent.failed(connected.getCause());
            return;
        }

        try {
            MetChannel channel = connected.getResult();
            if (!this.attachChannel(request.getRequestID(), channel)) {
                sent.failed(new CancellationException("RPC ended while connecting"));
                return;
            }

            int remaining = this.remainingMillis(request.getRequestID());
            if (remaining <= 0) {
                sent.failed(new MetTimeoutException("RPC expired while connecting"));
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
    private synchronized boolean attachChannel(long requestId, MetChannel channel) {
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

    private MetFuture createFuture(MetRequest rsfRequest) {
        return new MetFuture(rsfRequest) {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                try {
                    return super.cancel(mayInterruptIfRunning);
                } finally {
                    // Always remove this invocation even if a cancellation listener throws.
                    if (this.isCancelled()) {
                        MetCaller.this.removeRsfFuture(this, new CancellationException("RPC cancelled"));
                    }
                }
            }
        };
    }

    private void removeRsfFuture(MetFuture future, Throwable cause) {
        long requestId = future.getRequest().getRequestID();
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending != null && pending.future == future) {
            this.removeRsfFuture(pending, cause);
        }
    }

    private int timeout(int timeout) {
        if (timeout <= 0) {
            timeout = this.context.getSettings().getRequestTimeout();
        }
        return timeout > 0 ? timeout : this.context.getSettings().getDefaultTimeout();
    }

    //
    // for subscription
    //

    /** Receive decoded messages only through the connector subscription. */
    private void onMessage(MetChannel channel, long requestId, Payload payload) {
        // Responses and failures remain deliverable while connector writes drain.
        if (payload.getType() == Payload.Type.REQUEST && this.closed) {
            ((RequestPayload) payload).complete(new IllegalStateException("RPC is closed"));
            return;
        }

        switch (payload.getType()) {
            case REQUEST:
                this.onRequest(channel, requestId, (RequestPayload) payload);
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

    private void onRequest(MetChannel channel, long requestId, RequestPayload request) {
        try {
            int timeout = request.getClientTimeout();
            if (timeout <= 0) {
                timeout = this.context.getSettings().getDefaultTimeout();
            }

            Cancellable timer = this.connectors.schedule(() -> {
                request.complete(new MetTimeoutException("request(" + requestId + ") -> timeout for server."));
            }, timeout);

            request.completion().onFinal(done -> timer.cancel());
            if (!request.completion().isDone()) {
                this.dispatcher.onRequest(channel, requestId, request);
            }
        } catch (Throwable failure) {
            request.complete(failure);
        }
    }

    private void onResponse(MetChannel channel, long requestId, ResponsePayload response) {
        if (requestId != response.getRequestID()) {
            return;
        }

        if (response.completion() != null && response.getStatus() == ProtocolStatus.OK) {
            PendingRequest pending = this.rsfResponse.get(requestId);
            if (pending == null || pending.channel != channel || pending.future.isDone()) {
                response.completion().cancel();
                return;
            }

            // 交付响应 Publisher，保留截止时间与配额，直到消息流真正结束。
            this.putResponse(pending.future, response);
            response.completion().onFinal(done -> {
                Throwable failure = done.getCause();
                if (done.isCancelled()) {
                    failure = new CancellationException("RPC stream cancelled");
                }
                this.removeRsfFuture(pending, failure);
            });
            return;
        }

        MetFuture future = this.takeResponse(channel, requestId, response.getStatus() == ProtocolStatus.Accept, null);
        if (future != null) {
            this.putResponse(future, response);
        }
    }

    private void onFailure(MetChannel channel, long requestId, Throwable failure) {
        MetFuture future = this.takeResponse(channel, requestId, false, failure);
        if (future != null) {
            invLogger.error("response(" + requestId + ") -> errorFailed, " + failure.getMessage(), failure);
            future.failed(failure);
        }
    }

    /** Match the source, then remove that exact entry; ordinary ACKs keep the request pending. */
    private MetFuture takeResponse(MetChannel channel, long requestId, boolean acknowledgement, Throwable cause) {
        PendingRequest pending = this.rsfResponse.get(requestId);
        if (pending == null || channel == null || pending.channel != channel) {
            return null;
        }
        if (acknowledgement && !pending.future.getRequest().isMessage()) {
            return null;
        }
        return this.removeRsfFuture(pending, cause);
    }

    /** Complete a matched request that has already released its permit and timer. */
    private void putResponse(MetFuture future, ResponsePayload info) {
        MetRequest request = future.getRequest();
        short successStatus = request.isMessage() ? ProtocolStatus.Accept : ProtocolStatus.OK;
        if (info.getStatus() != successStatus) {
            String errorInfo = info.getOption("message");
            if (StringUtils.isBlank(errorInfo)) {
                errorInfo = "RPC failed, status = " + info.getStatus();
            }

            invLogger.error("response(" + request.getRequestID() + ") -> statusFailed, bindID =" + request.getBindInfo().getBindID() + ", status =" + info.getStatus() + ".");
            future.failed(new MetException(info.getStatus(), errorInfo));
            return;
        }

        Object result = info.getReturnData();
        if (request.isMessage()) {
            Class<?> returnType = request.getMethod().getReturnType();
            result = null;
            if (returnType.isAssignableFrom(MetResult.class)) {
                result = new MetResultDO(request.getRequestID(), true);
            }
        }
        MetResponseObject response = new MetResponseObject(request);
        response.setOptions(info);
        response.sendData(result);
        invLogger.info("response(" + request.getRequestID() + ") -> successful, bindID =" + request.getBindInfo().getBindID() + ".");
        future.completed(response);
    }

    /** 响应挂起的Request请求。 */
    private void putResponse(long requestID, Throwable e) {
        MetFuture rsfFuture = this.removeRsfFuture(this.rsfResponse.get(requestID), e);
        if (rsfFuture != null) {
            invLogger.error("response(" + requestID + ") -> errorFailed, " + e.getMessage(), e);
            rsfFuture.failed(e);
        }
    }

    private MetFuture removeRsfFuture(PendingRequest pending, Throwable cause) {
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
        pending.payload.complete(cause);
        return pending.future;
    }

    //

    private static final class PendingRequest {
        private final    MetFuture      future;
        private final    RequestPayload payload;
        private final    long           deadline;
        private volatile MetChannel     channel;
        private          Cancellable    timer;

        private PendingRequest(MetFuture future, RequestPayload payload, int timeout) {
            this.future = future;
            this.payload = payload;
            this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }

        private void cancelTimeout() {
            if (this.timer != null) {
                this.timer.cancel();
            }
        }
    }

    //
    // 流数据源与消费
    //

    /** 首次需求到达时创建迭代器，每次订阅独立创建；可关闭的迭代器在结束、失败或取消时关闭。 */
    public static <T> Flow.Publisher<T> fromIterator(Supplier<? extends Iterator<? extends T>> source) {
        return MetStreamUtils.fromIterator(source);
    }

    /** 将可迭代数据源适配成按需求交付的消息流。 */
    public static <T> Flow.Publisher<T> fromIterable(Iterable<? extends T> source) {
        return MetStreamUtils.fromIterable(source);
    }

    /** 同步处理完一条后再申请下一条；取消返回的 Future 会取消订阅。 */
    public static <T> Future<Void> consume(Flow.Publisher<T> source, Consumer<? super T> consumer) {
        return MetStreamUtils.consume(source, consumer);
    }
}

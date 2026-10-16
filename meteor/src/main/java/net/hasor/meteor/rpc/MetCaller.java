/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.InvocationTargetException;
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
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;

/** Coordinates outbound invocations, inbound dispatch and connector messages. */
public final class MetCaller implements AutoCloseable {
    private static final Logger                    invLogger   = LoggerFactory.getLogger(MetCaller.class);
    private static final AtomicLong                REQUEST_IDS = new AtomicLong(1);
    private final        Map<Long, PendingRequest> rsfResponse = new ConcurrentHashMap<>();
    private final        MetContext                context;
    private final        Semaphore                 requestPermits;
    private volatile     boolean                   closed;
    private              boolean                   resourcesClosed;
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
            this.close();
            throw failure;
        }
    }

    public MetContext getContext() {
        return this.context;
    }

    //
    // for close
    //

    @Override
    public void close() {
        synchronized (this) {
            if (this.resourcesClosed) {
                return;
            }
            this.closed = true;
            this.resourcesClosed = true;
        }
        this.closeOwnedResources();
    }

    /** Stop admission while allowing responses to complete already registered calls. */
    public synchronized void stopAdmission() {
        this.closed = true;
    }

    private void closeOwnedResources() {
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

        MetRequestObject request = new MetRequestObject(//
                REQUEST_IDS.incrementAndGet(), true, this.context, bindInfo, targetMethod, parameterObjects);
        request.setPeerAddress(targetAddress);
        request.setFlags(flags);
        request.setSerializeType(bindInfo.getSerializeType());
        request.setMessage(bindInfo.isMessage() || targetMethod.isAnnotationPresent(MetMessage.class));
        request.setTimeout(this.timeout(bindInfo.getClientTimeout()));

        // 创建时复制默认选项，后续由调用方覆盖或删除。
        request.addOptionMap(this.context.getSettings().getRequestOptions());
        return request;
    }

    /** 根据服务配置与本地提供者选择本地调用或分布式调用。 */
    public MetFuture invoke(MetRequestObject rsfRequest) {
        long requestID = rsfRequest.getRequestID();
        MetBindInfo<?> bindInfo = rsfRequest.getBindInfo();
        String serviceID = bindInfo.getBindID();
        MetFuture rsfFuture = this.createFuture(rsfRequest);
        if (this.closed) {
            rsfFuture.failed(new IllegalStateException("RSF runtime closed"));
            return rsfFuture;
        }

        invLogger.info("request(" + requestID + ") -> doSendRequest, bindID =" + serviceID //
                + ", callMethod =" + rsfRequest.getMethod() + ", isMessage =" + rsfRequest.isMessage() + ".");

        try {
            MetResponseObject response = new MetResponseObject(rsfRequest);

            // 默认优先使用本地提供者；强制分布式或本地未提供实例时进入 RPC 链路。
            boolean localPreferred = rsfRequest.isLocal() && !bindInfo.isDistributedCall();
            Object localTarget = null;
            if (localPreferred) {
                Supplier<?> localProvider = this.context.getServiceProvider(bindInfo);
                if (localProvider != null) {
                    localTarget = localProvider.get();
                }
            }

            if (localTarget != null) {
                this.invokeByLocal(rsfRequest, response, localTarget);
            } else {
                this.invokeByDistributed(rsfRequest, response, rsfFuture);
            }

            // Filter 可以直接完成调用；消息回执的 Accept 也可表示已受理。
            if (response.isResponse()) {
                short status = response.getStatus();
                boolean acceptedMessage = rsfRequest.isMessage() && status == ProtocolStatus.Accept;
                if (status == ProtocolStatus.OK || acceptedMessage) {
                    rsfFuture.completed(response);
                } else {
                    rsfFuture.failed(new MetException(response.getStatus(), response.getOption("message")));
                }
            }
        } catch (Throwable e) {
            String message = "request(" + requestID + ") -> invoke failed, error =" + e.getMessage() + ".";
            invLogger.error(message, e);
            this.removeRsfFuture(rsfFuture, e);
            rsfFuture.failed(e);
        }

        return rsfFuture;
    }

    private void invokeByLocal(MetRequestObject request, MetResponseObject response, Object target) throws Throwable {
        MetInvocationContext.invoke(request, response, (currentRequest, currentResponse) -> {
            try {
                Method method = currentRequest.getMethod();
                Object result = method.invoke(target, currentRequest.getParameterObject());
                currentResponse.sendData(result);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        });
    }

    private void invokeByDistributed(MetRequestObject request, MetResponseObject response, MetFuture future) throws Throwable {
        ProtocolSelection selection = this.resolveProtocol(request);
        request.setBindAddress(selection.bindAddress);

        String serviceID = request.getBindInfo().getBindID();
        Supplier<MetFilter>[] filterProviders = this.filterProvider.getFilterProviders(serviceID);
        MetFilterHandler filters = new MetFilterHandler(filterProviders, (filterRequest, filterResponse) -> {
            if (!filterResponse.isResponse()) {
                long requestID = filterRequest.getRequestID();
                invLogger.info("request(" + requestID + ") -> sendRequest, response wait for remote.");
                this.sendRequest(future, selection);
            }
        });
        MetInvocationContext.invoke(request, response, filters);
    }

    private ProtocolSelection resolveProtocol(MetRequestObject request) {
        InterAddress target = request.getTargetAddress();
        if (target == null) {
            return new ProtocolSelection(null, null, this.context.getDefaultProtocol(), null);
        }

        MetBindInfo<?> service = request.getBindInfo();
        Set<String> declared = service.getBindProtocols();
        boolean useDefault = declared == null || declared.isEmpty();
        String defaultProtocol = this.context.getDefaultProtocol();
        Set<String> selectors = useDefault ? Collections.singleton(defaultProtocol) : declared;
        List<ProtocolCandidate> available = new ArrayList<>();
        Set<String> logicalNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectorConfig endpoint : this.context.getSettings().getConnectorConfigs()) {
            for (ProtocolConfig mount : endpoint.protocols()) {
                available.add(new ProtocolCandidate(endpoint, mount));
                logicalNames.add(mount.name());
            }
        }

        // 完整候选同时满足目标地址和逻辑身份；没有服务声明时，默认协议只负责消除歧义。
        String scheme = target.getSchema();
        available.removeIf(candidate -> !StringUtils.equalsIgnoreCase(candidate.mount.scheme(), scheme));
        List<ProtocolCandidate> candidates = this.selectProtocols(available, selectors, logicalNames);
        if (candidates.isEmpty() && useDefault) {
            candidates = available;
        }

        String serviceId = service.getBindID();
        if (candidates.isEmpty()) {
            String message = "Service [" + serviceId + "] has no protocol for address scheme " + scheme;
            message += " and selection " + selectors;
            throw new MetException(ProtocolStatus.ProtocolUndefined, message);
        }
        if (candidates.size() > 1) {
            String message = "Service [" + serviceId + "] protocol is ambiguous for address scheme " + scheme;
            message += ": " + this.describeCandidates(candidates);
            throw new MetException(ProtocolStatus.ProtocolUndefined, message);
        }

        ProtocolCandidate selected = candidates.get(0);
        InterAddress bindAddress = this.connectors.getListenAddress(//
                selected.endpoint.name(), selected.mount.name(), selected.mount.scheme());
        return new ProtocolSelection(//
                selected.endpoint.name(), selected.mount.mountId(), selected.mount.name(), bindAddress);
    }

    private List<ProtocolCandidate> selectProtocols(//
            List<ProtocolCandidate> available, Set<String> selectors, Set<String> logicalNames) {
        List<ProtocolCandidate> selected = new ArrayList<>();
        for (ProtocolCandidate candidate : available) {
            for (String selector : selectors) {
                boolean logicalName = logicalNames.contains(selector);
                String value = logicalName ? candidate.mount.name() : candidate.mount.scheme();
                if (StringUtils.equalsIgnoreCase(value, selector)) {
                    selected.add(candidate);
                    break;
                }
            }
        }
        return selected;
    }

    private String describeCandidates(List<ProtocolCandidate> candidates) {
        StringJoiner description = new StringJoiner("; ");
        for (ProtocolCandidate candidate : candidates) {
            ConnectorConfig endpoint = candidate.endpoint;
            ProtocolConfig mount = candidate.mount;
            String binding = endpoint.bindEnabled() ? "bound" : "outbound";
            String item = "endpoint=" + endpoint.name() + ", protocol=" + mount.name();
            item += ", scheme=" + mount.scheme() + ", mode=" + binding;
            description.add(item);
        }
        return description.toString();
    }

    private void sendRequest(MetFuture future, ProtocolSelection selection) throws Exception {
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

        Future<?> sending = this.sendRequest(address, selection, payload);
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

    private Future<?> sendRequest(InterAddress address, ProtocolSelection selection, RequestPayload request) {
        BasicFuture<MetChannel> sent = new BasicFuture<>();

        if (this.closed) {
            sent.failed(new IllegalStateException("RPC is closed"));
        } else {
            this.connectors.connect(selection.endpointName, selection.mountId, address).onFinal(connected -> {
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

    private static final class ProtocolSelection {
        private final String       endpointName;
        private final String       mountId;
        private final String       protocol;
        private final InterAddress bindAddress;

        private ProtocolSelection(//
                String endpointName, String mountId, String protocol, InterAddress bindAddress) {
            this.endpointName = endpointName;
            this.mountId = mountId;
            this.protocol = protocol;
            this.bindAddress = bindAddress;
        }
    }

    private static final class ProtocolCandidate {
        private final ConnectorConfig endpoint;
        private final ProtocolConfig  mount;

        private ProtocolCandidate(ConnectorConfig endpoint, ProtocolConfig mount) {
            this.endpoint = endpoint;
            this.mount = mount;
        }
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

            long requestId = request.getRequestID();
            String bindId = request.getBindInfo().getBindID();
            short status = info.getStatus();
            String logPrefix = "response(" + requestId + ") -> statusFailed";
            String message = logPrefix + ", bindID =" + bindId + ", status =" + status + ".";
            invLogger.error(message);
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
        long requestId = request.getRequestID();
        String bindId = request.getBindInfo().getBindID();
        invLogger.info("response(" + requestId + ") -> successful, bindID =" + bindId + ".");
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

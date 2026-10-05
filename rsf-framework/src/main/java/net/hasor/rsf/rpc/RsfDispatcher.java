/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfConstants;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches inbound requests without owning outbound calls or client proxies. */
final class RsfDispatcher implements AutoCloseable {
    private static final Logger                logger    = LoggerFactory.getLogger(RsfDispatcher.class);
    private static final Logger                invLogger = LoggerFactory.getLogger(RsfConstants.LoggerName_Invoker);
    private final        RsfContext            context;
    private final        RsfFilterProvider     filters;
    private final        RsfDispatcherExecutor dispatcherExecutor;

    public RsfDispatcher(RsfContext context, RsfFilterProvider filters) {
        this.context = context;
        this.filters = filters;
        this.dispatcherExecutor = new RsfDispatcherExecutor(context.getSettings(), context.getClassLoader());
    }

    public RsfContext getContext() {
        return this.context;
    }

    public Supplier<RsfFilter>[] getFilterProviders(String serviceId) {
        return this.filters.getFilterProviders(serviceId);
    }

    @Override
    public void close() {
        this.dispatcherExecutor.shutdown();
    }

    /**
     * 收到Request请求，并将该请求安排进队列，由队列安排方法调用。
     * @param channel 请求来源通道。
     * @param requestId 请求标识。
     * @param info 请求消息。
     */
    public void onRequest(RsfChannel channel, long requestId, RequestPayload info) {
        if (requestId != info.getRequestID()) {
            logger.warn("Request ID mismatch: envelope={}, payload={}", requestId, info.getRequestID());
            info.complete(new IllegalArgumentException("Request ID mismatch"));
            return;
        }

        InterAddress target = channel.getRemote();
        String serviceUniqueName = "[" + info.getServiceGroup() + "]" + info.getServiceName() + "-" + info.getServiceVersion();
        try {
            invLogger.info("request({}) -> received, bindID ={}, targetMethod ={}, remoteAddress ={}.", //
                    info.getRequestID(), serviceUniqueName, info.getTargetMethod(), target);

            Executor executor = this.dispatcherExecutor.getExecute(serviceUniqueName);
            executor.execute(new RsfInvocationTask(channel, this, info));//放入业务线程准备执行
            ResponsePayload resp = RsfPayloadBuilder.buildResponseStatus(info.getRequestID(), ProtocolStatus.Accept, null);

            channel.sendData(resp).onFinal(done -> {
                if (info.isMessage() || resp.getStatus() != ProtocolStatus.Accept || done.getCause() != null || done.isCancelled()) {
                    info.complete(done.isCancelled() ? new IllegalStateException("Response write cancelled") : done.getCause());
                }
            });
        } catch (RejectedExecutionException e) {
            invLogger.info("request({}) -> rejected request, queue is full. -> bindID ={}, targetMethod ={}, remoteAddress ={}.", //
                    info.getRequestID(), serviceUniqueName, info.getTargetMethod(), target);

            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String msgLog = "rejected request, queue is full." + errorMessage;
            logger.warn(msgLog, e);
            ResponsePayload resp = RsfPayloadBuilder.buildResponseStatus(info.getRequestID(), ProtocolStatus.QueueFull, msgLog);

            channel.sendData(resp).onFinal(done -> {
                if (info.isMessage() || resp.getStatus() != ProtocolStatus.Accept || done.getCause() != null || done.isCancelled()) {
                    info.complete(done.isCancelled() ? new IllegalStateException("Response write cancelled") : done.getCause());
                }
            });
        }
    }
}

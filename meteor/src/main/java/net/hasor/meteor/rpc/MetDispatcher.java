/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** Dispatches inbound requests without owning outbound calls or client proxies. */
final class MetDispatcher implements AutoCloseable {
    private static final Logger                logger = LoggerFactory.getLogger(MetDispatcher.class);
    private final        MetContext            context;
    private final        MetFilterProvider     filters;
    private final        MetDispatcherExecutor dispatcherExecutor;

    MetDispatcher(MetContext context, MetFilterProvider filters) {
        this.context = context;
        this.filters = filters;
        this.dispatcherExecutor = new MetDispatcherExecutor(context);
    }

    MetContext getContext() {
        return this.context;
    }

    Supplier<MetFilter>[] getFilterProviders(String serviceId) {
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
    void onRequest(MetChannel channel, long requestId, RequestPayload info) {
        long receivedNanos = System.nanoTime();
        if (requestId != info.getRequestID()) {
            logger.warn("Request ID mismatch: envelope=" + requestId + ", payload=" + info.getRequestID());
            info.complete(new IllegalArgumentException("Request ID mismatch"));
            return;
        }

        // 协议暴露限制在入队和 Accept 回执之前检查，消息调用同样不能绕过。
        MetBindInfo<?> service = this.context.getServiceInfo(info.getServiceGroup(), info.getServiceName(), info.getServiceVersion());
        if (service != null && !service.getBindProtocols().isEmpty()) {
            if (!this.accepts(service.getBindProtocols(), channel)) {
                String scheme = channel.getLocal().getSchema();
                String message = "Service is not exposed through protocol: " + scheme;
                ResponsePayload response = MetPayloadBuilder.buildResponseStatus(//
                        requestId, ProtocolStatus.Forbidden, message);
                channel.sendData(response).onFinal(done -> this.completeResponse(info, response, done));
                return;
            }
        }

        InterAddress target = channel.getRemote();
        String serviceUniqueName = "[" + info.getServiceGroup() + "]";
        serviceUniqueName += info.getServiceName() + "-" + info.getServiceVersion();
        try {
            String targetMethod = info.getTargetMethod();
            String message = "request(" + info.getRequestID() + ") -> received, bindID =" + serviceUniqueName;
            message += ", targetMethod =" + targetMethod + ", remoteAddress =" + target + ".";
            logger.info(message);

            // 放入业务线程准备执行，再向对端发送接纳回执。
            MetInvocationTask task = new MetInvocationTask(channel, this, info, receivedNanos);
            this.dispatcherExecutor.execute(service, task);
            ResponsePayload resp = MetPayloadBuilder.buildResponseStatus(//
                    info.getRequestID(), ProtocolStatus.Accept, null);

            channel.sendData(resp).onFinal(done -> this.completeResponse(info, resp, done));
        } catch (RejectedExecutionException e) {
            String targetMethod = info.getTargetMethod();
            String message = "request(" + info.getRequestID() + ") -> rejected request, queue is full.";
            message += " -> bindID =" + serviceUniqueName + ", targetMethod =" + targetMethod;
            message += ", remoteAddress =" + target + ".";
            logger.info(message);

            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String msgLog = "rejected request, queue is full." + errorMessage;
            logger.warn(msgLog, e);
            ResponsePayload resp = MetPayloadBuilder.buildResponseStatus(//
                    info.getRequestID(), ProtocolStatus.QueueFull, msgLog);

            channel.sendData(resp).onFinal(done -> this.completeResponse(info, resp, done));
        }
    }

    private void completeResponse(RequestPayload request, ResponsePayload response, Future<MetChannel> writing) {
        boolean finalResponse = request.isMessage() || response.getStatus() != ProtocolStatus.Accept;
        boolean writeFailed = writing.getCause() != null || writing.isCancelled();
        if (!finalResponse && !writeFailed) {
            return;
        }

        Throwable failure = writing.getCause();
        if (writing.isCancelled()) {
            failure = new IllegalStateException("Response write cancelled");
        }
        request.complete(failure);
    }

    private boolean accepts(Set<String> selected, MetChannel channel) {
        String protocol = channel.getProtocol();
        String scheme = channel.getLocal().getSchema();
        Set<String> logicalNames = this.context.getSettings().getProtocols();
        for (String value : selected) {
            boolean logical = false;
            for (String name : logicalNames) {
                if (StringUtils.equalsIgnoreCase(value, name)) {
                    logical = true;
                    break;
                }
            }
            boolean matches = logical ? StringUtils.equalsIgnoreCase(value, protocol) : //
                    StringUtils.equalsIgnoreCase(value, scheme);
            if (matches) {
                return true;
            }
        }
        return false;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller;
import net.hasor.rsf.address.InterAddress;
import io.netty.util.TimerTask;
import net.hasor.rsf.*;
import net.hasor.rsf.domain.*;
import java.util.concurrent.CancellationException;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.cobble.concurrent.future.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责管理所有 RSF 发起的请求，Manager还提供了最大并发上限的配置.
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class RsfRequestManager {
    protected static Logger                         logger    = LoggerFactory.getLogger(RsfRequestManager.class);
    protected static Logger                         invLogger = LoggerFactory.getLogger(RsfConstants.LoggerName_Invoker);
    private final    ConcurrentMap<Long, RsfFuture> rsfResponse;
    private final    RsfContext                     rsfContext;
    // Each permit belongs either to a sender preparing a request or to one pending map entry.
    private final    Semaphore                      requestPermits;
    private final    SenderListener                 senderListener;
    private volatile boolean closed;

    protected void shutdownRequests() {
        List<RsfFuture> pending;
        synchronized (this) {
            this.closed = true;
            pending = new ArrayList<>(this.rsfResponse.values());
            this.rsfResponse.clear();
            this.requestPermits.release(pending.size());
        }
        for (RsfFuture future : pending) {
            try { future.failed(new IllegalStateException("RSF runtime closed")); }
            catch (RuntimeException failure) { logger.warn("Request completion callback failed during close", failure); }
        }
    }

    public RsfRequestManager(RsfContext rsfContext, SenderListener senderListener) {
        senderListener = Objects.requireNonNull(senderListener, "not found SendData.");
        this.rsfContext = rsfContext;
        this.rsfResponse = new ConcurrentHashMap<>();
        this.requestPermits = new Semaphore(rsfContext.getSettings().getMaximumRequest());
        this.senderListener = senderListener;
    }

    /**获取RSF容器对象。*/
    public RsfContext getContext() {
        return this.rsfContext;
    }

    /** Supplies filters selected by the hosting runtime for this service. */
    public abstract Supplier<RsfFilter>[] getFilterProviders(String serviceID);

    /**
     * 获取正在进行中的调用请求。
     * @param requestID 请求ID
     * @return 返回RsfFuture。
     */
    public RsfFuture getRequest(long requestID) {
        return this.rsfResponse.get(requestID);
    }

    /**
     * 响应挂起的Request请求。
     * @param info 响应结果
     */
    public boolean putResponse(ResponseInfo info) {
        long requestID = info.getRequestID();
        RsfFuture rsfFuture = this.rsfResponse.get(requestID);
        if (rsfFuture == null) {
            invLogger.warn("response({}) -> timeoutFailed, RsfFuture is not exist. -> maybe is timeout!", requestID);
            return false;
        }
        //
        // 1.处理ACK应答 -> (Invoke类型调用,不处理ACK应答)
        if (info.getStatus() == ProtocolStatus.Accept) {
            if (!rsfFuture.getRequest().isMessage()) {
                invLogger.info("response({}) -> ignore, rpcType = Invoke, status = Accept", requestID);
                return true;/* Invoker类型request不处理 ack 应答,只有Message类型请求才会把ACK应答作为response进行处理。 */
            }
        }
        //
        // 2.处理response
        rsfFuture = this.removeRsfFuture(requestID);
        if (rsfFuture == null) {
            invLogger.warn("response({}) -> timeoutFailed, RsfFuture is not exist. -> maybe is timeout!", requestID);
            return false;
        }
        //
        // 3.反序列化
        RsfRequest rsfRequest = rsfFuture.getRequest();
        RsfResponseObject local = new RsfResponseObject(rsfRequest);
        local.addOptionMap(info);
        local.sendStatus(info.getStatus());
        String serializeType = info.getSerializeType();
        String bindID = local.getBindInfo().getBindID();
        Method callMethod = rsfRequest.getMethod();
        invLogger.info("response({}) -> receiveTime ={}, serializeType ={}, status ={}, isMessage ={}, bindID ={}, callMethod ={}.",//
                requestID, info.getReceiveTime(), serializeType, info.getStatus(), rsfRequest.isMessage(), bindID, callMethod);
        //
        // - Message 调用
        if (rsfRequest.isMessage()) {
            Class<?> returnType = rsfRequest.getMethod().getReturnType();
            RsfResultDO returnObject = null;
            if (info.getStatus() == ProtocolStatus.Accept) {
                returnObject = new RsfResultDO(requestID, true);
            } else {
                returnObject = new RsfResultDO(requestID, false);
                returnObject.setErrorCode(info.getStatus());
                returnObject.setErrorMessage(info.getOption("message"));
            }
            //
            if (returnObject.isSuccess()) {
                invLogger.info("response({}) -> successful.", requestID);
                if (returnType.isAssignableFrom(RsfResult.class)) {
                    local.sendData(returnObject);
                    return rsfFuture.completed(local);
                }
                if (returnObject.isSuccess()) {
                    local.sendData(null);
                    return rsfFuture.completed(local);
                }
            }
            //
            String errorInfo = "errorCode = " + returnObject.getErrorCode() + ", errorMessage=" + returnObject.getErrorMessage();
            invLogger.error("response({}) -> invokeFailed, {}", requestID, errorInfo);
            return rsfFuture.failed(new RsfException(local.getStatus(), errorInfo));
        }
        // - Invoker 调用
        if (info.getStatus() == ProtocolStatus.OK) {
            local.sendData(info.getReturnData());
            return rsfFuture.completed(local);
        } else {
            invLogger.error("response({}) -> statusFailed, bindID ={}, status ={}.",//
                    requestID, bindID, local.getStatus());
            return rsfFuture.failed(new RsfException(local.getStatus(), "status."));
        }
    }

    /**
     * 响应挂起的Request请求。
     * @param requestID 请求ID
     * @param e 异常响应
     */
    public void putResponse(long requestID, Throwable e) {
        RsfFuture rsfFuture = this.removeRsfFuture(requestID);
        if (rsfFuture != null) {
            invLogger.error("response({}) -> errorFailed, {}", requestID, e.getMessage(), e);
            rsfFuture.failed(e);
        } else {
            invLogger.error("response({}) -> errorFailed, RsfFuture is not exist. -> maybe is timeout! ,error= {}.", requestID, e.getMessage(), e);
        }
    }

    private synchronized RsfFuture removeRsfFuture(long requestID) {
        RsfFuture rsfFuture = this.rsfResponse.remove(requestID);
        if (rsfFuture != null) {
            this.requestPermits.release();
        }
        return rsfFuture;
    }

    /**
     * 发送RSF调用请求，处理RsfFilter
     * @param rsfRequest rsf请求
     * @param listener FutureCallback回调监听器。
     * @return 返回RsfFuture。
     */
    protected RsfFuture doSendRequest(RsfRequestFormLocal rsfRequest, FutureCallback<RsfResponse> listener) {
        RsfBindInfo<?> bindInfo = rsfRequest.getBindInfo();
        String serviceID = bindInfo.getBindID();
        final RsfFuture rsfFuture = new RsfFuture(rsfRequest, listener) {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                try {
                    return super.cancel(mayInterruptIfRunning);
                } finally {
                    // Cancellation callbacks can throw or skip onCancel listeners.
                    if (isCancelled()) {
                        removeRsfFuture(rsfRequest.getRequestID());
                    }
                }
            }
        };
        if (this.closed) { rsfFuture.failed(new IllegalStateException("RSF runtime closed")); return rsfFuture; }
        invLogger.info("request({}) -> doSendRequest, bindID ={}, callMethod ={}, isMessage ={}.", //
                rsfRequest.getRequestID(), serviceID, rsfRequest.getMethod(), bindInfo.isMessage());
        //
        // .setup RsfRequest
        rsfRequest.addOptionMap(this.getContext().getSettings().getClientOption());//写入客户端选项，并将选项发送到Server。
        //
        // .call Chain
        try {
            RsfResponseObject res = new RsfResponseObject(rsfRequest);
            /*下面这段代码要负责 -> 执行rsfFilter过滤器链，并最终调用sendRequest发送请求。*/
            Supplier<RsfFilter>[] rsfFilterList = this.getFilterProviders(serviceID);
            new RsfFilterHandler(rsfFilterList, (request, response) -> {
                if (response.isResponse()) {
                    invLogger.info("request({}) -> sendRequest, response form local.", request.getRequestID());
                    rsfFuture.completed(response);//如果本地调用链已经做出了响应，那么不在需要发送到远端。
                } else {
                    invLogger.info("request({}) -> sendRequest, response wait for remote.", request.getRequestID());
                    sendRequest(rsfFuture);//发送请求到远方
                }
            }).doFilter(rsfRequest, res);
        } catch (Throwable e) {
            invLogger.error("request({}) -> errorFailed, sendRequest, doRsfFilterChain. error ={}.", rsfRequest.getRequestID(), e.getMessage(), e);
            removeRsfFuture(rsfRequest.getRequestID());
            try {
                rsfFuture.failed(e);
            } catch (Throwable e2) {
                logger.error("request({}) -> {}.", rsfRequest.getRequestID(), e2.getMessage(), e2);
            }
        }
        return rsfFuture;
    }

    /**将请求发送到远端服务器。*/
    private void sendRequest(final RsfFuture rsfFuture) throws Throwable {
        /*1.远程目标机*/
        final RsfRequestFormLocal rsfRequest = (RsfRequestFormLocal) rsfFuture.getRequest();
        String serviceID = rsfRequest.getBindInfo().getBindID();
        invLogger.info("request({}) -> bindID ={}, callMethod ={}, serializeType ={}, isMessage ={}, isP2PCalls ={}.",//
                rsfRequest.getRequestID(), serviceID, rsfRequest.getMethod(), rsfRequest.getSerializeType(), rsfRequest.isMessage(), rsfRequest.isP2PCalls());
        //
        if (rsfFuture.isDone()) {
            return;
        }
        /*2.检查目标地址*/
        InterAddress toAddress = rsfRequest.getTargetAddress();
        if (toAddress == null) {
            invLogger.warn("request({}) -> targetAddress Unavailable, bindID ={}.", rsfRequest.getRequestID(), serviceID);
            rsfFuture.failed(new RsfException(ProtocolStatus.Forbidden, "Service [" + serviceID + "] Address Unavailable."));
            return;
        }
        /*3.获取并发名额，再构造和发送请求。*/
        if (!acquireRequestPermit(rsfFuture)) {
            return;
        }
        boolean registered = false;
        try {
            invLogger.warn("request({}) -> pre sendData, bindID ={}, targetAddress ={}.", rsfRequest.getRequestID(), serviceID, toAddress);
            RsfEnvironment environment = this.getContext().getEnvironment();
            RequestInfo info = RpcMessageMapper.buildRequestInfo(environment, rsfRequest); // <- 1.生成RequestInfo
            info.setFlags(rsfRequest.getFlags());
            registerRequest(rsfFuture);
            registered = true; // From here, only removal from rsfResponse releases the permit.
            startRequest(rsfFuture);                                                    // <- 2.开始 timeout 计时
            if (rsfFuture.isDone()) {
                return;
            }
            Future<Void> sending = this.senderListener.sendRequest(toAddress, info);
            sending.onFailed(sent -> putResponse(info.getRequestID(), sent.getCause()));
            sending.onCancel(sent -> putResponse(info.getRequestID(), new CancellationException("Request send cancelled")));
        } finally {
            if (!registered) {
                this.requestPermits.release();
            }
        }
    }

    private boolean acquireRequestPermit(RsfFuture future) throws InterruptedException {
        if (this.requestPermits.tryAcquire()) {
            return true;
        }
        SendLimitPolicy policy = getContext().getSettings().getSendLimitPolicy();
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
        future.failed(new RsfException(ProtocolStatus.SendLimitPolicy,
                "request(" + future.getRequest().getRequestID() + ") -> maximum number of requests, apply SendPolicy = " + policy));
        return false;
    }

    private synchronized void registerRequest(RsfFuture future) {
        if (this.closed) {
            throw new IllegalStateException("RSF runtime closed");
        }
        if (this.rsfResponse.putIfAbsent(future.getRequest().getRequestID(), future) != null) {
            throw new IllegalStateException("Request is already pending");
        }
    }

    /**
     * 负责客户端引发的超时逻辑。
     * @param rsfFuture 开始计时的请求。
     */
    private void startRequest(RsfFuture rsfFuture) {
        final RsfRequestFormLocal request = (RsfRequestFormLocal) rsfFuture.getRequest();
        TimerTask timeTask = timeoutObject -> {
            RsfFuture rsfCallBack = getRequest(request.getRequestID());
            /*检测不到说明请求已经被正确响应。*/
            if (rsfCallBack == null) {
                return;
            }
            /*异常信息*/
            String errorInfo = "request(" + request.getRequestID() + ") -> timeout for client.";
            invLogger.error(errorInfo);
            /*回应Response*/
            putResponse(request.getRequestID(), new RsfTimeoutException(errorInfo));
        };
        invLogger.info("request({}) -> startRequest, timeout at {} ,bindID ={}, callMethod ={}.", //
                request.getRequestID(), request.getTimeout(), request.getBindInfo().getBindID(), request.getMethod());
        this.getContext().getEnvironment().atTime(timeTask, request.getTimeout());
    }
}

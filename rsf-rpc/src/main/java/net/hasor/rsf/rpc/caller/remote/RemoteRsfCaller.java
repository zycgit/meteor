/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller.remote;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.Exchange;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RequestInfo;
import net.hasor.rsf.domain.ResponseInfo;
import net.hasor.rsf.rpc.caller.RsfCaller;
import net.hasor.rsf.rpc.caller.RsfFilterProvider;
import net.hasor.rsf.rpc.caller.SenderListener;
import net.hasor.rsf.rpc.caller.remote.ExecutesManager;
import net.hasor.rsf.rpc.caller.RpcMessageMapper;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 扩展{@link RsfCaller}，用来支持远程机器发来的调用请求。
 * @version : 2015年12月8日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RemoteRsfCaller extends RsfCaller {
    private final ExecutesManager executesManager;

    public RemoteRsfCaller(RsfContext rsfContext, RsfFilterProvider filterProvider, SenderListener senderListener) {
        super(rsfContext, filterProvider, senderListener);
        //
        RsfSettings rsfSettings = rsfContext.getSettings();
        int queueSize = rsfSettings.getQueueMaxSize();
        int minCorePoolSize = rsfSettings.getQueueMinPoolSize();
        int maxCorePoolSize = rsfSettings.getQueueMaxPoolSize();
        long keepAliveTime = rsfSettings.getQueueKeepAliveTime();
        this.executesManager = new ExecutesManager(minCorePoolSize, maxCorePoolSize, queueSize, keepAliveTime, rsfContext.getClassLoader());
    }

    /**销毁。*/
    public void shutdown() {
        logger.info("rsfCaller -> shutdown.");
        this.shutdownRequests();
        this.executesManager.shutdown();
    }

    /**
     * 收到Request请求，并将该请求安排进队列，由队列安排方法调用。
     * @param exchange 请求的响应出口。
     * @param info 请求消息。
     */
    public void onRequest(Exchange exchange, RequestInfo info) {
        InterAddress target = exchange.peer();
        RsfEnvironment rsfEnv = this.getContext().getEnvironment();
        String serviceUniqueName = "[" + info.getServiceGroup() + "]" + info.getServiceName() + "-" + info.getServiceVersion();
        try {
            invLogger.info("request({}) -> received, bindID ={}, targetMethod ={}, remoteAddress ={}.", //
                    info.getRequestID(), serviceUniqueName, info.getTargetMethod(), target);
            //
            Executor executor = this.executesManager.getExecute(serviceUniqueName);
            executor.execute(new RemoteRsfCallerProcessing(exchange, this, info));//放入业务线程准备执行
            ResponseInfo resp = RpcMessageMapper.buildResponseStatus(rsfEnv, info.getRequestID(), ProtocolStatus.Accept, null);
            exchange.reply(resp);
        } catch (RejectedExecutionException e) {
            invLogger.info("request({}) -> rejected request, queue is full. -> bindID ={}, targetMethod ={}, remoteAddress ={}.", //
                    info.getRequestID(), serviceUniqueName, info.getTargetMethod(), target);
            //
            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String msgLog = "rejected request, queue is full." + errorMessage;
            logger.warn(msgLog, e);
            ResponseInfo resp = RpcMessageMapper.buildResponseStatus(rsfEnv, info.getRequestID(), ProtocolStatus.QueueFull, msgLog);
            exchange.reply(resp);
        }
    }

}

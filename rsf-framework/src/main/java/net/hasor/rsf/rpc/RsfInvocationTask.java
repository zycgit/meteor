/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.RsfFilterChain;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.serialize.SerializeCoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 负责处理远程Request对象的请求调用，同时也负责将产生的Response对象写回客户端。
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
final class RsfInvocationTask implements Runnable {
    private final Logger         logger              = LoggerFactory.getLogger(RsfInvocationTask.class);
    private final RsfFilterChain INVOCATION_EXECUTOR = new RsfInvocationExecutor();
    private final RsfDispatcher  dispatcher;
    private final InterAddress   target;
    private final RsfChannel     channel;
    private final RequestPayload requestInfo;
    private final ClassLoader    classLoader;

    RsfInvocationTask(RsfChannel channel, RsfDispatcher dispatcher, RequestPayload requestInfo) {
        this.channel = channel;
        this.target = channel.getRemote();
        this.dispatcher = dispatcher;
        this.requestInfo = requestInfo;
        this.classLoader = dispatcher.getContext().getClassLoader();
    }

    @Override
    public void run() {
        if (!this.requestInfo.isMessage() && this.requestInfo.completion().isDone()) {
            return;
        }

        /*正确性检验。*/
        long requestID = this.requestInfo.getRequestID();
        String group = this.requestInfo.getServiceGroup();
        String name = this.requestInfo.getServiceName();
        String version = this.requestInfo.getServiceVersion();
        RsfBindInfo<?> bindInfo = this.dispatcher.getContext().getServiceInfo(group, name, version);
        if (bindInfo == null || RsfServiceType.Provider != bindInfo.getServiceType()) {
            String serviceID = "[" + group + "]" + name + "-" + version;
            String errorInfo = "do request(" + requestID + ") failed -> service " + serviceID + " not exist.";
            this.logger.error(errorInfo);
            ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.NotFound, errorInfo);
            this.sendResponse(info);
            return;
        }

        /*检查timeout。*/
        long lostTime = System.currentTimeMillis() - this.requestInfo.getReceiveTime();
        int clientTimeout = this.requestInfo.getClientTimeout();
        int timeout = this.validateTimeout(clientTimeout, bindInfo);
        if (lostTime > timeout) {
            String errorInfo = "do request(" + requestID + ") failed -> timeout for server.";
            this.logger.error(errorInfo);
            ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.Timeout, errorInfo);
            this.sendResponse(info);
            return;
        }

        /*准备参数*/
        String serializeType = this.requestInfo.getSerializeType();
        Object[] pObjects = null;
        Class<?>[] pTypes = null;
        try {
            //1.确定序列化器
            SerializeCoder coder = this.dispatcher.getContext().getSerializeCoder(serializeType);
            if (coder == null) {
                String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") is undefined.";
                this.logger.error(errorInfo);
                ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.SerializeForbidden, errorInfo);
                this.sendResponse(info);
                return;
            }

            //2.参数数量校验
            List<String> pTypeList = this.requestInfo.getParameterTypes();
            List<Object> pObjectList = this.requestInfo.getParameterValues();
            if (pTypeList.size() != pObjectList.size()) {
                String errorInfo = "do request(" + requestID + ") failed -> parameters count and types count, not equal.";
                this.logger.error(errorInfo);
                ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.InvokeError, errorInfo);
                this.sendResponse(info);
                return;
            }

            //3.反序列化
            pTypes = new Class<?>[pTypeList.size()];
            pObjects = new Object[pObjectList.size()];
            for (int i = 0; i < pTypeList.size(); i++) {
                String paramTypeStr = pTypeList.get(i);
                Object paramObject = pObjectList.get(i);

                pTypes[i] = RsfRuntimeUtils.getType(paramTypeStr, this.classLoader);
                pObjects[i] = paramObject;
            }
        } catch (Throwable e) {
            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") ,serialize error: " + errorMessage;
            this.logger.error(errorInfo, e);
            ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.SerializeError, errorInfo);
            this.sendResponse(info);
            return;
        }

        /*执行调用*/
        Method targetMethod = null;
        try {
            String methodName = this.requestInfo.getTargetMethod();
            targetMethod = bindInfo.getBindType().getMethod(methodName, pTypes);
        } catch (Throwable e) {
            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String errorInfo = "do request(" + requestID + ") failed -> lookup service method error : " + errorMessage;
            this.logger.error(errorInfo, e);
            ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.Forbidden, errorInfo);
            this.sendResponse(info);
            return;
        }

        try {
            RsfRequestObject rsfRequest = new RsfRequestObject(requestID, false, this.dispatcher.getContext(), bindInfo, targetMethod, pObjects);
            rsfRequest.setPeerAddress(this.target);
            rsfRequest.setFlags(this.requestInfo.getFlags());
            rsfRequest.setSerializeType(this.requestInfo.getSerializeType());
            rsfRequest.setMessage(this.requestInfo.isMessage());
            rsfRequest.setTimeout(timeout);
            rsfRequest.setReceiveTime(this.requestInfo.getReceiveTime());
            rsfRequest.setOptions(this.requestInfo);
            RsfResponseObject rsfResponse = new RsfResponseObject(rsfRequest);
            rsfResponse.addOptionMap(this.dispatcher.getContext().getSettings().getResponseOptions());//填充响应的默认附加选项。

            String serviceID = bindInfo.getBindID();
            Supplier<RsfFilter>[] rsfFilters = this.dispatcher.getFilterProviders(serviceID);
            new RsfFilterHandler(rsfFilters, INVOCATION_EXECUTOR).doFilter(rsfRequest, rsfResponse);

            this.sendResponse(rsfResponse);//将Response写入客户端。
        } catch (Throwable e) {
            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String msgLog = "do request(" + requestID + ") failed -> service " + bindInfo.getBindID() + ", error :" + errorMessage;
            this.logger.error(msgLog, e);
            ResponsePayload info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.InvokeError, msgLog);
            this.sendResponse(info);
        }
    }

    private int validateTimeout(int timeout, RsfBindInfo<?> bindInfo) {
        if (timeout <= 0) {
            timeout = this.dispatcher.getContext().getSettings().getDefaultTimeout();
        }
        int serviceTimeout = bindInfo.getClientTimeout();
        if (serviceTimeout <= 0) {
            serviceTimeout = this.dispatcher.getContext().getSettings().getDefaultTimeout();
        }
        return Math.min(timeout, serviceTimeout);
    }

    private void sendResponse(RsfResponseObject rsfResponse) {
        if (this.requestInfo.isMessage()) {
            return;/*如果是消息类型调用,则丢弃response*/
        }

        String serializeType = this.requestInfo.getSerializeType();
        long requestID = rsfResponse.getRequestID();
        ResponsePayload info = null;
        try {

            //1.确定序列化器
            SerializeCoder coder = this.dispatcher.getContext().getSerializeCoder(serializeType);

            //2.Response对象
            if (coder == null) {
                String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") is undefined.";
                this.logger.error(errorInfo);
                info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.SerializeForbidden, errorInfo);
            } else {
                info = RsfPayloadBuilder.buildResponsePayload(rsfResponse);
            }
        } catch (Throwable e) {
            info = null;
            String errorMessage = "(" + e.getClass().getName() + ")" + e.getMessage();
            String errorInfo = "do request(" + requestID + ") failed -> serializeType(" + serializeType + ") ,serialize error: " + errorMessage;
            this.logger.error(errorInfo, e);
            info = RsfPayloadBuilder.buildResponseStatus(requestID, ProtocolStatus.SerializeError, errorInfo);
        } finally {
            this.sendResponse(info);
        }
    }

    private void sendResponse(ResponsePayload info) {
        if (this.requestInfo.isMessage()) {
            return;/*如果是消息类型调用,则丢弃response*/
        }
        if (!this.requestInfo.completion().isDone()) {
            this.channel.sendData(info).onFinal(done -> {
                this.requestInfo.complete(done.isCancelled() ? new IllegalStateException("Response write cancelled") : done.getCause());
            });
        }
    }
}

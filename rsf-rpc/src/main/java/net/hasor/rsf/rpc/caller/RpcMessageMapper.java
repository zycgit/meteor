/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfRequest;
import net.hasor.rsf.RsfResponse;
import net.hasor.rsf.domain.RequestInfo;
import net.hasor.rsf.domain.ResponseInfo;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.cobble.StringUtils;

import java.io.IOException;

/**
 *
 * @version : 2015年3月28日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RpcMessageMapper {
    /**将{@link RsfRequest},转换为{@link RequestInfo}。*/
    public static RequestInfo buildRequestInfo(RsfEnvironment env, RsfRequest rsfRequest) throws IOException {
        RequestInfo info = new RequestInfo();
        RsfBindInfo<?> rsfBindInfo = rsfRequest.getBindInfo();
        String serializeType = rsfRequest.getSerializeType();
        //
        //1.基本信息
        info.setRequestID(rsfRequest.getRequestID());//请求ID
        info.setServiceGroup(rsfBindInfo.getBindGroup());//序列化策略
        info.setServiceName(rsfBindInfo.getBindName());//序列化策略
        info.setServiceVersion(rsfBindInfo.getBindVersion());//序列化策略
        info.setTargetMethod(rsfRequest.getMethod().getName());//序列化策略
        info.setSerializeType(serializeType);//序列化策略
        info.setClientTimeout(rsfRequest.getTimeout());
        info.setMessage(rsfRequest.isMessage());
        //
        //2.params
        Class<?>[] pTypes = rsfRequest.getParameterTypes();
        Object[] pObjects = rsfRequest.getParameterObject();
        pTypes = (pTypes == null) ? new Class[0] : pTypes;
        pObjects = (pObjects == null) ? new Object[0] : pObjects;
        for (int i = 0; i < pTypes.length; i++) {
            String typeByte = RsfRuntimeUtils.toAsmType(pTypes[i]);
            info.addParameter(typeByte, pObjects[i]);
        }
        //
        //3.Opt参数
        info.addOptionMap(rsfRequest);
        //
        return info;
    }

    public static ResponseInfo buildResponseStatus(RsfEnvironment env, long requestID, short status, String errorInfo) {
        ResponseInfo info = new ResponseInfo();
        info.setRequestID(requestID);
        info.setStatus(status);
        if (StringUtils.isNotBlank(errorInfo)) {
            info.addOption("message", errorInfo);
        }
        return info;
    }

    /**将{@link RsfResponse},转换为{@link ResponseInfo}。*/
    public static ResponseInfo buildResponseInfo(RsfEnvironment env, RsfResponse rsfResponse) throws IOException {
        ResponseInfo info = new ResponseInfo();
        String serializeType = rsfResponse.getSerializeType();
        //        SerializeCoder coder = env.getSerializeCoder(serializeType);
        //        byte[] returnData = coder.encode(rsfResponse.getData());
        info.setRequestID(rsfResponse.getRequestID());
        info.setStatus(rsfResponse.getStatus());
        info.setSerializeType(serializeType);
        info.setReturnType(rsfResponse.getReturnType().getName());
        info.setReturnData(rsfResponse.getData());
        info.addOptionMap(rsfResponse);
        //
        return info;
    }

}

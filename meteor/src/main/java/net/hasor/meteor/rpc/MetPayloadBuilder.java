/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.io.IOException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/**
 * 构造 RPC 请求、响应和状态通知的 Payload。
 * @version : 2015年3月28日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetPayloadBuilder {
    /**将{@link MetRequest},转换为{@link RequestPayload}。*/
    public static RequestPayload buildRequestPayload(MetRequest rsfRequest) throws IOException {
        RequestPayload info = new RequestPayload();
        MetBindInfo<?> rsfBindInfo = rsfRequest.getBindInfo();
        String serializeType = rsfRequest.getSerializeType();

        //1.基本信息
        info.setRequestID(rsfRequest.getRequestID());//请求ID
        info.setServiceGroup(rsfBindInfo.getBindGroup());//序列化策略
        info.setServiceName(rsfBindInfo.getBindName());//序列化策略
        info.setServiceVersion(rsfBindInfo.getBindVersion());//序列化策略
        info.setTargetMethod(rsfRequest.getMethod().getName());//序列化策略
        info.setSerializeType(serializeType);//序列化策略
        info.setClientTimeout(rsfRequest.getTimeout());
        info.setMessage(rsfRequest.isMessage());

        //2.params
        Class<?>[] pTypes = rsfRequest.getParameterTypes();
        Object[] pObjects = rsfRequest.getParameterObject();
        pTypes = (pTypes == null) ? new Class[0] : pTypes;
        pObjects = (pObjects == null) ? new Object[0] : pObjects;
        for (int i = 0; i < pTypes.length; i++) {
            String typeByte = MetRuntimeUtils.toAsmType(pTypes[i]);
            info.addParameter(typeByte, pObjects[i]);
        }

        //3.Opt参数
        info.addOptionMap(rsfRequest);
        return info;
    }

    public static ResponsePayload buildResponseStatus(long requestID, short status, String errorInfo) {
        ResponsePayload info = new ResponsePayload();
        info.setRequestID(requestID);
        info.setStatus(status);
        if (StringUtils.isNotBlank(errorInfo)) {
            info.addOption("message", errorInfo);
        }
        return info;
    }

    /**将{@link MetResponse},转换为{@link ResponsePayload}。*/
    public static ResponsePayload buildResponsePayload(MetResponse rsfResponse) throws IOException {
        ResponsePayload info = new ResponsePayload();
        String serializeType = rsfResponse.getSerializeType();
        //        SerializeCoder coder = env.getSerializeCoder(serializeType);
        //        byte[] returnData = coder.encode(rsfResponse.getData());
        info.setRequestID(rsfResponse.getRequestID());
        info.setStatus(rsfResponse.getStatus());
        info.setSerializeType(serializeType);
        info.setReturnType(rsfResponse.getReturnType().getName());
        info.setReturnData(rsfResponse.getData());
        info.addOptionMap(rsfResponse);
        return info;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.payload;
/**
 * RSF Response 的化身,是封装 Response 的数据对象。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ResponsePayload extends Payload {
    private long   requestID     = 0;    //请求ID
    private long   receiveTime   = 0;    //数据包到达时间
    private short  status        = 0;    //响应状态
    private String serializeType = null; //序列化类型
    private String returnType    = null; //返回数据类型
    private Object returnData    = null; //返回数据

    public ResponsePayload() {
        super(Type.RESPONSE);
    }

    /**获取请求ID。*/
    public long getRequestID() {
        return this.requestID;
    }

    /**设置请求ID。*/
    public void setRequestID(long requestID) {
        this.requestID = requestID;
    }

    public void setReceiveTime(long receiveTime) {
        this.receiveTime = receiveTime;
    }

    /**数据包到达时间*/
    public long getReceiveTime() {
        return this.receiveTime;
    }

    /**
     * 获取响应状态
     * @see net.hasor.meteor.domain.ProtocolStatus
     */
    public short getStatus() {
        return this.status;
    }

    /**
     * 设置响应状态
     * @see net.hasor.meteor.domain.ProtocolStatus
     */
    public void setStatus(short status) {
        this.status = status;
    }

    /**获取序列化类型*/
    public String getSerializeType() {
        return this.serializeType;
    }

    /**设置序列化类型*/
    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    /**获取返回数据类型*/
    public String getReturnType() {
        return this.returnType;
    }

    /**设置返回数据类型*/
    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    /**获取返回值数据*/
    public Object getReturnData() {
        return this.returnData;
    }

    /**设置返回值数据*/
    public void setReturnData(Object returnData) {
        this.returnData = returnData;
    }
}
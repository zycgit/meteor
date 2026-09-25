/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
import java.util.ArrayList;
import java.util.List;

/**
 * RSF Request 的化身,是封装 Request 的数据对象。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RequestInfo extends OptionInfo {
    private long         requestID      = 0;    //请求ID
    private short        flags          = 0;    //标签
    private long         receiveTime    = 0;    //接收请求（本地时间戳）
    private String       serviceName    = null; //远程服务名
    private String       serviceGroup   = null; //远程服务分组
    private String       serviceVersion = null; //远程服务版本
    private String       targetMethod   = null; //远程服务方法名
    private String       serializeType  = null; //序列化策略
    private int          clientTimeout  = 0;    //远程调用时最大忍受等待时间
    private boolean      isMessage      = false;//是否为消息请求
    private List<String> paramTypes     = null; //参数类型
    private List<Object> paramValues    = null; //参数值

    public RequestInfo() {
        this.paramTypes = new ArrayList<>();
        this.paramValues = new ArrayList<>();
    }

    /**获取请求ID。*/
    public long getRequestID() {
        return this.requestID;
    }

    /**设置请求ID。*/
    public void setRequestID(long requestID) {
        this.requestID = requestID;
    }

    /**获取flag*/
    public short getFlags() {
        return this.flags;
    }

    /**设置flag。*/
    public void setFlags(short flags) {
        this.flags = flags;
    }

    /**数据包到达时间*/
    public void setReceiveTime(long receiveTime) {
        this.receiveTime = receiveTime;
    }

    /**数据包到达时间*/
    public long getReceiveTime() {
        return this.receiveTime;
    }

    /**获取服务名*/
    public String getServiceName() {
        return this.serviceName;
    }

    /**设置服务名*/
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**获取服务分组*/
    public String getServiceGroup() {
        return this.serviceGroup;
    }

    /**设置服务分组*/
    public void setServiceGroup(String serviceGroup) {
        this.serviceGroup = serviceGroup;
    }

    /**获取服务版本*/
    public String getServiceVersion() {
        return this.serviceVersion;
    }

    /**设置服务版本*/
    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    /**获取调用的方法名*/
    public String getTargetMethod() {
        return this.targetMethod;
    }

    /**设置调用的方法名*/
    public void setTargetMethod(String targetMethod) {
        this.targetMethod = targetMethod;
    }

    /**获取序列化类型*/
    public String getSerializeType() {
        return this.serializeType;
    }

    /**设置序列化类型*/
    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    /**获取远程客户端调用超时时间。*/
    public int getClientTimeout() {
        return this.clientTimeout;
    }

    /**设置远程客户端调用超时时间。*/
    public void setClientTimeout(int clientTimeout) {
        this.clientTimeout = clientTimeout;
    }

    /**是否为消息请求*/
    public boolean isMessage() {
        return this.isMessage;
    }

    /**设置是否为消息请求*/
    public void setMessage(boolean message) {
        this.isMessage = message;
    }
    //

    /**添加请求参数。*/
    public void addParameter(String paramType, Object paramValue) {
        this.paramTypes.add(paramType);
        this.paramValues.add(paramValue);
    }

    /**添加请求参数。*/
    public void updateParameter(int index, String paramType, Object paramValue) {
        if (index < 0 || index > this.paramTypes.size()) {
            throw new IndexOutOfBoundsException("index out of range 0~" + this.paramTypes.size());
        }
        this.paramTypes.set(index, paramType);
        this.paramValues.set(index, paramValue);
    }

    /**获取请求参数类型列表。*/
    public List<String> getParameterTypes() {
        return this.paramTypes;
    }

    /**获取请求参数值列表。*/
    public List<Object> getParameterValues() {
        return this.paramValues;
    }
}
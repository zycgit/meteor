/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import net.hasor.cobble.ArrayUtils;

/**
 * RSF 1.0 Request 二进制传输协议
 * --------------------------------------------------------bytes =13
 * byte[1]  version                              RSF版本(0xC1 or 0x81)
 * byte[8]  requestID                            请求ID
 * byte[1]  keepData                             保留区
 * byte[3]  contentLength                        内容大小(max ~ 16MB)
 * --------------------------------------------------------bytes =16
 * byte[2]  servicesName-(attr-index)            远程服务名
 * byte[2]  servicesGroup-(attr-index)           远程服务分组
 * byte[2]  servicesVersion-(attr-index)         远程服务版本
 * byte[2]  servicesMethod-(attr-index)          远程服务方法名
 * byte[2]  serializeTypeIndex-(attr-index)           序列化策略
 * byte[4]  clientTimeoutMillis                        远程客户端超时时间
 * byte[2]  flag                                 标记
 * --------------------------------------------------------bytes =1 ~ 1021
 * byte[1]  paramCount                           参数总数
 *     byte[4]  ptype-0-(attr-index,attr-index)  参数类型1
 *     byte[4]  ptype-1-(attr-index,attr-index)  参数类型2
 *     ...
 * --------------------------------------------------------bytes =1 ~ 1021
 * byte[1]  optionCount                          选项参数总数
 *     byte[4]  attr-0-(attr-index,attr-index)   选项参数1
 *     byte[4]  attr-1-(attr-index,attr-index)   选项参数2
 *     ...
 * --------------------------------------------------------bytes =6 ~ 8192
 * byte[2]  attrPool-size (0~4095)               池大小 0x0FFF
 *     byte[4] att-length                        属性1大小
 *     byte[4] att-length                        属性2大小
 *     ...
 * --------------------------------------------------------bytes =n
 * dataBody                                      数据内容
 *     bytes[...]
 *
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
class RsfV1RequestFrame extends RsfV1AttributePool {
    private byte  frameType           = 0;  // byte[1]  RSF头
    private long  requestID           = 0;  // byte[8]  请求ID
    private short flags               = 0;  // byte[2]  标记
    private short serviceNameIndex    = 0;  // byte[2]  远程服务名索引
    private short serviceGroupIndex   = 0;  // byte[2]  远程服务分组索引
    private short serviceVersionIndex = 0;  // byte[2]  远程服务版本
    private short targetMethodIndex   = 0;  // byte[2]  远程服务方法名
    private short serializeTypeIndex  = 0;  // byte[2]  序列化策略
    private int   clientTimeoutMillis = 0;  // byte[4]  远程客户端超时时间
    private int[] parameterMappings   = {}; // (attr-index,attr-index)
    private int[] optionMappings      = {}; // (attr-index,attr-index)

    /**
     * 获取帧类型。
     */
    byte getFrameType() {
        return this.frameType;
    }

    /**
     * 设置帧类型。
     */
    void setFrameType(byte frameType) {
        this.frameType = frameType;
    }

    /**
     * 获取请求ID。
     */
    long getRequestID() {
        return this.requestID;
    }

    /**
     * 设置请求ID。
     */
    void setRequestID(long requestID) {
        this.requestID = requestID;
    }

    /**
     * 获取flag
     */
    short getFlags() {
        return this.flags;
    }

    /**
     * 设置flag。
     */
    void setFlags(short flags) {
        this.flags = flags;
    }

    /**
     * 获取服务名
     */
    short getServiceNameIndex() {
        return this.serviceNameIndex;
    }

    /**
     * 设置服务名
     */
    void setServiceNameIndex(short serviceNameIndex) {
        this.serviceNameIndex = serviceNameIndex;
    }

    /**
     * 获取服务分组
     */
    short getServiceGroupIndex() {
        return this.serviceGroupIndex;
    }

    /**
     * 设置服务分组
     */
    void setServiceGroupIndex(short serviceGroupIndex) {
        this.serviceGroupIndex = serviceGroupIndex;
    }

    /**
     * 获取服务版本
     */
    short getServiceVersionIndex() {
        return this.serviceVersionIndex;
    }

    /**
     * 设置服务版本
     */
    void setServiceVersionIndex(short serviceVersionIndex) {
        this.serviceVersionIndex = serviceVersionIndex;
    }

    /**
     * 获取调用的方法名
     */
    short getTargetMethodIndex() {
        return this.targetMethodIndex;
    }

    /**
     * 设置调用的方法名
     */
    void setTargetMethodIndex(short targetMethodIndex) {
        this.targetMethodIndex = targetMethodIndex;
    }

    /**
     * 获取序列化类型
     */
    short getSerializeTypeIndex() {
        return this.serializeTypeIndex;
    }

    /**
     * 设置序列化类型
     */
    void setSerializeTypeIndex(short serializeTypeIndex) {
        this.serializeTypeIndex = serializeTypeIndex;
    }

    /**
     * 获取远程客户端调用超时时间。
     */
    int getClientTimeoutMillis() {
        return this.clientTimeoutMillis;
    }

    /**
     * 设置远程客户端调用超时时间。
     */
    void setClientTimeoutMillis(int clientTimeoutMillis) {
        this.clientTimeoutMillis = clientTimeoutMillis;
    }

    //

    /**
     * 添加请求参数。
     */
    void addParameterMapping(short typeIndex, short valueIndex) {
        int encodedType = typeIndex << 16;
        int packedMapping = encodedType | valueIndex;
        this.addParameterMapping(packedMapping);
    }

    /**
     * 添加请求参数。
     */
    void addParameterMapping(int packedMapping) {
        this.parameterMappings = ArrayUtils.add(this.parameterMappings, packedMapping);
    }

    /**
     * 获取请求参数类型列表。
     */
    int[] getParameterMappings() {
        return this.parameterMappings;
    }

    /**
     * 添加选项。
     */
    void addOptionMapping(short nameIndex, short valueIndex) {
        int encodedName = nameIndex << 16;
        int packedMapping = encodedName | valueIndex;
        this.addOptionMapping(packedMapping);
    }

    /**
     * 添加选项。
     */
    void addOptionMapping(int packedMapping) {
        this.optionMappings = ArrayUtils.add(this.optionMappings, packedMapping);
    }

    /**
     * 获取Option。
     */
    int[] getOptionMappings() {
        return this.optionMappings;
    }
}

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
 * RSF 1.0 Response 二进制传输协议
 * --------------------------------------------------------bytes =13
 * byte[1]  version                              RSF版本(0xC1 or 0x81)
 * byte[8]  requestID                            包含的请求ID
 * byte[1]  keepData                             保留区
 * byte[3]  contentLength                        内容大小(max ~ 16MB)
 * --------------------------------------------------------bytes =6
 * byte[2]  status                               响应状态
 * byte[2]  serializeTypeIndex-(attr-index)           序列化策略
 * byte[2]  returnTypeIndex-(attr-index)              返回数据类型
 * byte[2]  returnDataIndex-(attr-index)              返回数据
 * --------------------------------------------------------bytes =1 ~ 1021
 * byte[1]  optionCount                          选项参数总数
 *     byte[4]  attr-0-(attr-index,attr-index)   选项参数1
 *     byte[4]  attr-1-(attr-index,attr-index)   选项参数2
 *     ...
 * --------------------------------------------------------bytes =6 ~ 8192
 * byte[2]  attrPool-size (Max = 2047)           池大小
 *     byte[4] att-length                        属性1大小
 *     byte[4] att-length                        属性2大小
 *     ...
 * --------------------------------------------------------bytes =n
 * dataBody                                      数据内容
 *     bytes[...]
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
class RsfV1ResponseFrame extends RsfV1AttributePool {
    private byte  frameType          = 0;  // byte[1]  RSF头
    private long  requestID          = 0;  // byte[8]  请求ID
    private short status             = 0;  // byte[2]  响应状态
    private short serializeTypeIndex = 0;  // byte[2]  序列化类型
    private short returnTypeIndex    = 0;  // byte[2]  返回数据类型索引
    private short returnDataIndex    = 0;  // byte[2]  返回数据索引
    private int[] optionMappings     = {}; // (attr-index, attr-index)

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
     * 获取响应状态
     */
    short getStatus() {
        return this.status;
    }

    /**
     * 设置响应状态
     */
    void setStatus(short status) {
        this.status = status;
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
     * 获取返回值类型
     */
    short getReturnTypeIndex() {
        return this.returnTypeIndex;
    }

    /**
     * 设置返回值类型
     */
    void setReturnTypeIndex(short returnTypeIndex) {
        this.returnTypeIndex = returnTypeIndex;
    }

    /**
     * 获取返回值数据
     */
    short getReturnDataIndex() {
        return this.returnDataIndex;
    }

    /**
     * 设置返回值数据
     */
    void setReturnDataIndex(short returnDataIndex) {
        this.returnDataIndex = returnDataIndex;
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

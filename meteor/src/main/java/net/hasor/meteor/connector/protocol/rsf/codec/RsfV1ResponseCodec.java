/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.io.IOException;

/** Encodes and decodes RSF/1 response frames. */
class RsfV1ResponseCodec {

    /**
     * encode Message to byte & write to network framework
     */
    void encode(RsfV1ResponseFrame resMsg, RsfV1WireBuffer buf) throws IOException {
        //
        //* --------------------------------------------------------bytes =13
        //* byte[1]  frameType                            RSF帧类型
        buf.writeByte(resMsg.getFrameType());
        //* byte[8]  requestID                            请求ID
        buf.writeLong(resMsg.getRequestID());
        //* byte[1]  keepData                             保留区
        buf.writeByte(0);
        //* byte[3]  contentLength                        内容大小(max = 16MB)
        RsfV1WireBuffer responseBody = this.encodeBody(resMsg);
        try {
            int bodyLength = responseBody.readableBytes();
            if (bodyLength > 0xFFFFFF) {
                //左移8未，在无符号右移8位。形成最大16777215字节的限制。
                throw new IOException("RSF frame exceeds 24-bit length");
            }
            buf.writeMedium(bodyLength);
            //
            buf.writeBytes(responseBody);
        } finally {
            responseBody.release();
        }
        //
    }

    private RsfV1WireBuffer encodeBody(RsfV1ResponseFrame resMsg) {
        RsfV1WireBuffer bodyBuf = RsfV1WireBuffer.output();
        //
        //* --------------------------------------------------------bytes =8
        //* byte[2]  status                               响应状态
        bodyBuf.writeShort(resMsg.getStatus());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        bodyBuf.writeShort(resMsg.getSerializeTypeIndex());
        //* byte[2]  returnType-(attr-index)              返回数据类型
        bodyBuf.writeShort(resMsg.getReturnTypeIndex());
        //* byte[2]  returnData-(attr-index)              返回数据
        bodyBuf.writeShort(resMsg.getReturnDataIndex());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int[] optionMapping = resMsg.getOptionMappings();
        if (optionMapping.length > 255) {
            throw new IllegalArgumentException("Too many RSF options");
        }
        bodyBuf.writeByte(optionMapping.length);
        for (int i = 0; i < optionMapping.length; i++) {
            //* byte[4]  ptype-0-(attr-index,attr-index)  选项参数1
            //* byte[4]  ptype-1-(attr-index,attr-index)  选项参数2
            bodyBuf.writeInt(optionMapping[i]);
        }
        //* --------------------------------------------------------bytes =n
        //* dataBody                                      数据池
        resMsg.writeTo(bodyBuf);
        return bodyBuf;
    }

    /**
     * decode stream to object
     */
    RsfV1ResponseFrame decode(RsfV1WireBuffer buf) throws IOException {
        //* --------------------------------------------------------bytes =13
        //* byte[1]  frameType                            RSF帧类型
        byte frameType = buf.readByte();
        //* byte[8]  requestID                            包含的请求ID
        long requestID = buf.readLong();
        //* byte[1]  keepData                             保留区
        buf.skipBytes(1);
        //* byte[3]  contentLength                        内容大小
        //.readUnsignedMedium()
        buf.skipBytes(3);
        //
        RsfV1ResponseFrame res = new RsfV1ResponseFrame();
        res.setFrameType(frameType);
        res.setRequestID(requestID);
        //* --------------------------------------------------------bytes =8
        //* byte[2]  status                               响应状态
        res.setStatus(buf.readShort());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        res.setSerializeTypeIndex(buf.readShort());
        //* byte[2]  returnType-(attr-index)              返回数据类型
        res.setReturnTypeIndex(buf.readShort());
        //* byte[2]  returnData-(attr-index)              返回数据
        res.setReturnDataIndex(buf.readShort());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int optionCount = buf.readUnsignedByte();
        for (int i = 0; i < optionCount; i++) {
            //* byte[4]  attr-0-(attr-index,attr-index)   选项参数
            int mergeData = buf.readInt();
            res.addOptionMapping(mergeData);
        }
        //* --------------------------------------------------------bytes =n
        //* dataBody                                      数据池
        res.readFrom(buf);
        return res;
    }
}

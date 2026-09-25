/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.rsf.v1;
import java.io.IOException;
import net.hasor.rsf.protocol.rsf.Protocol;
import net.hasor.rsf.protocol.rsf.WireBuffer;

/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RpcResponseProtocolV1 implements Protocol<ResponseBlock> {
    /**encode Message to byte & write to network framework*/
    public void encode(ResponseBlock resMsg, WireBuffer buf) throws IOException {
        //
        //* --------------------------------------------------------bytes =13
        //* byte[1]  version                              RSF版本(0x81)
        buf.writeByte(resMsg.getHead());
        //* byte[8]  requestID                            请求ID
        buf.writeLong(resMsg.getRequestID());
        //* byte[1]  keepData                             保留区
        buf.writeByte(0);
        //* byte[3]  contentLength                        内容大小(max = 16MB)
        WireBuffer responseBody = this.encodeResponse(resMsg);
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

    private WireBuffer encodeResponse(ResponseBlock resMsg) {
        WireBuffer bodyBuf = new WireBuffer();
        //
        //* --------------------------------------------------------bytes =8
        //* byte[2]  status                               响应状态
        bodyBuf.writeShort(resMsg.getStatus());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        bodyBuf.writeShort(resMsg.getSerializeType());
        //* byte[2]  returnType-(attr-index)              返回数据类型
        bodyBuf.writeShort(resMsg.getReturnType());
        //* byte[2]  returnData-(attr-index)              返回数据
        bodyBuf.writeShort(resMsg.getReturnData());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int[] optionMapping = resMsg.getOptions();
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
        resMsg.fillTo(bodyBuf);
        return bodyBuf;
    }

    /**decode stream to object*/
    public ResponseBlock decode(WireBuffer buf) throws IOException {
        //* --------------------------------------------------------bytes =13
        //* byte[1]  version                              RSF版本
        byte version = buf.readByte();
        //* byte[8]  requestID                            包含的请求ID
        long requestID = buf.readLong();
        //* byte[1]  keepData                             保留区
        buf.skipBytes(1);
        //* byte[3]  contentLength                        内容大小
        buf.skipBytes(3);//.readUnsignedMedium()
        //
        ResponseBlock res = new ResponseBlock();
        res.setHead(version);
        res.setRequestID(requestID);
        //* --------------------------------------------------------bytes =8
        //* byte[2]  status                               响应状态
        res.setStatus(buf.readShort());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        res.setSerializeType(buf.readShort());
        //* byte[2]  returnType-(attr-index)              返回数据类型
        res.setReturnType(buf.readShort());
        //* byte[2]  returnData-(attr-index)              返回数据
        res.setReturnData(buf.readShort());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int optionCount = buf.readUnsignedByte();
        for (int i = 0; i < optionCount; i++) {
            //* byte[4]  attr-0-(attr-index,attr-index)   选项参数
            int mergeData = buf.readInt();
            res.addOption(mergeData);
        }
        //* --------------------------------------------------------bytes =n
        //* dataBody                                      数据池
        res.fillFrom(buf);
        return res;
    }
}

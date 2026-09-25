/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.rsf.v1;
import net.hasor.rsf.protocol.rsf.WireBuffer;
import net.hasor.rsf.protocol.rsf.Protocol;


import java.io.IOException;

/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RpcRequestProtocolV1 implements Protocol<RequestBlock> {
    /**encode Message to byte & write to network framework*/
    public void encode(RequestBlock reqMsg, WireBuffer buf) throws IOException {
        //* --------------------------------------------------------bytes =13
        //* byte[1]  version                              RSF版本
        buf.writeByte(reqMsg.getHead());
        //* byte[8]  requestID                            请求ID
        buf.writeLong(reqMsg.getRequestID());
        //* byte[1]  keepData                             保留区
        buf.writeByte(0);
        //* byte[3]  contentLength                        内容大小(max = 16MB)
        //
        WireBuffer requestBody = this.encodeRequest(reqMsg);
        try {
            int bodyLength = requestBody.readableBytes();
            if (bodyLength > 0xFFFFFF) {
                throw new IOException("RSF frame exceeds 24-bit length");
            }//左移8未，在无符号右移8位。形成最大16777215字节的限制。
            buf.writeMedium(bodyLength);
            //
            buf.writeBytes(requestBody);
        } finally {
            requestBody.release();
        }
    }

    private WireBuffer encodeRequest(RequestBlock reqMsg) {
        WireBuffer bodyBuf = new WireBuffer();
        //* --------------------------------------------------------bytes =14
        //* byte[2]  servicesName-(attr-index)            远程服务名
        bodyBuf.writeShort(reqMsg.getServiceName());
        //* byte[2]  servicesGroup-(attr-index)           远程服务分组
        bodyBuf.writeShort(reqMsg.getServiceGroup());
        //* byte[2]  servicesVersion-(attr-index)         远程服务版本
        bodyBuf.writeShort(reqMsg.getServiceVersion());
        //* byte[2]  servicesMethod-(attr-index)          远程服务方法名
        bodyBuf.writeShort(reqMsg.getTargetMethod());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        bodyBuf.writeShort(reqMsg.getSerializeType());
        //* byte[4]  clientTimeout                        远程客户端超时时间
        bodyBuf.writeInt(reqMsg.getClientTimeout());
        //* byte[2]  flag                                 标签
        bodyBuf.writeShort(reqMsg.getFlags());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  paramCount                           参数总数
        int[] paramMapping = reqMsg.getParameters();
        if (paramMapping.length > 255) {
            throw new IllegalArgumentException("Too many RSF parameters");
        }
        bodyBuf.writeByte(paramMapping.length);
        for (int i = 0; i < paramMapping.length; i++) {
            //* byte[4]  ptype-0-(attr-index,attr-index)  参数类型1
            //* byte[4]  ptype-1-(attr-index,attr-index)  参数类型2
            bodyBuf.writeInt(paramMapping[i]);
        }
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int[] optionMapping = reqMsg.getOptions();
        if (optionMapping.length > 255) {
            throw new IllegalArgumentException("Too many RSF options");
        }
        bodyBuf.writeByte(optionMapping.length);
        for (int i = 0; i < optionMapping.length; i++) {
            //* byte[4]  ptype-0-(attr-index,attr-index)  选项参数1
            //* byte[4]  ptype-1-(attr-index,attr-index)  选项参数2
            bodyBuf.writeInt(optionMapping[i]);
        }
        //* --------------------------------------------------------数据池
        //* dataBody                                      数据池
        reqMsg.fillTo(bodyBuf);
        return bodyBuf;
    }

    /**decode stream to object*/
    public RequestBlock decode(WireBuffer buf) throws IOException {
        //* --------------------------------------------------------bytes =13
        //* byte[1]  version                              RSF版本(0xC1)
        byte rsfHead = buf.readByte();
        //* byte[8]  requestID                            包含的请求ID
        long requestID = buf.readLong();
        //* byte[1]  keepData                             保留区
        buf.skipBytes(1);
        //* byte[3]  contentLength                        内容大小
        buf.skipBytes(3);//.readUnsignedMedium()
        //
        RequestBlock req = new RequestBlock();
        req.setHead(rsfHead);
        req.setRequestID(requestID);
        //* --------------------------------------------------------bytes =16
        //* byte[2]  servicesName-(attr-index)            远程服务名
        req.setServiceName(buf.readShort());
        //* byte[2]  servicesGroup-(attr-index)           远程服务分组
        req.setServiceGroup(buf.readShort());
        //* byte[2]  servicesVersion-(attr-index)         远程服务版本
        req.setServiceVersion(buf.readShort());
        //* byte[2]  servicesMethod-(attr-index)          远程服务方法名
        req.setTargetMethod(buf.readShort());
        //* byte[2]  serializeType-(attr-index)           序列化策略
        req.setSerializeType(buf.readShort());
        //* byte[4]  clientTimeout                        远程客户端超时时间
        req.setClientTimeout(buf.readInt());
        //* byte[2]  flag                                 标签
        req.setFlags(buf.readShort());
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  paramCount                           参数总数
        int paramCount = buf.readUnsignedByte();
        for (int i = 0; i < paramCount; i++) {
            //* byte[4]  ptype-0-(attr-index,attr-index)  参数类型
            int mergeData = buf.readInt();
            req.addParameter(mergeData);
        }
        //* --------------------------------------------------------bytes =1 ~ 1021
        //* byte[1]  optionCount                          选项参数总数
        int optionCount = buf.readUnsignedByte();
        for (int i = 0; i < optionCount; i++) {
            //* byte[4]  attr-0-(attr-index,attr-index)   选项参数
            int mergeData = buf.readInt();
            req.addOption(mergeData);
        }
        //* --------------------------------------------------------bytes =n
        //* dataBody                                      数据池
        req.fillFrom(buf);
        return req;
    }
}

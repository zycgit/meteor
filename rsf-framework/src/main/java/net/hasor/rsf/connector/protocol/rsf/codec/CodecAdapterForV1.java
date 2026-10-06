/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf.codec;
import java.io.IOException;
import java.util.List;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.serialize.SerializeCoder;

/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年11月4日
 * @author 赵永春(zyc @ hasor.net)
 */
public class CodecAdapterForV1 implements CodecAdapter, ProtocolConstants {

    private final RsfContext  context;
    private final ClassLoader classLoader;

    public CodecAdapterForV1(RsfContext context) {
        this.context = context;
        this.classLoader = context.getClassLoader();
    }

    public byte[] encode(Payload message) throws IOException {
        PoolBlock block = null;
        try {
            WireBuffer output = new WireBuffer();
            if (message instanceof RequestPayload request) {
                block = this.buildRequestBlock(request);
                this.writeRequestBlock((RequestBlock) block, output);
            } else if (message instanceof ResponsePayload response) {
                block = this.buildResponseBlock(response);
                this.writeResponseBlock((ResponseBlock) block, output);
            } else {
                throw new IOException("Unsupported RSF message");
            }
            return output.toByteArray();
        } finally {
            if (block != null) {
                block.release();
            }
        }
    }

    public Payload decode(byte[] frame) throws Exception {
        try {
            WireBuffer input = new WireBuffer(frame);
            if (frame[0] == RSF_InvokerRequest || frame[0] == RSF_MessageRequest) {
                RequestPayload request = this.readRequestPayload(input);
                request.setReceiveTime(System.currentTimeMillis());
                return request;
            }

            if (frame[0] == RSF_Response) {
                ResponsePayload response = this.readResponsePayload(input);
                response.setReceiveTime(System.currentTimeMillis());
                return response;
            }
            throw new IOException("Unsupported RSF header: " + frame[0]);
        } catch (Exception error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("Cannot decode RSF frame", error);
        }
    }

    @Override
    public RequestBlock buildRequestBlock(RequestPayload info) throws IOException {
        RequestBlock block = new RequestBlock();
        if (info.isMessage()) {
            block.setHead(RSF_MessageRequest);
        } else {
            block.setHead(RSF_InvokerRequest);
        }

        //1.基本信息
        //请求ID
        block.setRequestID(info.getRequestID());
        block.setFlags(info.getFlags());
        block.setServiceGroup(pushString(block, info.getServiceGroup()));
        block.setServiceName(pushString(block, info.getServiceName()));
        block.setServiceVersion(pushString(block, info.getServiceVersion()));
        block.setTargetMethod(pushString(block, info.getTargetMethod()));
        block.setSerializeType(pushString(block, info.getSerializeType()));
        block.setClientTimeout(info.getClientTimeout());

        //2.params
        List<String> pTypes = info.getParameterTypes();
        List<Object> pValues = info.getParameterValues();
        if ((pTypes != null && !pTypes.isEmpty()) && (pValues != null && !pValues.isEmpty())) {
            SerializeCoder coder = this.context.getSerializeCoder(info.getSerializeType());
            for (int i = 0; i < pTypes.size(); i++) {
                String typeKey = pTypes.get(i);
                Object value = pValues.get(i);
                byte[] valKey = (coder != null) ? coder.encode(value) : new byte[0];

                short paramType = pushString(block, typeKey);
                short paramData = block.pushData(valKey);
                block.addParameter(paramType, paramData);
            }
        }

        //3.Opt参数
        String[] optKeys = info.getOptionKeys();
        if (optKeys.length > 0) {
            for (int i = 0; i < optKeys.length; i++) {
                short optKey = pushString(block, optKeys[i]);
                short optVal = pushString(block, info.getOption(optKeys[i]));
                block.addOption(optKey, optVal);
            }
        }

        return block;
    }

    @Override
    public ResponseBlock buildResponseBlock(ResponsePayload info) throws IOException {
        ResponseBlock block = new ResponseBlock();

        //1.基本信息
        block.setHead(RSF_Response);
        //请求ID
        block.setRequestID(info.getRequestID());
        //序列化策略
        block.setSerializeType(pushString(block, info.getSerializeType()));

        //2.returnData
        String returnType = info.getReturnType();
        SerializeCoder serializeCoder = this.context.getSerializeCoder(info.getSerializeType());
        byte[] encode = (serializeCoder != null) ? serializeCoder.encode(info.getReturnData()) : new byte[0];
        block.setReturnType(pushString(block, returnType));
        block.setReturnData(block.pushData(encode));
        //响应状态
        block.setStatus(info.getStatus());

        //3.Opt参数
        String[] optKeys = info.getOptionKeys();
        for (String optKey1 : optKeys) {
            short optKey = pushString(block, optKey1);
            short optVal = pushString(block, info.getOption(optKey1));
            block.addOption(optKey, optVal);
        }

        return block;
    }

    private static short pushString(PoolBlock block, String value) {
        return block.pushData(WireStrings.fromCache(value));
    }

    private final Protocol<RequestBlock>  requestProtocol  = new RpcRequestProtocolV1();
    private final Protocol<ResponseBlock> responseProtocol = new RpcResponseProtocolV1();

    @Override
    public void writeRequestBlock(RequestBlock block, WireBuffer out) throws IOException {
        this.requestProtocol.encode(block, out);
    }

    @Override
    public RequestPayload readRequestPayload(WireBuffer frame) throws Throwable {
        RequestBlock rsfBlock = this.requestProtocol.decode(frame);
        RequestPayload info = new RequestPayload();
        try {
            //1.基本数据
            info.setRequestID(rsfBlock.getRequestID());
            info.setFlags(rsfBlock.getFlags());
            short serializeTypeInt = rsfBlock.getSerializeType();
            String serializeType = WireStrings.fromCache(rsfBlock.readPool(serializeTypeInt));
            info.setSerializeType(serializeType);

            //2.Message
            if (rsfBlock.getHead() == RSF_InvokerRequest) {
                info.setMessage(false);
            }
            if (rsfBlock.getHead() == RSF_MessageRequest) {
                info.setMessage(true);
            }

            //3.Opt参数
            int[] optionArray = rsfBlock.getOptions();
            if (optionArray.length > 0) {
                for (int optItem : optionArray) {
                    short optKey = (short) (optItem >>> 16);
                    short optVal = (short) (optItem & PoolBlock.PoolMaxSize);
                    String optKeyStr = WireStrings.fromCache(rsfBlock.readPool(optKey));
                    String optValStr = WireStrings.fromCache(rsfBlock.readPool(optVal));
                    info.addOption(optKeyStr, optValStr);
                }
            }

            //4.Request
            String serviceGroup = WireStrings.fromCache(rsfBlock.readPool(rsfBlock.getServiceGroup()));
            String serviceName = WireStrings.fromCache(rsfBlock.readPool(rsfBlock.getServiceName()));
            String serviceVersion = WireStrings.fromCache(rsfBlock.readPool(rsfBlock.getServiceVersion()));
            String targetMethod = WireStrings.fromCache(rsfBlock.readPool(rsfBlock.getTargetMethod()));
            int clientTimeout = rsfBlock.getClientTimeout();
            info.setServiceGroup(serviceGroup);
            info.setServiceName(serviceName);
            info.setServiceVersion(serviceVersion);
            info.setTargetMethod(targetMethod);
            info.setClientTimeout(clientTimeout);

            int[] paramDatas = rsfBlock.getParameters();
            SerializeCoder serializeCoder = this.context.getSerializeCoder(serializeType);
            if (paramDatas.length > 0) {
                for (int i = 0; i < paramDatas.length; i++) {
                    int paramItem = paramDatas[i];
                    short paramKey = (short) (paramItem >>> 16);
                    short paramVal = (short) (paramItem & PoolBlock.PoolMaxSize);
                    byte[] keyData = rsfBlock.readPool(paramKey);
                    byte[] valData = rsfBlock.readPool(paramVal);

                    String paramType = WireStrings.fromCache(keyData);
                    Object paramObj = null;
                    if (serializeCoder != null && StringUtils.isNotBlank(paramType)) {
                        paramObj = serializeCoder.decode(valData, RsfRuntimeUtils.getType(paramType, this.classLoader));
                    }
                    info.addParameter(paramType, paramObj);
                }
            }
        } finally {
            if (rsfBlock != null) {
                rsfBlock.release();
            }
        }
        return info;
    }

    @Override
    public void writeResponseBlock(ResponseBlock block, WireBuffer out) throws IOException {
        this.responseProtocol.encode(block, out);
    }

    @Override
    public ResponsePayload readResponsePayload(WireBuffer frame) throws Throwable {
        ResponseBlock rsfBlock = this.responseProtocol.decode(frame);
        ResponsePayload info = new ResponsePayload();
        try {
            //1.基本数据
            info.setRequestID(rsfBlock.getRequestID());
            short serializeTypeInt = rsfBlock.getSerializeType();
            String serializeType = WireStrings.fromCache(rsfBlock.readPool(serializeTypeInt));
            info.setSerializeType(serializeType);

            //2.Opt参数
            int[] optionArray = rsfBlock.getOptions();
            for (int optItem : optionArray) {
                short optKey = (short) (optItem >>> 16);
                short optVal = (short) (optItem & PoolBlock.PoolMaxSize);
                String optKeyStr = WireStrings.fromCache(rsfBlock.readPool(optKey));
                String optValStr = WireStrings.fromCache(rsfBlock.readPool(optVal));
                info.addOption(optKeyStr, optValStr);
            }

            //3.Response
            info.setStatus(rsfBlock.getStatus());
            SerializeCoder serializeCoder = this.context.getSerializeCoder(serializeType);
            String returnType = WireStrings.fromCache(rsfBlock.readPool(rsfBlock.getReturnType()));
            info.setReturnType(returnType);
            byte[] returnByte = rsfBlock.readPool(rsfBlock.getReturnData());
            Object returnData = null;
            if (serializeCoder != null && StringUtils.isNotBlank(returnType)) {
                returnData = serializeCoder.decode(returnByte, ClassUtils.getClass(this.classLoader, returnType, false));
            }
            info.setReturnData(returnData);
        } finally {
            if (rsfBlock != null) {
                rsfBlock.release();
            }
        }
        return info;
    }
}

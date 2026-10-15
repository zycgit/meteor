/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.serialize.SerializeCoder;

/** Converts Meteor payloads to and from complete RSF/1 frames. */
public final class RsfV1Codec {
    private static final byte RSF_INVOKER_REQUEST = (byte) 0x91;
    private static final byte RSF_MESSAGE_REQUEST = (byte) 0xa1;
    private static final byte RSF_RESPONSE        = (byte) 0xb1;

    private final MetContext         context;
    private final ClassLoader        classLoader;
    private final RsfV1RequestCodec  requestCodec  = new RsfV1RequestCodec();
    private final RsfV1ResponseCodec responseCodec = new RsfV1ResponseCodec();

    public RsfV1Codec(MetContext context) {
        this.context = context;
        this.classLoader = context.getClassLoader();
    }

    public byte[] encode(Payload payload) throws IOException {
        RsfV1AttributePool frame = null;
        try {
            RsfV1WireBuffer output = RsfV1WireBuffer.output();
            if (payload instanceof RequestPayload request) {
                frame = this.buildRequestFrame(request);
                this.requestCodec.encode((RsfV1RequestFrame) frame, output);
            } else if (payload instanceof ResponsePayload response) {
                frame = this.buildResponseFrame(response);
                this.responseCodec.encode((RsfV1ResponseFrame) frame, output);
            } else {
                throw new IOException("Unsupported RSF message");
            }
            return output.toByteArray();
        } finally {
            if (frame != null) {
                frame.release();
            }
        }
    }

    public Payload decode(byte[] frameBytes) throws Exception {
        try {
            RsfV1WireBuffer input = RsfV1WireBuffer.input(frameBytes);
            if (frameBytes[0] == RSF_INVOKER_REQUEST || frameBytes[0] == RSF_MESSAGE_REQUEST) {
                RequestPayload request = this.decodeRequestPayload(input);
                request.setReceiveTime(System.currentTimeMillis());
                return request;
            }

            if (frameBytes[0] == RSF_RESPONSE) {
                ResponsePayload response = this.decodeResponsePayload(input);
                response.setReceiveTime(System.currentTimeMillis());
                return response;
            }
            throw new IOException("Unsupported RSF header: " + frameBytes[0]);
        } catch (Exception error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("Cannot decode RSF frame", error);
        }
    }

    private RsfV1RequestFrame buildRequestFrame(RequestPayload payload) throws IOException {
        RsfV1RequestFrame frame = new RsfV1RequestFrame();
        if (payload.isMessage()) {
            frame.setFrameType(RSF_MESSAGE_REQUEST);
        } else {
            frame.setFrameType(RSF_INVOKER_REQUEST);
        }

        // 基本信息。
        frame.setRequestID(payload.getRequestID());
        frame.setFlags(payload.getFlags());
        frame.setServiceGroupIndex(addString(frame, payload.getServiceGroup()));
        frame.setServiceNameIndex(addString(frame, payload.getServiceName()));
        frame.setServiceVersionIndex(addString(frame, payload.getServiceVersion()));
        frame.setTargetMethodIndex(addString(frame, payload.getTargetMethod()));
        frame.setSerializeTypeIndex(addString(frame, payload.getSerializeType()));
        frame.setClientTimeoutMillis(payload.getClientTimeout());

        // 调用参数。
        List<String> parameterTypes = payload.getParameterTypes();
        List<Object> parameterValues = payload.getParameterValues();
        boolean hasTypes = parameterTypes != null && !parameterTypes.isEmpty();
        boolean hasValues = parameterValues != null && !parameterValues.isEmpty();
        if (hasTypes && hasValues) {
            SerializeCoder coder = this.context.getSerializeCoder(payload.getSerializeType());
            if (coder == null) {
                throw new IOException("Unsupported serialization type: " + payload.getSerializeType());
            }

            for (int i = 0; i < parameterTypes.size(); i++) {
                String parameterType = parameterTypes.get(i);
                Object parameterValue = parameterValues.get(i);
                byte[] encodedValue = coder.encode(parameterValue);

                short typeIndex = addString(frame, parameterType);
                short valueIndex = frame.addValue(encodedValue);
                frame.addParameterMapping(typeIndex, valueIndex);
            }
        }

        // 调用选项。
        String[] optionNames = payload.getOptionKeys();
        for (String optionName : optionNames) {
            short nameIndex = addString(frame, optionName);
            short valueIndex = addString(frame, payload.getOption(optionName));
            frame.addOptionMapping(nameIndex, valueIndex);
        }

        return frame;
    }

    private RsfV1ResponseFrame buildResponseFrame(ResponsePayload payload) throws IOException {
        RsfV1ResponseFrame frame = new RsfV1ResponseFrame();

        // 基本信息。
        frame.setFrameType(RSF_RESPONSE);
        frame.setRequestID(payload.getRequestID());
        frame.setSerializeTypeIndex(addString(frame, payload.getSerializeType()));

        // 响应值。
        String returnType = payload.getReturnType();
        SerializeCoder serializeCoder = this.context.getSerializeCoder(payload.getSerializeType());
        if (serializeCoder == null && (StringUtils.isNotBlank(returnType) || payload.getReturnData() != null)) {
            throw new IOException("Unsupported serialization type: " + payload.getSerializeType());
        }

        byte[] encodedReturnData;
        if (serializeCoder != null) {
            encodedReturnData = serializeCoder.encode(payload.getReturnData());
        } else {
            encodedReturnData = new byte[0];
        }
        frame.setReturnTypeIndex(addString(frame, returnType));
        frame.setReturnDataIndex(frame.addValue(encodedReturnData));
        frame.setStatus(payload.getStatus());

        // 响应选项。
        String[] optionNames = payload.getOptionKeys();
        for (String optionName : optionNames) {
            short nameIndex = addString(frame, optionName);
            short valueIndex = addString(frame, payload.getOption(optionName));
            frame.addOptionMapping(nameIndex, valueIndex);
        }

        return frame;
    }

    private static short addString(RsfV1AttributePool pool, String value) {
        return pool.addValue(encodeString(value));
    }

    private RequestPayload decodeRequestPayload(RsfV1WireBuffer input) throws Throwable {
        RsfV1RequestFrame requestFrame = this.requestCodec.decode(input);
        RequestPayload request = new RequestPayload();
        try {
            // 基本信息。
            request.setRequestID(requestFrame.getRequestID());
            request.setFlags(requestFrame.getFlags());
            short serializeTypeIndex = requestFrame.getSerializeTypeIndex();
            String serializeType = decodeString(requestFrame.getValue(serializeTypeIndex));
            request.setSerializeType(serializeType);

            // 调用模式。
            if (requestFrame.getFrameType() == RSF_INVOKER_REQUEST) {
                request.setMessage(false);
            }
            if (requestFrame.getFrameType() == RSF_MESSAGE_REQUEST) {
                request.setMessage(true);
            }

            // 调用选项。
            int[] encodedOptions = requestFrame.getOptionMappings();
            for (int encodedOption : encodedOptions) {
                short nameIndex = (short) (encodedOption >>> 16);
                short valueIndex = (short) (encodedOption & RsfV1AttributePool.MAX_VALUE_COUNT);
                String optionName = decodeString(requestFrame.getValue(nameIndex));
                String optionValue = decodeString(requestFrame.getValue(valueIndex));
                request.addOption(optionName, optionValue);
            }

            // 服务目标。
            String serviceGroup = decodeString(requestFrame.getValue(requestFrame.getServiceGroupIndex()));
            String serviceName = decodeString(requestFrame.getValue(requestFrame.getServiceNameIndex()));
            String serviceVersion = decodeString(requestFrame.getValue(requestFrame.getServiceVersionIndex()));
            String targetMethod = decodeString(requestFrame.getValue(requestFrame.getTargetMethodIndex()));
            request.setServiceGroup(serviceGroup);
            request.setServiceName(serviceName);
            request.setServiceVersion(serviceVersion);
            request.setTargetMethod(targetMethod);
            request.setClientTimeout(requestFrame.getClientTimeoutMillis());

            // 调用参数。
            int[] encodedParameters = requestFrame.getParameterMappings();
            SerializeCoder serializeCoder = this.context.getSerializeCoder(serializeType);
            for (int encodedParameter : encodedParameters) {
                short typeIndex = (short) (encodedParameter >>> 16);
                short valueIndex = (short) (encodedParameter & RsfV1AttributePool.MAX_VALUE_COUNT);
                String parameterType = decodeString(requestFrame.getValue(typeIndex));
                byte[] parameterData = requestFrame.getValue(valueIndex);

                Object parameterValue = null;
                if (serializeCoder != null && StringUtils.isNotBlank(parameterType)) {
                    Class<?> valueType = MetRuntimeUtils.getType(parameterType, this.classLoader);
                    parameterValue = serializeCoder.decode(parameterData, valueType);
                }
                request.addParameter(parameterType, parameterValue);
            }
        } finally {
            requestFrame.release();
        }
        return request;
    }

    private ResponsePayload decodeResponsePayload(RsfV1WireBuffer input) throws Throwable {
        RsfV1ResponseFrame responseFrame = this.responseCodec.decode(input);
        ResponsePayload response = new ResponsePayload();
        try {
            // 基本信息。
            response.setRequestID(responseFrame.getRequestID());
            short serializeTypeIndex = responseFrame.getSerializeTypeIndex();
            String serializeType = decodeString(responseFrame.getValue(serializeTypeIndex));
            response.setSerializeType(serializeType);

            // 响应选项。
            int[] encodedOptions = responseFrame.getOptionMappings();
            for (int encodedOption : encodedOptions) {
                short nameIndex = (short) (encodedOption >>> 16);
                short valueIndex = (short) (encodedOption & RsfV1AttributePool.MAX_VALUE_COUNT);
                String optionName = decodeString(responseFrame.getValue(nameIndex));
                String optionValue = decodeString(responseFrame.getValue(valueIndex));
                response.addOption(optionName, optionValue);
            }

            // 响应值。
            response.setStatus(responseFrame.getStatus());
            SerializeCoder serializeCoder = this.context.getSerializeCoder(serializeType);
            String returnType = decodeString(responseFrame.getValue(responseFrame.getReturnTypeIndex()));
            response.setReturnType(returnType);
            byte[] encodedReturnData = responseFrame.getValue(responseFrame.getReturnDataIndex());
            Object returnValue = null;
            boolean canDecode = serializeCoder != null && StringUtils.isNotBlank(returnType);
            if (canDecode && !StringUtils.equals("void", returnType)) {
                Class<?> resultType = ClassUtils.getClass(this.classLoader, returnType, false);
                returnValue = serializeCoder.decode(encodedReturnData, resultType);
            }
            response.setReturnData(returnValue);
        } finally {
            responseFrame.release();
        }
        return response;
    }

    private static byte[] encodeString(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeString(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}

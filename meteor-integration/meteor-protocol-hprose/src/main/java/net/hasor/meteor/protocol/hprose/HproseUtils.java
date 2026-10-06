/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import hprose.io.HproseReader;
import hprose.io.HproseTags;
import hprose.io.HproseWriter;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/**
 * Hprose 工具
 * @version : 2017年1月28日
 * @author 赵永春 (zyc@hasor.net)
 */
public class HproseUtils implements HproseConstants {
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /***/
    public static String[] doFunction(MetContext context) {
        Set<String> allMethod = new LinkedHashSet<>();
        allMethod.add("*");

        // .请求函数列表
        List<String> serviceIDs = context.getServiceIDs();
        for (String serviceID : serviceIDs) {
            MetBindInfo<?> serviceInfo = context.getServiceInfo(serviceID);
            if (serviceInfo.isShadow() || MetServiceType.Provider != serviceInfo.getServiceType()) {
                continue;
            }

            String aliasName = serviceInfo.getAliasName(HPROSE);
            if (StringUtils.isBlank(aliasName)) {
                continue;
            }

            Method[] methodArrays = serviceInfo.getBindType().getMethods();
            for (Method method : methodArrays) {
                StringBuilder define = new StringBuilder();
                define = define.append(aliasName);
                define = define.append("_");
                define = define.append(method.getName());
                allMethod.add(define.toString());
            }
        }
        return allMethod.toArray(new String[allMethod.size()]);
    }

    /***/
    public static RequestPayload[] doCall(MetContext context, InputStream content, String requestURI, String origin) throws MetException, IOException {
        HproseReader reader = new HproseReader(content);
        List<RequestPayload> infoArrays = new ArrayList<RequestPayload>();
        parseRequest(context, reader, infoArrays);
        content.skip(content.available());
        for (RequestPayload info : infoArrays) {
            info.addOption("Location", requestURI);
            info.addOption("Origin", origin);
        }

        return infoArrays.toArray(new RequestPayload[infoArrays.size()]);
    }

    /***/
    private static void parseRequest(MetContext context, HproseReader reader, List<RequestPayload> infoArrays) throws IOException {
        String callName = null;
        try {
            callName = reader.readString();
            reader.reset();
        } catch (IOException e) {
            throw new MetException(ProtocolStatus.ProtocolError, "decode callName error -> " + e.getMessage());
        }

        // 创建 RequestPayload 对象
        MetBindInfo<?> serviceInfo = null;
        RequestPayload request = new RequestPayload();
        try {
            String[] lastParams = callName.split("_");
            String methodName = lastParams[lastParams.length - 1];
            String serviceID = callName.substring(0, callName.length() - methodName.length() - 1);

            serviceInfo = context.getServiceInfo(HPROSE, serviceID);
            if (serviceInfo == null) {
                throw new MetException(ProtocolStatus.NotFound, "serviceID not found in alias. -> " + serviceID);
            }

            request.setServiceGroup(serviceInfo.getBindGroup());
            request.setServiceName(serviceInfo.getBindName());
            request.setServiceVersion(serviceInfo.getBindVersion());
            request.setTargetMethod(methodName);
            request.setMessage(false);
            request.setSerializeType("Hprose");
            request.setClientTimeout(context.getSettings().getDefaultTimeout());
            request.setReceiveTime(System.currentTimeMillis());
        } catch (Exception e) {
            if (e instanceof MetException) {
                throw (MetException) e;
            }
            throw new MetException(ProtocolStatus.Unknown, "error(" + e.getClass() + ") -> " + e.getMessage());
        }
        // 确定方法
        int lastTag = 0;
        Method atMethod = null;
        Class<?>[] parameterTypes = null;
        byte[][] args = null;
        try {
            int argCount = 0;
            String methodName = request.getTargetMethod();
            lastTag = reader.checkTags(String.valueOf((char) TagList) + (char) TagEnd + (char) TagCall);
            if (lastTag == HproseTags.TagList) {
                reader.reset();
                argCount = reader.readInt(HproseTags.TagOpenbrace);
                args = new byte[argCount][];
                for (int i = 0; i < argCount; i++) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    reader.readRaw(out);
                    args[i] = out.toByteArray();
                }
                reader.readInt(HproseTags.TagClosebrace);
            }

            args = (args == null) ? new byte[0][] : args;
            Method[] allMethods = serviceInfo.getBindType().getMethods();
            for (Method method : allMethods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                parameterTypes = method.getParameterTypes();
                if (argCount != parameterTypes.length) {
                    continue;
                }
                atMethod = method;
                break;
            }

            if (atMethod == null) {
                throw new MetException(ProtocolStatus.NotFound, "serviceID : " + serviceInfo.getBindID() + " ,not found method " + methodName);
            }
        } catch (Exception e) {
            if (e instanceof MetException) {
                throw (MetException) e;
            }
            throw new MetException(ProtocolStatus.Unknown, "error(" + e.getClass() + ") -> " + e.getMessage());
        }

        // .参数处理(isRef是否为引用参数调用 (遇到引用参数方法，会在response时将请求参数一同返回给客户端)
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> paramType = parameterTypes[i];
            HproseReader paramDataReader = new HproseReader(args[i]);
            Object paramData = paramDataReader.unserialize(paramType);
            request.addParameter(paramType.getName(), paramData);
        }

        // .请求参数
        infoArrays.add(request);

        // .如果最后一个读取到的标签是结束标签那么结束整个解析，否则在读取一个标签。
        try {
            if (lastTag == TagEnd) {
                return;
            }
            lastTag = reader.checkTags(String.valueOf((char) TagTrue) + (char) TagEnd + (char) TagCall);
        } catch (Exception e) {
            if (e instanceof MetException) {
                throw (MetException) e;
            }
            throw new MetException(ProtocolStatus.SerializeError, "error(" + e.getClass() + ") reader.checkTags -> " + e.getMessage());
        }

        // .当读取的最后一个标签不是结束标签那么继续处理直到遇到结束标签
        if (lastTag == TagEnd) {
            return;
        }

        // .如果下一个标签还是一个call，表示当前请求是批量调用。
        if (lastTag == TagCall) {
            throw new MetException(ProtocolStatus.ProtocolError, "hprose batch calls, is not support.");
            //parseRequest(context, reader, infoArrays);
        }

        // .表示是参数引用调用，面对参数引用时候在响应时需要讲参数一同响应给客户端
        if (lastTag == TagTrue) {
            throw new MetException(ProtocolStatus.ProtocolError, "hprose ref param, is not support.");
        }
    }

    /***/
    public static void parseResponse(long requestID, ResponsePayload response, OutputStream output) throws IOException {
        if (response.getStatus() == ProtocolStatus.OK) {
            output.write(new byte[] { 'R' });
            ByteArrayOutputStream binary = new ByteArrayOutputStream();
            HproseWriter writer = new HproseWriter(binary);
            writer.serialize(response.getReturnData());
            byte[] encode = binary.toByteArray();
            output.write(encode);
            output.write(new byte[] { 'z' });
            //
        } else {
            Map<String, String> errorMsg = new HashMap<String, String>();
            String[] optionKeys = response.getOptionKeys();
            if (optionKeys != null) {
                for (String optKey : optionKeys) {
                    errorMsg.put(optKey, response.getOption(optKey));
                }
            }
            errorMsg.put("requestID", String.valueOf(requestID));
            errorMsg.put("status", String.valueOf(response.getStatus()));
            String jsonData = ERROR_MAPPER.writeValueAsString(errorMsg);
            output.write('E');
            new HproseWriter(output).writeString(jsonData);
            output.write('z');
        }
    }

    /***/
    public static byte[] encodeRequest(MetContext context, RequestPayload request) throws IOException {
        MetBindInfo<?> bindInfo = context.getServiceInfo(request.getServiceGroup(), request.getServiceName(), request.getServiceVersion());
        String aliasName = bindInfo.getAliasName(HPROSE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HproseWriter writer = new HproseWriter(out);
        writer.writeString(aliasName + "_" + request.getTargetMethod());

        writer.writeArray(request.getParameterValues().toArray());

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write('C');
        frame.write(out.toByteArray());
        frame.write('z');
        return frame.toByteArray();
    }

    public static Object decodeResponse(InputStream inputStream) throws IOException {
        int aByte = inputStream.read();
        if ((char) aByte == 'R') {
            HproseReader reader = new HproseReader(inputStream);
            Object value = reader.unserialize();
            if (inputStream.read() != 'z' || inputStream.read() != -1) {
                throw new IOException("Invalid Hprose response terminator");
            }
            return value;
        }

        if ((char) aByte == 'E') {
            throw new IOException("Remote Hprose error: " + new HproseReader(inputStream).readString());
        }
        throw new IOException("Invalid Hprose response tag: " + aByte);
    }
}

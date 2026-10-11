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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/**
 * Hprose 工具
 * @version : 2017年1月28日
 * @author 赵永春 (zyc@hasor.net)
 */
public final class HproseUtils implements HproseConstants {
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper()//
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)//
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private HproseUtils() {
    }

    /** 返回已发布服务的 Hprose 方法别名。 */
    public static String[] doFunction(MetContext context, ProtocolConfig protocol) {
        Set<String> allMethod = new LinkedHashSet<>();
        allMethod.add("*");

        // .请求函数列表
        List<String> serviceIDs = context.getServiceIDs();
        for (String serviceID : serviceIDs) {
            MetBindInfo<?> serviceInfo = context.getServiceInfo(serviceID);
            if (serviceInfo.isShadow() || MetServiceType.Provider != serviceInfo.getServiceType()) {
                continue;
            }

            if (!protocol.accepts(serviceInfo.getBindProtocols())) {
                continue;
            }
            String aliasName = serviceInfo.getAliasName(HPROSE);
            if (StringUtils.isBlank(aliasName)) {
                continue;
            }

            Method[] methodArrays = MetRuntimeUtils.getServiceMethods(serviceInfo.getBindType());
            for (Method method : methodArrays) {
                allMethod.add(aliasName + "_" + method.getName());
            }
        }
        return allMethod.toArray(new String[allMethod.size()]);
    }

    /** 解析单个调用；批量调用和引用参数返回尚不支持。 */
    public static RequestPayload doCall(MetContext context, InputStream content, String requestURI, String origin) throws IOException {
        HproseReader reader = new HproseReader(content);
        String callName = reader.readString();
        int separator = callName == null ? -1 : callName.lastIndexOf('_');
        if (separator <= 0 || separator == callName.length() - 1) {
            throw new MetException(ProtocolStatus.ProtocolError, "Invalid Hprose call name: " + callName);
        }

        // 先确定服务与参数个数，再使用同一个 reader 解析完整参数列表，保留参数间的引用。
        String alias = callName.substring(0, separator);
        MetBindInfo<?> service = context.getServiceInfo(HPROSE, alias);
        while (service == null && (separator = callName.lastIndexOf('_', separator - 1)) > 0) {
            alias = callName.substring(0, separator);
            service = context.getServiceInfo(HPROSE, alias);
        }
        if (service == null) {
            throw new MetException(ProtocolStatus.NotFound, "Hprose service alias not found: " + callName);
        }
        String methodName = callName.substring(separator + 1);
        int tag = reader.checkTags("azC");
        int count = 0;
        if (tag == TagList) {
            reader.reset();
            count = reader.readInt(TagOpenbrace);
        }
        Method method = findMethod(service, methodName, count);
        Object[] arguments = new Object[count];
        if (tag == TagList) {
            reader.readArray(method.getGenericParameterTypes(), arguments, count);
            tag = reader.checkTags("tzC");
        }
        if (tag != TagEnd) {
            throw new MetException(ProtocolStatus.ProtocolError, "Hprose batch calls and reference returns are unsupported");
        }
        if (content.read() != -1) {
            throw new MetException(ProtocolStatus.ProtocolError, "Unexpected data after Hprose call");
        }

        // 只向 RPC 层交付已完整解析并校验的请求。
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(methodName);
        request.setSerializeType(HPROSE);
        request.setClientTimeout(context.getSettings().getDefaultTimeout());
        request.setReceiveTime(System.currentTimeMillis());
        request.addOption("Location", requestURI);
        request.addOption("Origin", origin);
        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < count; index++) {
            request.addParameter(types[index].getName(), arguments[index]);
        }
        return request;
    }

    private static Method findMethod(MetBindInfo<?> service, String name, int count) {
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (StringUtils.equals(method.getName(), name) && method.getParameterCount() == count) {
                return method;
            }
        }
        throw new MetException(ProtocolStatus.NotFound, "Hprose method not found: " + name + "/" + count);
    }

    /** 解析 Hprose 错误字符串中的 JSON 扩展信息。 */
    public static JsonNode parseError(String error) throws IOException {
        return ERROR_MAPPER.readTree(error);
    }

    /** 写入结果，或包含状态码及响应选项的错误报文。 */
    public static void parseResponse(long requestID, ResponsePayload response, OutputStream output) throws IOException {
        if (response.getStatus() == ProtocolStatus.OK) {
            output.write('R');
            new HproseWriter(output).serialize(response.getReturnData());
            output.write('z');
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

    /** 通过已注册服务的 Hprose 别名编码单个请求。 */
    public static byte[] encodeRequest(MetContext context, RequestPayload request) throws IOException {
        MetBindInfo<?> bindInfo = context.getServiceInfo(request.getServiceGroup(), request.getServiceName(), request.getServiceVersion());
        String aliasName = bindInfo.getAliasName(HPROSE);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write('C');
        HproseWriter writer = new HproseWriter(frame);
        writer.writeString(aliasName + "_" + request.getTargetMethod());
        writer.reset();
        writer.writeArray(request.getParameterValues().toArray());
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

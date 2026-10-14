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
import net.hasor.meteor.connector.ContextPaths;
import net.hasor.meteor.connector.ProtocolConfig;
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

    /** 返回默认挂载点上已发布服务的 Hprose 方法别名。 */
    public static String[] doFunction(MetContext context, ProtocolConfig protocol) {
        return doFunction(context, protocol, protocol.contextPath());
    }

    /** 返回协议已声明目录挂载点上的方法别名；精确入口使用 Mount 重载。 */
    public static String[] doFunction(MetContext context, ProtocolConfig protocol, String contextPath) {
        return doFunction(context, protocol, protocol.contexts().resolve(contextPath));
    }

    /** 返回当前请求实际挂载点上已发布服务的 Hprose 方法别名。 */
    public static String[] doFunction(MetContext context, ProtocolConfig protocol, ContextPaths.Mount mount) {
        Set<String> allMethod = new LinkedHashSet<>();

        // .请求函数列表
        List<String> serviceIDs = context.getServiceIDs();
        for (String serviceID : serviceIDs) {
            MetBindInfo<?> serviceInfo = context.getServiceInfo(serviceID);
            if (serviceInfo == null || serviceInfo.isShadow()) {
                continue;
            }
            if (MetServiceType.Provider != serviceInfo.getServiceType()) {
                continue;
            }

            if (!protocol.accepts(serviceInfo, mount)) {
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
            Map<String, String> errorMsg = new HashMap<>();
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

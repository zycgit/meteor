/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.net.URI;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.xml.rpc.ParameterMode;
import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.axis.encoding.XMLType;
import org.apache.axis.soap.SOAPConstants;

/** 标准 Axis RPC/encoded 客户端，不依赖 Meteor 类型与编码器。 */
final class AxisPeerClient {
    private AxisPeerClient() {
    }

    static Map<String, Object> invoke(Map<String, Object> request) throws Exception {
        String namespace = (String) request.get("namespace");
        String version = (String) request.get("version");
        SOAPConstants constants = switch (version) {
            case "1.1" -> SOAPConstants.SOAP11_CONSTANTS;
            case "1.2" -> SOAPConstants.SOAP12_CONSTANTS;
            default -> throw new IllegalArgumentException("Unknown Axis SOAP version: " + version);
        };

        // 保留原有 SDK 配置，由 Axis 产生完整 RPC/encoded 消息并执行真实 HTTP。
        Call call = (Call) new Service().createCall();
        call.setSOAPVersion(constants);
        URI endpoint = URI.create((String) request.get("endpoint"));
        call.setTargetEndpointAddress(endpoint.toURL());
        call.setOperationName(new QName(namespace, "echo"));
        call.setOperationStyle("rpc");
        call.setOperationUse("encoded");
        call.setEncodingStyle(constants.getEncodingURI());
        call.setUseSOAPAction(true);
        call.setSOAPActionURI(namespace + "/echo");
        call.addParameter(new QName("", "arg0"), XMLType.XSD_STRING, String.class, ParameterMode.IN);
        call.setReturnType(XMLType.XSD_STRING, String.class);
        call.setReturnQName(new QName("", "return"));
        call.setTimeout(5000);
        Object result = call.invoke(new Object[] { request.get("value") });
        return Map.of("value", result);
    }
}

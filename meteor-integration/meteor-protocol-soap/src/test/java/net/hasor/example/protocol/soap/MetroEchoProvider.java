/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.*;
import org.w3c.dom.NodeList;

/** 标准 Metro 对端实现，用于验证 Meteor 客户端的外部 SOAP 互通。 */
@WebServiceProvider(//
        serviceName = "EchoService", //
        portName = "EchoPort", //
        targetNamespace = "urn:meteor:test:echo")
@ServiceMode(Service.Mode.MESSAGE)
public final class MetroEchoProvider implements Provider<SOAPMessage> {
    private static final String ECHO_TYPES_NAMESPACE = "urn:meteor:test:echo:types";

    @Override
    public SOAPMessage invoke(SOAPMessage request) {
        try {
            // 读取 WSDL document/literal wrapped 请求参数。
            NodeList parameters = request.getSOAPBody().getElementsByTagNameNS(//
                    ECHO_TYPES_NAMESPACE, "message");
            if (parameters.getLength() != 1) {
                throw new WebServiceException("Echo request must contain one message parameter");
            }

            // 返回同一 WSDL 声明的响应包装和结果。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            SOAPMessage response = factory.createMessage();
            QName responseName = new QName(ECHO_TYPES_NAMESPACE, "echoResponse", "types");
            SOAPBodyElement body = response.getSOAPBody().addBodyElement(responseName);
            QName resultName = new QName(ECHO_TYPES_NAMESPACE, "result", "types");
            body.addChildElement(resultName).addTextNode(parameters.item(0).getTextContent());
            response.saveChanges();
            return response;
        } catch (WebServiceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new WebServiceException("Unable to build Echo response", failure);
        }
    }
}

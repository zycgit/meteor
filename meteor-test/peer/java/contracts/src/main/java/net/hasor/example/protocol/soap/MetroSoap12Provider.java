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

/** 使用标准 Metro SOAP 1.2 binding 的独立回显服务。 */
@WebServiceProvider(serviceName = "EchoService", portName = "EchoServicePort", //
        targetNamespace = "urn:hasor:meteor:soap:EchoService")
@ServiceMode(Service.Mode.MESSAGE)
public final class MetroSoap12Provider implements Provider<SOAPMessage> {
    @Override
    public SOAPMessage invoke(SOAPMessage request) {
        try {
            // 读取直接位于操作包装下的普通 Java 参数。
            NodeList arguments = request.getSOAPBody().getElementsByTagNameNS("", "arg0");
            if (arguments.getLength() != 1) {
                throw new WebServiceException("Echo request must contain one arg0 parameter");
            }

            // 响应由 Metro 的 SOAP 1.2 工厂和 HTTP binding 产生。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
            SOAPMessage response = factory.createMessage();
            QName name = new QName("urn:hasor:meteor:soap:EchoService", "echoResponse", "service");
            SOAPBodyElement wrapper = response.getSOAPBody().addBodyElement(name);
            wrapper.addChildElement(new QName("return")).addTextNode(arguments.item(0).getTextContent());
            response.saveChanges();
            return response;
        } catch (Exception failure) {
            throw new WebServiceException("Unable to process SOAP 1.2 Echo request", failure);
        }
    }
}

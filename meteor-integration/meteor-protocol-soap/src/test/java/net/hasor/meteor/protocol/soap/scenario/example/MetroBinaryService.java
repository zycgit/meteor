/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.bind.annotation.XmlMimeType;

/** 由 Metro 处理 document/literal SOAP 与 MTOM 二进制优化的服务契约。 */
@WebService(name = "BinaryServicePortType", targetNamespace = "urn:hasor:meteor:soap:BinaryService")
@SOAPBinding(style = SOAPBinding.Style.DOCUMENT)
public interface MetroBinaryService {
    @WebMethod(operationName = "echo", action = "urn:hasor:meteor:soap:BinaryService/echo")
    @WebResult(name = "return", targetNamespace = "")
    @XmlMimeType("application/octet-stream")
    byte[] echo(@WebParam(name = "arg0", targetNamespace = "") @XmlMimeType("application/octet-stream") byte[] data);
}

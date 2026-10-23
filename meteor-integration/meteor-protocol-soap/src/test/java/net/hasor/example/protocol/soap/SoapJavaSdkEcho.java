/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

/** 由 Metro 读取的本地标准 Java SEI，对应普通 EchoService 发布的 RPC/literal 契约。 */
@WebService(name = "EchoServicePortType", targetNamespace = "urn:hasor:meteor:soap:EchoService")
@SOAPBinding(style = SOAPBinding.Style.RPC)
public interface SoapJavaSdkEcho {
    @WebMethod(operationName = "echo", action = "urn:hasor:meteor:soap:EchoService/echo")
    @WebResult(name = "return", targetNamespace = "")
    String echo(@WebParam(name = "arg0", targetNamespace = "") String value);
}
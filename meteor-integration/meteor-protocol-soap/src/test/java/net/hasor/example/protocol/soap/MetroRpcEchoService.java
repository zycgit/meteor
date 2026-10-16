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

/** 由 Metro 解释标准注解的 RPC/literal 服务契约。 */
@WebService(name = "RpcEchoServicePortType", targetNamespace = "urn:hasor:meteor:soap:RpcEchoService")
@SOAPBinding(style = SOAPBinding.Style.RPC)
public interface MetroRpcEchoService {
    @WebMethod(operationName = "echo", action = "urn:hasor:meteor:soap:RpcEchoService/echo")
    @WebResult(name = "return", targetNamespace = "")
    String echo(@WebParam(name = "arg0", targetNamespace = "") String message);
}

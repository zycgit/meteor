/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.metro;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

/** 使用标准 JAX-WS 接口消费 Meteor 生成的命名空间和 XML 属性契约。 */
@WebService(name = "MetXmlOrderServicePortType", targetNamespace = MetroMetXmlOrderService.NAMESPACE)
@SOAPBinding(style = SOAPBinding.Style.DOCUMENT)
public interface MetroMetXmlOrderService {
    String NAMESPACE = "urn:hasor:meteor:soap:MetXmlOrderService";

    @WebMethod(operationName = "echo", action = NAMESPACE + "/echo")
    @WebResult(name = "result", targetNamespace = NAMESPACE)
    MetroMetXmlOrder echo(@WebParam(name = "order", targetNamespace = NAMESPACE) MetroMetXmlOrder value);
}

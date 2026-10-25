/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.soap;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;

/** 标准 JAX-WS 契约，与 Meteor 订单服务使用相同线上名称和字段。 */
@WebService(name = "ordersPortType", targetNamespace = MetroOrderService.NAMESPACE)
public interface MetroOrderService {
    String NAMESPACE = "urn:hasor:meteor:soap:orders";

    @WebMethod(operationName = "save", action = NAMESPACE + "/save")
    @WebResult(name = "return", targetNamespace = "")
    MetroOrder save(@WebParam(name = "arg0", targetNamespace = "") MetroOrder order);

    @WebMethod(operationName = "find", action = NAMESPACE + "/find")
    @WebResult(name = "return", targetNamespace = "")
    MetroOrder find(@WebParam(name = "arg0", targetNamespace = "") MetroOrderQuery query);
}

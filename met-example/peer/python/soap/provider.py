#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 soapfish2 发布订单服务及 WSDL。"""

from wsgiref.simple_server import make_server

from soapfish import soap, soap_dispatch, xsd

NAMESPACE = "urn:hasor:meteor:soap:orders"


class Order(xsd.ComplexType):
    id = xsd.Element(xsd.Int)
    description = xsd.Element(xsd.String, minOccurs=0, nillable=True)
    attachment = xsd.Element(xsd.Base64Binary, minOccurs=0, nillable=True)


class OrderQuery(xsd.ComplexType):
    id = xsd.Element(xsd.Int)


class Save(xsd.ComplexType):
    arg0 = xsd.Element(Order)


class Find(xsd.ComplexType):
    arg0 = xsd.Element(OrderQuery)


class OrderResponse(xsd.ComplexType):
    value = xsd.Element(Order, tagname="return")


def application():
    # 描述 SOAP 请求、响应和订单类型，由 SDK 生成 WSDL。
    schema = xsd.Schema(
        targetNamespace=NAMESPACE,
        elementFormDefault=xsd.ElementFormDefault.UNQUALIFIED,
        complexTypes=[Order, OrderQuery, Save, Find, OrderResponse],
        elements={
            "save": xsd.Element(Save),
            "saveResponse": xsd.Element(OrderResponse),
            "find": xsd.Element(Find),
            "findResponse": xsd.Element(OrderResponse),
        },
    )
    orders = {}

    def save(request, body):
        orders[body.arg0.id] = body.arg0
        return OrderResponse(value=body.arg0)

    def find(request, body):
        return OrderResponse(value=orders[body.arg0.id])

    service = soap.Service(
        name="orders",
        targetNamespace=NAMESPACE,
        location="http://127.0.0.1:8080/soap/orders",
        schemas=[schema],
        methods=[
            xsd.Method("save", NAMESPACE + "/save", "save", "saveResponse", save),
            xsd.Method("find", NAMESPACE + "/find", "find", "findResponse", find),
        ],
    )
    dispatcher = soap_dispatch.SOAPDispatcher(service)
    return soap_dispatch.WsgiSoapApplication(dispatcher)


def main():
    # 发布固定地址，WSDL 位于服务地址后的 ?wsdl。
    with make_server("127.0.0.1", 8080, application()) as server:
        print("Order provider is ready: http://127.0.0.1:8080/soap/orders?wsdl", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Zeep consumers and soapfish2 providers use SOAP SDKs; JSON lines only control processes."""

import argparse
import base64
import json
import logging
from pathlib import Path
import sys
import threading
from wsgiref.simple_server import make_server

from soapfish import core, soap, soap11, soap_dispatch, xsd
import requests
import zeep
from zeep.transports import Transport

NAMESPACE = "urn:hasor:meteor:soap:orders"
ENDPOINT_PATH = "/soap/orders"


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


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
    # The SDK builds XSD/WSDL, validates SOAP input and encodes all SOAP responses.
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
        # Keep native bytes in the business store; SDK model fields use base64 lexical values.
        value = body.arg0
        orders[value.id] = {
            "id": value.id,
            "description": value.description,
            "attachment": base64.b64decode(value.attachment),
        }
        return response(orders[value.id])

    def find(request, body):
        if body.arg0.id not in orders:
            raise core.SOAPError(soap11.Code.SERVER, "Order not found: " + str(body.arg0.id))
        return response(orders[body.arg0.id])

    service = soap.Service(
        name="orders", targetNamespace=NAMESPACE,
        location="${scheme}://${host}" + ENDPOINT_PATH,
        schemas=[schema],
        methods=[
            xsd.Method("save", NAMESPACE + "/save", "save", "saveResponse", save),
            xsd.Method("find", NAMESPACE + "/find", "find", "findResponse", find),
        ],
    )
    return soap_dispatch.SOAPDispatcher(service)


def response(value):
    # Each reply is a new SDK model, matching OrderDemoServiceImpl's copy semantics.
    order = Order(
        id=value["id"], description=value["description"],
        attachment=base64.b64encode(value["attachment"]).decode("ascii"),
    )
    return OrderResponse(value=order)


def provide():
    dispatcher = application()
    server = make_server("127.0.0.1", 0, soap_dispatch.WsgiSoapApplication(dispatcher))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    emit({"ready": True, "role": "provider", "port": server.server_port})
    try:
        for line in sys.stdin:
            if json.loads(line)["operation"] == "quit":
                return
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def consume():
    # No business URL is passed at startup; direct or discovered WSDL comes from the control command.
    emit({"ready": True, "role": "consumer"})
    for line in sys.stdin:
        command = json.loads(line)
        if command["operation"] == "quit":
            return
        try:
            with requests.Session() as session:
                client = zeep.Client(
                    command["wsdl"], transport=Transport(session=session, timeout=10, operation_timeout=10),
                )
                expected = command["order"]
                order = dict(expected)
                order["attachment"] = base64.b64decode(order["attachment"])
                client.service.save(arg0=order)
                actual = client.service.find(arg0={"id": order["id"]})
                emit({"result": {
                    "id": actual.id, "description": actual.description,
                    "attachment": base64.b64encode(actual.attachment).decode("ascii"),
                }})
        except Exception as failure:
            emit({"error": str(failure)})


def export_contract(destination):
    # Persist the provider SDK's generated contract, not a handwritten substitute WSDL.
    dispatcher = application()
    content = dispatcher.wsdl.decode().replace("${scheme}", "http").replace("${host}", "127.0.0.1")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(content, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("consumer", "provider", "contract"))
    parser.add_argument("--output", type=Path)
    options = parser.parse_args()
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr)
    if options.role == "provider":
        provide()
    elif options.role == "consumer":
        consume()
    else:
        export_contract(options.output)


if __name__ == "__main__":
    main()

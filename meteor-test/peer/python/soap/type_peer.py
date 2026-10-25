#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Native Python type vectors over independent Zeep and soapfish2 SOAP SDKs."""

import argparse
import base64
import enum
import json
import logging
from pathlib import Path
import struct
import sys
import threading
from wsgiref.simple_server import make_server

from lxml import etree
import requests
from soapfish import soap, soap_dispatch, xsd
import zeep
from zeep.plugins import HistoryPlugin
from zeep.transports import Transport

NAMESPACE = "urn:hasor:meteor:soap:types"
ENDPOINT_PATH = "/soap/types"
VECTOR_PATH = Path(__file__).resolve().parents[3] / "src/test/resources/vectors/types/values.json"
MAP_KEYS = ("alpha", "beta", "minimum", "maximum")


class TypeKind(enum.Enum):
    FIRST = "FIRST"
    SECOND = "SECOND"


class Kind(xsd.String):
    enumeration = [member.value for member in TypeKind]


class TypeRecord(xsd.ComplexType):
    index = xsd.Element(xsd.Int)
    name = xsd.Element(xsd.String)


class StringList(xsd.ComplexType):
    items = xsd.ListElement(xsd.String, "item", minOccurs=0, maxOccurs=xsd.UNBOUNDED)


class LongMap(xsd.ComplexType):
    # This contract declares the four keys used by the shared vectors; arbitrary keys are not claimed.
    alpha = xsd.Element(xsd.Long, minOccurs=0)
    beta = xsd.Element(xsd.Long, minOccurs=0)
    minimum = xsd.Element(xsd.Long, minOccurs=0)
    maximum = xsd.Element(xsd.Long, minOccurs=0)


class TypeValue(xsd.ComplexType):
    caseId = xsd.Element(xsd.String)
    booleanValue = xsd.Element(xsd.Boolean)
    int8Value = xsd.Element(xsd.Byte)
    int16Value = xsd.Element(xsd.Short)
    int32Value = xsd.Element(xsd.Int)
    int64Value = xsd.Element(xsd.Long)
    float32Value = xsd.Element(xsd.Float)
    float64Value = xsd.Element(xsd.Double)
    textValue = xsd.Element(xsd.String)
    binaryValue = xsd.Element(xsd.Base64Binary)
    enumValue = xsd.Element(Kind)
    listValue = xsd.Element(StringList)
    mapValue = xsd.Element(LongMap)
    recordValue = xsd.Element(TypeRecord)
    optionalValue = xsd.Element(xsd.String, minOccurs=0, nillable=True)
    receipt = xsd.Element(xsd.String)
    unsigned64Value = xsd.Element(xsd.UnsignedLong)


class Echo(xsd.ComplexType):
    request = xsd.Element(TypeValue)


class EchoResponse(xsd.ComplexType):
    result = xsd.Element(TypeValue, tagname="return")


def emit(value):
    print(json.dumps(value, ensure_ascii=False, allow_nan=False), flush=True)


def vector(case_id):
    # JSON only supplies inputs; each business field is restored before entering an RPC SDK.
    table = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
    entry = table["vectors"][case_id]
    value = dict(table["defaults"])
    value[entry["field"]] = entry["value"]
    value["caseId"] = case_id
    value["receipt"] = "client:" + case_id
    for field in ("int8Value", "int16Value", "int32Value", "int64Value", "unsigned64Value"):
        value[field] = int(value[field])
    value["float32Value"] = struct.unpack("!f", struct.pack("!f", float(value["float32Value"])))[0]
    value["float64Value"] = float(value["float64Value"])
    value["binaryValue"] = base64.b64decode(value["binaryValue"])
    value["enumValue"] = TypeKind(value["enumValue"])
    value["listValue"] = list(value["listValue"])
    value["mapValue"] = {key: int(item) for key, item in value["mapValue"].items()}
    value["recordValue"] = dict(value["recordValue"])
    return entry["field"], value


def verify_native(field, expected, actual):
    # Type checks are independent from JSON equality: bool is not accepted as int, and bytes stay bytes.
    value = actual[field]
    reference = expected[field]
    if field == "enumValue":
        assert isinstance(value, TypeKind), type(value)
    elif field in ("int8Value", "int16Value", "int32Value", "int64Value", "unsigned64Value"):
        assert type(value) is int, type(value)
    elif field in ("float32Value", "float64Value"):
        assert type(value) is float, type(value)
        width = "!f" if field == "float32Value" else "!d"
        assert struct.pack(width, value) == struct.pack(width, reference), (value, reference)
    elif reference is not None:
        assert type(value) is type(reference), (type(value), type(reference))
    assert value == reference, (field, value, reference)


def sdk_value(value):
    # soapfish models describe XML shape; the service itself reads native Python collections and numbers.
    fields = dict(value)
    fields["binaryValue"] = base64.b64encode(value["binaryValue"]).decode("ascii")
    fields["enumValue"] = value["enumValue"].value
    fields["listValue"] = StringList(items=list(value["listValue"]))
    fields["mapValue"] = LongMap(**value["mapValue"])
    fields["recordValue"] = TypeRecord(**value["recordValue"])
    if value["optionalValue"] is None:
        fields["optionalValue"] = xsd.NIL
    return TypeValue(**fields)


def native_value(value, zeep_result=False, optional_nil=False):
    fields = {
        name: getattr(value, name)
        for name in (
            "caseId", "booleanValue", "int8Value", "int16Value", "int32Value", "int64Value",
            "float32Value", "float64Value", "textValue", "optionalValue", "receipt", "unsigned64Value",
        )
    }
    # XSD float is binary32; Python's float stores binary64, so restore the declared width once.
    fields["float32Value"] = struct.unpack("!f", struct.pack("!f", fields["float32Value"]))[0]
    # Empty XML simple content has the empty lexical value even when the SDK exposes None.
    if fields["textValue"] is None:
        fields["textValue"] = ""
    binary = value.binaryValue
    if binary is None:
        binary = b"" if zeep_result else ""
    fields["binaryValue"] = binary if zeep_result else base64.b64decode(binary)
    fields["enumValue"] = TypeKind(value.enumValue)
    if value.listValue is None:
        fields["listValue"] = []
    else:
        fields["listValue"] = list(value.listValue.item if zeep_result else value.listValue.items)
    fields["mapValue"] = {
        key: getattr(value.mapValue, key)
        for key in MAP_KEYS if value.mapValue is not None and getattr(value.mapValue, key) is not None
    }
    record_name = value.recordValue.name
    fields["recordValue"] = {"index": value.recordValue.index, "name": "" if record_name is None else record_name}
    if fields["optionalValue"] is xsd.NIL or optional_nil:
        fields["optionalValue"] = None
    elif fields["optionalValue"] is None:
        fields["optionalValue"] = ""
    return fields


def application():
    # The provider SDK generates and validates the complete WSDL/XSD before listening.
    schema = xsd.Schema(
        targetNamespace=NAMESPACE,
        elementFormDefault=xsd.ElementFormDefault.UNQUALIFIED,
        simpleTypes=[Kind],
        complexTypes=[TypeRecord, StringList, LongMap, TypeValue, Echo, EchoResponse],
        elements={"echo": xsd.Element(Echo), "echoResponse": xsd.Element(EchoResponse)},
    )

    def echo(request, body):
        actual = native_value(body.request)
        field, expected = vector(actual["caseId"])
        verify_native(field, expected, actual)
        actual["receipt"] = "accepted:" + actual["caseId"]
        return EchoResponse(result=sdk_value(actual))

    service = soap.Service(
        name="types", targetNamespace=NAMESPACE,
        location="${scheme}://${host}" + ENDPOINT_PATH,
        schemas=[schema],
        methods=[xsd.Method("echo", NAMESPACE + "/echo", "echo", "echoResponse", echo)],
    )
    return soap_dispatch.SOAPDispatcher(service)


def provide():
    server = make_server("0.0.0.0", 0, soap_dispatch.WsgiSoapApplication(application()))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    emit({"ready": True, "port": server.server_port})
    try:
        for line in sys.stdin:
            if json.loads(line)["operation"] == "quit":
                return
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def consume():
    emit({"ready": True})
    for line in sys.stdin:
        command = json.loads(line)
        if command["operation"] == "quit":
            return
        history = None
        try:
            # Zeep owns contract loading and all SOAP I/O; JSON lines never carry SOAP envelopes.
            field, expected = vector(command["vector"])
            with requests.Session() as session:
                transport = Transport(session=session, timeout=10, operation_timeout=10)
                history = HistoryPlugin()
                client = zeep.Client(command["wsdl"], transport=transport, plugins=[history])
                request = dict(expected)
                request["enumValue"] = expected["enumValue"].value
                request["listValue"] = {"item": expected["listValue"]}
                if request["optionalValue"] is None:
                    request["optionalValue"] = zeep.xsd.Nil
                response = client.service.echo(request=request)

                # Zeep exposes both empty string and xsi:nil as None; only XML presence resolves that ambiguity.
                envelope = history.last_received["envelope"]
                optional_nodes = envelope.xpath('.//*[local-name()="optionalValue"]')
                assert len(optional_nodes) == 1, "SOAP response must contain the optionalValue element"
                nil_value = optional_nodes[0].get("{http://www.w3.org/2001/XMLSchema-instance}nil")
                optional_nil = nil_value in ("true", "1")
                actual = native_value(response, zeep_result=True, optional_nil=optional_nil)

            # Compare actual SDK values in Python before preparing a JSON control result for Java.
            verify_native(field, expected, actual)
            assert actual["receipt"] == "accepted:" + command["vector"], actual["receipt"]
            actual["binaryValue"] = base64.b64encode(actual["binaryValue"]).decode("ascii")
            actual["enumValue"] = actual["enumValue"].value
            emit({"result": actual})
        except Exception as failure:
            logging.exception("SOAP type vector failed")
            sent = None
            received = None
            if history is not None:
                try:
                    sent = history.last_sent
                    received = history.last_received
                except IndexError:
                    pass
            if sent is not None:
                request_xml = etree.tostring(sent["envelope"], encoding="unicode")
                logging.error("SOAP type request: %s", request_xml)
            if received is not None:
                response_xml = etree.tostring(received["envelope"], encoding="unicode")
                logging.error("SOAP type response: %s", response_xml)
            emit({"error": type(failure).__name__ + ": " + str(failure)})


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
        # Keep the generated SDK contract alongside its runtime, separate from the existing order contract.
        content = application().wsdl.decode().replace("${scheme}", "http").replace("${host}", "127.0.0.1")
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()

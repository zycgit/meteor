#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Restore shared vectors to Python types and compare each contract field exactly."""

import base64
import copy
import json
import struct
from pathlib import Path


def vector(path, name):
    # JSON is an input fixture only; SDK calls receive native numbers, bytes and structures.
    table = json.loads(Path(path).read_text(encoding="utf-8"))
    selected = table["vectors"][name]
    value = copy.deepcopy(table["defaults"])
    value[selected["field"]] = selected["value"]
    value["caseId"] = name
    value["receipt"] = "client:" + name
    value["int64Value"] = int(value["int64Value"])
    value["unsigned64Value"] = int(value["unsigned64Value"])
    value["float32Value"] = struct.unpack("!f", struct.pack("!f", float(value["float32Value"])))[0]
    value["float64Value"] = float(value["float64Value"])
    value["binaryValue"] = base64.b64decode(value["binaryValue"], validate=True)
    value["mapValue"] = {key: int(item) for key, item in value["mapValue"].items()}
    value["enumValue"] = {"FIRST": 0, "SECOND": 1}[value["enumValue"]]
    return selected["type"], value


def assert_value(expected, actual, receipt, type_name):
    # Each JUnit vector proves its selected business type plus the provider-generated receipt.
    fields = {
        "boolean": "booleanValue", "int8": "int8Value", "int16": "int16Value",
        "int32": "int32Value", "int64": "int64Value", "uint64": "unsigned64Value",
        "uint8": "unsigned64Value", "uint16": "unsigned64Value", "uint32": "unsigned64Value",
        "float32": "float32Value", "float64": "float64Value", "text": "textValue",
        "binary": "binaryValue", "enum": "enumValue", "list": "listValue",
        "map": "mapValue", "record": "recordValue", "optional": "optionalValue",
    }
    field = fields[type_name]
    item = actual[field]
    if type_name in ("int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64", "enum"):
        assert type(item) is int, (field, type(item))
    elif type_name == "boolean":
        assert type(item) is bool, type(item)
    elif type_name == "binary":
        assert type(item) is bytes, type(item)
    elif type_name == "text":
        assert type(item) is str, type(item)
    elif type_name == "list":
        assert type(item) is list and all(type(value) is str for value in item), item
    elif type_name == "map":
        assert type(item) is dict and all(type(value) is int for value in item.values()), item
    elif type_name == "record":
        assert type(item["index"]) is int and type(item["name"]) is str, item
    elif type_name == "optional":
        assert item is None or type(item) is str, type(item)

    # Python has one integer type; widths describe explicit protocol bounds, not native Python uint types.
    if type_name.startswith("uint"):
        width = int(type_name[4:])
        assert 0 <= item <= (1 << width) - 1, (type_name, item)

    # Binary float comparison retains signed zero and the minimum subnormal values.
    if type_name in ("float32", "float64"):
        encoding = "!f" if type_name == "float32" else "!d"
        assert type(item) is float, (field, type(item))
        assert struct.pack(encoding, item) == struct.pack(encoding, expected[field]), field
    else:
        assert item == expected[field], (field, item, expected[field])
    assert actual["caseId"] == expected["caseId"], actual["caseId"]
    assert actual["receipt"] == receipt, (actual["receipt"], receipt)

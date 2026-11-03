#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""NXN process control; protocol SDKs continue to own all business traffic."""

import base64
import json
import os
from pathlib import Path


def enabled():
    return bool(os.environ.get("METEOR_NXN_REQUEST_FILE"))


def request(binary=False):
    value = json.loads(Path(os.environ["METEOR_NXN_REQUEST_FILE"]).read_text(encoding="utf-8"))
    if type(value["id"]) is not int or not -(2 ** 31) <= value["id"] < 2 ** 31:
        raise ValueError("NXN order id must be int32")
    if not isinstance(value["description"], str):
        raise ValueError("NXN order description must be a string")
    attachment = base64.b64decode(value["attachment"], validate=True)
    return {
        "id": value["id"], "description": value["description"],
        "attachment": attachment if binary else base64.b64encode(attachment).decode("ascii"),
    }


def ready(target):
    print(json.dumps({"event": "ready", "target": target}, ensure_ascii=False), flush=True)


def result(order, saved):
    def normalise(value):
        attachment = value["attachment"]
        if isinstance(attachment, (bytes, bytearray, memoryview)):
            attachment = base64.b64encode(attachment).decode("ascii")
        return {"id": value["id"], "description": value["description"], "attachment": attachment}

    actual = normalise(order)
    stored = normalise(saved)
    expected = request()
    if stored != expected:
        raise AssertionError(f"save returned {stored!r} instead of {expected!r}")
    if actual != expected:
        raise AssertionError(f"find returned {actual!r} instead of {expected!r}")
    print(json.dumps({"event": "result", "order": actual, "saved": stored}, ensure_ascii=False), flush=True)

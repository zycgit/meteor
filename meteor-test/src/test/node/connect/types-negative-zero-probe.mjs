/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import { strictEqual } from "node:assert";
import { create, toJson, toJsonString } from "@bufbuild/protobuf";
import { TypeValueSchema } from "./generated/types_pb.js";

// Isolate the official SDK from Meteor, HTTP, control JSON and application conversion.
const message = create(TypeValueSchema, { float32Value: -0, float64Value: -0 });
const value = toJson(TypeValueSchema, message);
const text = toJsonString(TypeValueSchema, message);
const restored = JSON.parse(text);
strictEqual(Object.is(message.float32Value, -0), true);
strictEqual(Object.is(message.float64Value, -0), true);
strictEqual(Object.is(value.float32Value, -0), true);
strictEqual(Object.is(value.float64Value, -0), true);

// JSON.stringify is the SDK's final output step and normalizes both negative zero values to positive zero.
strictEqual(Object.is(restored.float32Value, 0), true);
strictEqual(Object.is(restored.float64Value, 0), true);
console.log(JSON.stringify({
  sdk: "@bufbuild/protobuf@2.16.0",
  stage: "toJsonString / JSON.stringify",
  float32: { nativeNegativeZero: true, jsonObjectNegativeZero: true, parsedPositiveZero: true },
  float64: { nativeNegativeZero: true, jsonObjectNegativeZero: true, parsedPositiveZero: true },
  serializedJson: text,
}));

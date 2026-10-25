/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { TypeValueSchema } from "./generated/types_pb.js";

export { TypeEchoService } from "./generated/types_pb.js";

// Control JSON is only an input fixture. Restore native values before the SDK serializes the RPC.
export function restoreValue(input) {
  const mapValue = Object.fromEntries(Object.entries(input.mapValue).map(([key, value]) => [key, BigInt(value)]));
  const binaryValue = Uint8Array.from(atob(input.binaryValue), character => character.charCodeAt(0));
  return create(TypeValueSchema, {
    ...input,
    int64Value: BigInt(input.int64Value),
    unsigned64Value: BigInt(input.unsigned64Value),
    float32Value: Math.fround(Number(input.float32Value)),
    float64Value: Number(input.float64Value),
    binaryValue,
    enumValue: input.enumValue === "SECOND" ? 1 : 0,
    mapValue,
    optionalValue: input.optionalValue === null ? undefined : input.optionalValue,
  });
}

// Each result is a newly constructed native message; the provider issues its own business receipt.
export function acceptValue(request) {
  if (typeof request.int64Value !== "bigint" || typeof request.unsigned64Value !== "bigint") {
    throw new TypeError("64-bit integer fields must remain BigInt");
  }
  for (const value of Object.values(request.mapValue)) {
    if (typeof value !== "bigint") {
      throw new TypeError("Map int64 values must remain BigInt");
    }
  }
  return create(TypeValueSchema, {
    ...request,
    binaryValue: new Uint8Array(request.binaryValue),
    listValue: [...request.listValue],
    mapValue: { ...request.mapValue },
    recordValue: request.recordValue ? { ...request.recordValue } : undefined,
    receipt: `accepted:${request.caseId}`,
  });
}

function decimal(value) {
  return Object.is(value, -0) ? "-0.0" : String(value);
}

// Text is used only after RPC to transport observations back to JUnit, including exact integer precision.
export function plainValue(value) {
  return {
    caseId: value.caseId,
    booleanValue: value.booleanValue,
    int8Value: value.int8Value,
    int16Value: value.int16Value,
    int32Value: value.int32Value,
    int64Value: value.int64Value.toString(),
    unsigned64Value: value.unsigned64Value.toString(),
    float32Value: decimal(value.float32Value),
    float64Value: decimal(value.float64Value),
    textValue: value.textValue,
    binaryValue: btoa(String.fromCharCode(...value.binaryValue)),
    enumValue: value.enumValue === 1 ? "SECOND" : "FIRST",
    listValue: value.listValue,
    mapValue: Object.fromEntries(Object.entries(value.mapValue).map(([key, item]) => [key, item.toString()])),
    recordValue: value.recordValue ? { index: value.recordValue.index, name: value.recordValue.name } : null,
    optionalValue: value.optionalValue ?? null,
    receipt: value.receipt,
  };
}

// The browser reuses the official Buf codec instead of converting int64 through JavaScript Number.
export function serializeValue(value) {
  return toBinary(TypeValueSchema, value);
}

export function deserializeValue(bytes) {
  return fromBinary(TypeValueSchema, bytes);
}

// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

export const nxnEnabled = Boolean(process.env.METEOR_NXN_REQUEST_FILE);

export function orderRequest(binary = false) {
    if (!nxnEnabled) {
        return {
            id: 1001,
            description: "Order from Node.js",
            attachment: binary ? Buffer.from("Meteor") : Buffer.from("Meteor").toString("base64"),
        };
    }
    const value = JSON.parse(readFileSync(process.env.METEOR_NXN_REQUEST_FILE, "utf8"));
    assert.ok(Number.isInteger(value.id) && value.id >= -2147483648 && value.id <= 2147483647,
        "NXN order id must be int32");
    assert.equal(typeof value.description, "string", "NXN order description must be a string");
    assert.equal(typeof value.attachment, "string", "NXN order attachment must be Base64");
    const bytes = Buffer.from(value.attachment, "base64");
    assert.equal(bytes.toString("base64"), value.attachment, "NXN order attachment must be canonical Base64");
    return { id: value.id, description: value.description, attachment: binary ? bytes : value.attachment };
}

export function orderTarget(fallback, legacyPath) {
    let address = process.env.METEOR_ORDER_TARGET || fallback;
    // Official Connect transports take HTTP URLs for gRPC's HTTP/2 connection.
    if (address?.startsWith("grpc://")) {
        address = "http://" + address.slice("grpc://".length);
    } else if (address?.startsWith("grpcs://")) {
        address = "https://" + address.slice("grpcs://".length);
    }
    const target = new URL(address);
    // NXN's returned path belongs to the provider, including a mount at the root.
    if (!nxnEnabled && target.pathname === "/") {
        target.pathname = legacyPath;
    }
    return target;
}

export function orderReady(target) {
    if (nxnEnabled) {
        console.log(JSON.stringify({ event: "ready", target }));
    } else {
        console.log(`Order provider is ready: ${target}`);
    }
}

export function orderResult(protocol, found, saved) {
    const normalise = (value) => ({
        id: value.id,
        description: value.description,
        attachment: typeof value.attachment === "string"
            ? value.attachment : Buffer.from(value.attachment).toString("base64"),
    });
    const result = normalise(found);
    if (nxnEnabled) {
        const stored = normalise(saved);
        const expected = orderRequest();
        assert.deepEqual(stored, expected, "save returned a different order");
        assert.deepEqual(result, expected, "find returned a different order");
        console.log(JSON.stringify({ event: "result", order: result, saved: stored }));
    } else {
        console.log(JSON.stringify({ language: "node", protocol, result }));
    }
}

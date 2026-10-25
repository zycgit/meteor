/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import grpcWeb from "grpc-web";
import { deserializeValue, plainValue, restoreValue, serializeValue } from "../connect/types-codec.mjs";

// grpc-web is the official browser transport. Its public descriptor accepts the precision-preserving Buf codec.
const path = "/meteor.types.TypeEchoService/echo";
const descriptor = new grpcWeb.MethodDescriptor(
  path, grpcWeb.MethodType.UNARY, Object, Object, serializeValue, deserializeValue,
);

window.grpcWebTypeScenario = ({ target, format, value }) => new Promise((resolve, reject) => {
  const client = new grpcWeb.GrpcWebClientBase({ format });
  const request = restoreValue(value);
  client.rpcCall(`${target}${path}`, request, {}, descriptor, (error, response) => {
    if (error) {
      reject(new Error(`gRPC-Web ${error.code}: ${error.message}`));
    } else {
      resolve({ value: plainValue(response) });
    }
  });
});

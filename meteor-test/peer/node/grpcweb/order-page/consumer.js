// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import grpcWeb from "grpc-web";
import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { OrderSchema, OrderQuerySchema } from "../../connect/generated/order_pb.js";

// 使用公共 order.proto 的消息类型，通过 grpc-web SDK 调用 Meteor。
const configuredTarget = new URLSearchParams(window.location.search).get("target");
const targetAddress = new URL(configuredTarget || "http://127.0.0.1:8080/grpc-web");
// Meteor 就绪地址只携带协议、主机和端口，页面补上当前协议挂载路径。
if (targetAddress.pathname === "/") {
    targetAddress.pathname = "/grpc-web";
}
const target = targetAddress.toString().replace(/\/$/, "").replace(/^grpc-web:/, "http:");
const client = new grpcWeb.GrpcWebClientBase({ format: "binary" });
const service = "/meteor.example.OrderDemoService/";
const save = new grpcWeb.MethodDescriptor(
    service + "save", grpcWeb.MethodType.UNARY, Object, Object,
    (order) => toBinary(OrderSchema, order),
    (bytes) => fromBinary(OrderSchema, bytes),
);
const find = new grpcWeb.MethodDescriptor(
    service + "find", grpcWeb.MethodType.UNARY, Object, Object,
    (query) => toBinary(OrderQuerySchema, query),
    (bytes) => fromBinary(OrderSchema, bytes),
);

function call(method, request) {
    return new Promise((resolve, reject) => {
        client.rpcCall(target + method.getName(), request, {}, method, (error, response) => {
            if (error) {
                reject(error);
            } else {
                resolve(response);
            }
        });
    });
}

// 页面保存并查询固定订单，保留描述和附件文本供用户填写。
const form = document.querySelector("form");
const output = document.querySelector("pre");
form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector("button");
    button.disabled = true;
    output.textContent = "调用中…";
    try {
        const input = new FormData(form);
        const order = create(OrderSchema, {
            id: 1001,
            description: String(input.get("description")),
            attachment: new TextEncoder().encode(String(input.get("attachment"))),
        });

        // 先保存，再查询；附件以 Base64 展示。
        await call(save, order);
        const found = await call(find, create(OrderQuerySchema, { id: 1001 }));
        output.textContent = JSON.stringify({
            id: found.id,
            description: found.description,
            attachment: btoa(String.fromCharCode(...found.attachment)),
        }, null, 2);
    } catch (error) {
        output.textContent = error.message;
    } finally {
        button.disabled = false;
    }
});

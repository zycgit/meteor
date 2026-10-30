#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

const [sdkDirectory, outputDirectory, protoFile, bufExecutable] = process.argv.slice(2);
if (!sdkDirectory || !outputDirectory || !protoFile) {
    throw new Error("Usage: prepare.mjs <sdkRoot> <outputRoot> <protoFile> [bufExecutable]");
}

// 每个版本使用自己的 SDK 和生成器；源契约不复制到运行目录。
const sdkRoot = resolve(sdkDirectory);
const outputRoot = resolve(outputDirectory);
const manifestFile = join(sdkRoot, "node_modules/@connectrpc/connect/package.json");
const manifest = JSON.parse(await readFile(manifestFile, "utf8"));
const legacy = manifest.version.startsWith("1.");
const plugins = [{
    plugin: "es",
    path: join(sdkRoot, "node_modules/.bin/protoc-gen-es"),
    out: outputRoot,
    opt: "target=js",
}];
if (legacy) {
    plugins.push({
        plugin: "connect-es",
        path: join(sdkRoot, "node_modules/.bin/protoc-gen-connect-es"),
        out: outputRoot,
        opt: "target=js",
    });
}

// 生成结果和运行入口属于构建目录，已安装的依赖保持只读。
await mkdir(outputRoot, { recursive: true });
await writeFile(join(outputRoot, "package.json"), JSON.stringify({ private: true, type: "module" }) + "\n");
await rm(join(outputRoot, "node_modules"), { recursive: true, force: true });
await symlink(join(sdkRoot, "node_modules"), join(outputRoot, "node_modules"), "dir");
const template = JSON.stringify({ version: "v1", plugins });
const contract = resolve(protoFile);
const buf = bufExecutable || join(sdkRoot, "node_modules/.bin/buf");
const generated = spawnSync(buf, ["generate", contract, "--template", template], {
    stdio: "inherit",
});
if (generated.error) {
    throw generated.error;
}
if (generated.status !== 0) {
    throw new Error(`Connect ${manifest.version} contract generation failed: ${generated.signal || generated.status}`);
}

// 两代 SDK 只在公开客户端名称和生成的服务描述入口上存在差异。
const client = legacy ? "createPromiseClient" : "createClient";
const descriptor = legacy ? "order_connect.js" : "order_pb.js";
const runtime = [
    `export { ${client} as createClient, Code, ConnectError } from "@connectrpc/connect";`,
    'export { connectNodeAdapter, createConnectTransport } from "@connectrpc/connect-node";',
    `export { OrderDemoService } from "./${descriptor}";`,
    `export const sdkVersion = ${JSON.stringify(manifest.version)};`,
];
await writeFile(join(outputRoot, "runtime.mjs"), runtime.join("\n") + "\n");
console.log(`Prepared Connect Node ${manifest.version}: ${outputRoot}`);

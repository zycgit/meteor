// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { build } from "esbuild";

await build({
    entryPoints: ["consumer.js"],
    outfile: "dist/consumer.js",
    bundle: true,
    platform: "browser",
    target: "es2020",
});

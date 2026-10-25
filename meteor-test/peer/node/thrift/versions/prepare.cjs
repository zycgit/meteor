#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const fs = require('node:fs');
const path = require('node:path');

function prepare(profileDirectory) {
    // 官方生成代码放在版本依赖旁，通过普通 Node 模块解析使用本版本的 SDK。
    const profile = path.resolve(profileDirectory);
    fs.cpSync(path.join(__dirname, 'generated'), path.join(profile, 'generated'), {recursive: true});
    if (path.basename(profile) === 'thrift-0.25.0' || path.basename(profile) === '0.25.0') {
        fs.cpSync(path.join(__dirname, 'generated-bigint'), path.join(profile, 'generated-bigint'), {recursive: true});
    }
}

module.exports = {prepare};
if (require.main === module) {
    prepare(process.argv[2]);
}

#!/usr/bin/env node
'use strict';

// Node and browser services share one source fingerprint and generated output.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const versions = require('./versions/prepare.cjs');

const peers = [
    'hprose', 'connect', 'jsonrpc', 'grpcweb', 'thrift', 'soap', 'xmlrpc', 'dubbo', 'oncrpc', 'triple-alpha', 'hessian',
];
const ignoredDirectories = new Set(['node_modules', '__pycache__']);

function sourceFiles(directory, relative = '') {
    const files = [];
    for (const entry of fs.readdirSync(path.join(directory, relative), {withFileTypes: true})) {
        const name = path.join(relative, entry.name);
        if (entry.isDirectory()) {
            if (!ignoredDirectories.has(entry.name)) {
                files.push(...sourceFiles(directory, name));
            }
        } else if (fs.statSync(path.join(directory, name)).isFile()) {
            files.push(name);
        }
    }
    return files;
}

function prepare(source, output, toolchain) {
    // Lockfiles belong to the image; startup never installs or changes SDKs.
    const digest = crypto.createHash('sha256');
    for (const name of sourceFiles(source).sort()) {
        digest.update(name.split(path.sep).join('/'));
        digest.update(fs.readFileSync(path.join(source, name)));
    }
    for (const peer of peers) {
        const installed = fs.readFileSync(path.join(toolchain, peer, 'package-lock.json'));
        const declared = fs.readFileSync(path.join(source, peer, 'package-lock.json'));
        if (!installed.equals(declared)) {
            throw new Error(`Node dependency lockfile changed; rebuild the image: ${peer}`);
        }
    }
    const sdkProfiles = versions.verifyInstalled(source, toolchain);
    const fingerprint = digest.digest('hex');
    const marker = path.join(output, 'node-build.json');
    if (fs.existsSync(marker) && JSON.parse(fs.readFileSync(marker, 'utf8')).source_sha256 === fingerprint) {
        return fingerprint;
    }

    // Build in the output volume; mounted sources and installed SDKs stay unchanged.
    fs.rmSync(marker, {force: true});
    const target = path.join(output, 'node');
    fs.rmSync(target, {recursive: true, force: true});
    fs.cpSync(source, target, {
        recursive: true,
        dereference: true,
        filter: file => !path.relative(source, file).split(path.sep).some(part => ignoredDirectories.has(part)),
    });
    for (const peer of peers) {
        fs.symlinkSync(path.join(toolchain, peer, 'node_modules'), path.join(target, peer, 'node_modules'), 'dir');
    }
    const compiled = spawnSync(process.execPath, ['build.js'], {cwd: path.join(target, 'grpcweb'), stdio: 'inherit'});
    if (compiled.error) {
        throw compiled.error;
    }
    if (compiled.status !== 0) {
        throw new Error(`Node bundle build failed: ${compiled.signal || compiled.status}`);
    }
    const assets = path.join(output, 'assets', 'grpcweb');
    fs.mkdirSync(assets, {recursive: true});
    for (const name of ['browser.bundle.js', 'types-browser.bundle.js']) {
        fs.copyFileSync(path.join(target, 'grpcweb', name), path.join(assets, name));
    }
    versions.prepare(source, output, toolchain, sdkProfiles);
    fs.writeFileSync(marker, JSON.stringify({source_sha256: fingerprint}) + '\n');
    return fingerprint;
}

function main(args) {
    // flock releases the shared build lock even when a preparer is terminated.
    const options = {output: '/opt/meteor-peer', toolchain: '/opt/meteor-toolchain/node'};
    for (let index = 0; index < args.length; index++) {
        const option = args[index];
        if (option === '--prepare-only' || option === '--locked') {
            options[option.slice(2)] = true;
        } else if (option === '--output' || option === '--toolchain') {
            const value = args[++index];
            if (!value) {
                throw new Error(`Missing value for ${option}`);
            }
            options[option.slice(2)] = path.resolve(value);
        } else {
            throw new Error(`Unknown option: ${option}`);
        }
    }
    const runtime = process.env.METEOR_PEER_RUNTIME || 'node';
    const ready = path.join(options.output, `runtime-${runtime}.ready.json`);
    fs.mkdirSync(options.output, {recursive: true});
    if (options.locked) {
        const fingerprint = prepare(__dirname, options.output, options.toolchain);
        if (runtime === 'node') {
            // 实际 SDK 清单和锁定依赖图归工作区，清理运行卷后仍可审计本轮身份。
            const archive = path.resolve(__dirname, '../../build/preparation/sdk-versions/node');
            const manifest = path.join(options.output, 'sdk-versions/runtimes.json');
            const selected = JSON.parse(fs.readFileSync(manifest, 'utf8'));
            fs.mkdirSync(archive, {recursive: true});
            fs.copyFileSync(manifest, path.join(archive, 'runtimes.json'));
            for (const profile of selected.profiles) {
                const destination = path.join(archive, profile.profile);
                fs.mkdirSync(destination, {recursive: true});
                fs.copyFileSync(path.join(profile.root, 'sdk.json'), path.join(destination, 'sdk.json'));
                fs.copyFileSync(path.join(profile.installed, 'package-lock.json'),
                    path.join(destination, 'package-lock.json'));
            }
        }
        fs.writeFileSync(ready, JSON.stringify({runtime, source_sha256: fingerprint}) + '\n');
        return;
    }

    // Advertise readiness only after this service observed a successful shared build.
    fs.rmSync(ready, {force: true});
    const lock = path.join(options.output, '.node-build.lock');
    const command = [lock, process.execPath, __filename, ...args, '--locked'];
    const prepared = spawnSync('flock', command, {stdio: 'inherit'});
    if (prepared.error) {
        throw prepared.error;
    }
    if (prepared.status !== 0) {
        throw new Error(`Node runtime preparation failed: ${prepared.signal || prepared.status}`);
    }
    console.log(ready);
    if (!options['prepare-only']) {
        setInterval(() => {}, 1 << 30);
    }
}

module.exports = {prepare};
if (require.main === module) {
    try {
        main(process.argv.slice(2));
    } catch (error) {
        console.error(error.stack || error);
        process.exitCode = 1;
    }
}

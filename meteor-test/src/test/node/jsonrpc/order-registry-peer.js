/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
'use strict';
const assert = require('node:assert/strict');
const readline = require('node:readline');
const jayson = require('jayson');

function copy(order) {
    // JSON-RPC uses a Base64 string for the shared Java byte[] field.
    assert.equal(typeof order.id, 'number');
    assert.equal(Number.isInteger(order.id), true);
    assert.equal(typeof order.description, 'string');
    assert.equal(typeof order.attachment, 'string');
    return {id: order.id, description: order.description, attachment: order.attachment};
}

function request(client, method, values) {
    return new Promise((resolve, reject) => {
        // Jayson owns JSON-RPC request IDs, JSON encoding and response/error correlation.
        client.request(method, values, (failure, response) => {
            if (failure) {
                reject(failure);
            } else if (response.error) {
                reject(new Error(JSON.stringify(response.error)));
            } else {
                resolve(response.result);
            }
        });
    });
}

async function consumer() {
    // The process has no business URL at startup; only the registry resolver supplies it.
    console.log('CONSUMER_READY');
    const lines = readline.createInterface({input: process.stdin});
    for await (const line of lines) {
        const command = JSON.parse(line);
        const url = new URL(command.address);
        assert.equal(url.protocol, 'http:');
        const client = jayson.client.http(url.toString());
        client.options.timeout = 5000;
        const expected = copy(command.order);

        // Business requests go directly from the Node SDK to the resolved Meteor endpoint.
        const saved = await request(client, 'orders.save', [expected]);
        const found = await request(client, 'orders.find', [{id: expected.id}]);
        assert.deepEqual(saved, expected);
        assert.deepEqual(found, expected);
        const expectedBytes = Buffer.from(expected.attachment, 'base64');
        assert.deepEqual(Buffer.from(found.attachment, 'base64'), expectedBytes);
        console.log('RESULT:' + JSON.stringify(found));
    }
}

async function provider() {
    const orders = new Map();
    const rpc = new jayson.Server({
        'orders.save': (args, done) => {
            const stored = copy(args[0]);
            orders.set(stored.id, stored);
            done(null, copy(stored));
        },
        'orders.find': (args, done) => {
            const found = orders.get(args[0].id);
            if (found) {
                done(null, copy(found));
            } else {
                done({code: -32603, message: 'Order not found: ' + args[0].id});
            }
        }
    });

    // The official SDK owns its own HTTP server; registration happens only after READY.
    const server = rpc.http();
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    console.log('READY:' + server.address().port);
    process.stdin.resume();
    await new Promise(resolve => process.stdin.once('end', resolve));
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
}

async function main() {
    const mode = process.argv[2];
    if (mode === 'provider') {
        await provider();
    } else if (mode === 'consumer') {
        await consumer();
    } else {
        throw new Error('Expected provider or consumer');
    }
}

main().catch(failure => {
    console.error(failure);
    process.exitCode = 1;
});

'use strict';

const assert = require('node:assert/strict');
const xmlrpc = require('xmlrpc');

async function main() {
    const [role, target, message] = process.argv.slice(2);
    if (role === 'consumer') {
        // The SDK owns XML-RPC serialization and parses Meteor's business response.
        const client = xmlrpc.createClient({url: target});
        const result = await new Promise((resolve, reject) => {
            client.methodCall('demo.echo', [message], (error, value) => {
                if (error) reject(error);
                else resolve(value);
            });
        });
        assert.equal(result, message);
        console.log('OK');
        return;
    }
    if (role !== 'provider') {
        throw new Error(`Unknown role: ${role}`);
    }

    // node-xmlrpc exposes the protocol method directly on an independently allocated port.
    const server = xmlrpc.createServer({host: '0.0.0.0', port: 0, path: '/rpc'}, () => {
        console.log(`READY ${server.httpServer.address().port}`);
    });
    server.on('demo.echo', (error, parameters, callback) => {
        callback(error, parameters[0]);
    });

    // EOF stops only this peer, leaving the shared Node container available to other cases.
    process.stdin.resume();
    process.stdin.on('end', () => {
        server.close(() => {});
        server.httpServer.closeAllConnections();
    });
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

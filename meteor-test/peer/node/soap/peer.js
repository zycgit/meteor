'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const soap = require('soap');

async function main() {
    const [role, target, message] = process.argv.slice(2);
    if (role === 'consumer') {
        // node-soap reads Meteor's published WSDL and performs the business request itself.
        const client = await soap.createClientAsync(target, {wsdl_options: {timeout: 10000}});
        const [response] = await client.echoAsync({message}, {timeout: 10000});
        assert.equal(response.result, message);
        console.log('OK');
        return;
    }
    if (role !== 'provider') {
        throw new Error(`Unknown role: ${role}`);
    }

    // The SDK reads the same test-owned WSDL and resolves its adjacent XSD itself.
    const wsdl = fs.readFileSync(target, 'utf8');
    const services = {EchoService: {EchoPort: {echo: request => ({result: request.message})}}};
    const server = http.createServer();
    soap.listen(server, {
        path: '/services/EchoService', uri: target, services, xml: wsdl,
        callback: error => {
            if (error) throw error;
            server.listen(0, '0.0.0.0', () => console.log(`READY ${server.address().port}`));
        }
    });

    // The scenario owns this process; EOF closes the server and all of its connections.
    process.stdin.resume();
    process.stdin.on('end', () => {
        server.close();
        server.closeAllConnections();
    });
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

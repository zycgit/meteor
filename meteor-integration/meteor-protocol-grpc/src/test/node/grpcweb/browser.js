/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const fs = require('fs');
const http = require('http');
const readline = require('readline');
const path = require('path');
const { chromium } = require('playwright');

async function run() {
  // The page has its own port, so every RPC really crosses an origin boundary.
  const bundle = fs.readFileSync(path.join(__dirname, 'browser.bundle.js'));
  const server = http.createServer((request, response) => {
    if (request.url === '/client.js') {
      response.writeHead(200, { 'content-type': 'text/javascript' });
      response.end(bundle);
    } else {
      response.writeHead(200, { 'content-type': 'text/html' });
      response.end('<!doctype html><script src="/client.js"></script>');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const input = readline.createInterface({ input: process.stdin, terminal: false });
  const inputClosed = new Promise(resolve => { input.once('close', resolve); });
  const inputCommand = new Promise(resolve => {
    input.once('line', line => resolve(JSON.parse(line)));
    input.once('close', () => resolve(null));
  });
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
    // Only the explicit TLS fixture bypasses its private test CA; CORS remains enabled.
    const page = await browser.newPage({ ignoreHTTPSErrors: process.argv.includes('--test-tls') });
    const origin = `http://127.0.0.1:${server.address().port}`;
    await page.goto(origin);

    // Observe Chromium's actual network requests, including automatic OPTIONS preflight.
    const network = await page.context().newCDPSession(page);
    await network.send('Network.enable');
    const requests = [];
    const responses = [];
    const networkEvents = [];
    const requestMethods = new Map();
    let receivedPostResponse;
    const postResponse = new Promise(resolve => { receivedPostResponse = resolve; });
    network.on('Network.requestWillBeSent', event => {
      requestMethods.set(event.requestId, event.request.method);
      requests.push({ method: event.request.method, url: event.request.url, headers: event.request.headers });
    });
    network.on('Network.responseReceived', event => {
      responses.push({ url: event.response.url, protocol: event.response.protocol, status: event.response.status,
        headers: event.response.headers });
      if (requestMethods.get(event.requestId) === 'POST') {
        receivedPostResponse();
      }
    });
    network.on('Network.dataReceived', event => {
      networkEvents.push({ type: 'data', requestId: event.requestId, length: event.dataLength });
    });
    network.on('Network.loadingFailed', event => {
      networkEvents.push({ type: 'failed', requestId: event.requestId, error: event.errorText,
        cancelled: event.canceled, blockedReason: event.blockedReason });
    });
    console.log(`READY ${origin}`);
    const command = await inputCommand;
    if (command === null) {
      return;
    }

    // A bounded wait treats a hung stream or CORS exchange as a test failure.
    let invocationComplete = false;
    let invocationResult;
    let stage = 'invoke';
    let timer;
    const timeout = new Promise((resolve, reject) => {
      timer = setTimeout(() => {
        console.error(`DIAGNOSTIC ${JSON.stringify({ stage, invocationComplete, invocationResult,
          requests, responses, networkEvents })}`);
        reject(new Error('Browser gRPC-Web call timed out'));
      }, 15000);
      timer.unref();
    });
    const invocation = page.evaluate(value => window.grpcWebScenario(value), command).then(value => {
      invocationComplete = true;
      invocationResult = value;
      return value;
    });
    let result;
    try {
      result = await Promise.race([invocation, timeout]);
      if (command.scene === 'h2cancel' && result.cancelled) {
        stage = 'post-response-observation';
        await Promise.race([postResponse, timeout]);
      }
    } finally {
      clearTimeout(timer);
    }
    result.requests = requests;
    result.responses = responses;
    result.networkEvents = networkEvents;
    result.origin = origin;
    console.log(`RESULT ${JSON.stringify(result)}`);
    // Keep the browser alive until Java finishes lifecycle assertions and closes stdin.
    await inputClosed;
  } finally {
    input.close();
    if (browser) {
      await browser.close();
    }
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

run().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
});

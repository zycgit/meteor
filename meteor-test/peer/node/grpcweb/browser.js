/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const fs = require('fs');
const http = require('http');
const readline = require('readline');
const path = require('path');
const { chromium } = require('playwright');
const { createDiagnostics } = require('./browser-diagnostics');
const diagnostics = createDiagnostics();

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
    server.listen(0, '0.0.0.0', resolve);
  });
  const input = readline.createInterface({ input: process.stdin, terminal: false });
  const inputClosed = new Promise(resolve => {
    input.once('close', () => {
      diagnostics.log('stdin-closed');
      resolve();
    });
  });
  const inputCommand = new Promise(resolve => {
    input.once('line', line => resolve(JSON.parse(line)));
    input.once('close', () => resolve(null));
  });
  let browser;
  try {
    await diagnostics.networkReady();
    diagnostics.snapshot('before-launch');
    browser = await chromium.launch({ headless: true, args: diagnostics.launchArgs });
    diagnostics.log('browser-launched', { version: browser.version(), args: diagnostics.launchArgs });
    diagnostics.snapshot('after-launch');
    // Only the explicit TLS fixture bypasses its private test CA; CORS remains enabled.
    const page = await browser.newPage({ ignoreHTTPSErrors: process.argv.includes('--test-tls') });
    const { postResponse } = await diagnostics.observe(page);
    const origin = diagnostics.origin(server.address().port);
    diagnostics.log('navigation-start', { origin });
    await page.goto(origin);
    diagnostics.log('navigation-complete', { origin });
    diagnostics.snapshot('ready');
    console.log(`READY ${origin}`);
    const command = await inputCommand;
    if (command === null) {
      return;
    }

    // A bounded wait treats a hung stream or CORS exchange as a test failure.
    diagnostics.log('invocation-start', { command });
    let invocationComplete = false;
    let invocationResult;
    let stage = 'invoke';
    let timer;
    const timeout = new Promise((resolve, reject) => {
      timer = setTimeout(() => {
        console.error(`DIAGNOSTIC ${JSON.stringify({ stage, invocationComplete, invocationResult,
          requests: diagnostics.requests, responses: diagnostics.responses,
          networkEvents: diagnostics.networkEvents })}`);
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
    result.requests = diagnostics.requests;
    result.responses = diagnostics.responses;
    result.networkEvents = diagnostics.networkEvents;
    result.origin = origin;
    diagnostics.result(result);
    console.log(`RESULT ${JSON.stringify(result)}`);
    // Keep the browser alive until Java finishes lifecycle assertions and closes stdin.
    await inputClosed;
  } catch (error) {
    diagnostics.log('failure', { message: error.message, stack: error.stack });
    throw error;
  } finally {
    diagnostics.log('shutdown-start');
    input.close();
    if (browser) {
      await browser.close();
      diagnostics.log('browser-closed');
    }
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    diagnostics.log('server-closed');
    diagnostics.close();
  }
}

run().catch(error => {
  diagnostics.log('process-error', { message: error.message, stack: error.stack });
  console.error(error.stack || error);
  process.exitCode = 1;
});

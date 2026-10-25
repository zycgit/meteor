/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const fs = require('fs');
const http = require('http');
const readline = require('readline');
const path = require('path');
const { chromium } = require('playwright');
const { createDiagnostics } = require('./browser-diagnostics');
const diagnostics = createDiagnostics();

async function run() {
  // The page origin is hosted by this independent container; Chromium's CORS checks remain enabled.
  const bundle = fs.readFileSync(path.join(__dirname, 'types-browser.bundle.js'));
  const server = http.createServer((request, response) => {
    if (request.url === '/types-client.js') {
      response.writeHead(200, { 'content-type': 'text/javascript' });
      response.end(bundle);
    } else {
      response.writeHead(200, { 'content-type': 'text/html' });
      response.end('<!doctype html><script src="/types-client.js"></script>');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '0.0.0.0', resolve);
  });
  const controls = readline.createInterface({ input: process.stdin });
  controls.once('close', () => diagnostics.log('stdin-closed'));
  let browser;
  try {
    await diagnostics.networkReady();
    diagnostics.snapshot('before-launch');
    browser = await chromium.launch({ headless: true, args: diagnostics.launchArgs });
    diagnostics.log('browser-launched', { version: browser.version(), args: diagnostics.launchArgs });
    diagnostics.snapshot('after-launch');
    const origin = diagnostics.origin(server.address().port);
    const page = await browser.newPage();
    await diagnostics.observe(page);
    diagnostics.log('navigation-start', { origin });
    await page.goto(origin);
    diagnostics.log('navigation-complete', { origin });
    diagnostics.snapshot('ready');
    console.log(`READY ${JSON.stringify({ origin })}`);

    // Each JUnit instance sends exactly one vector; native messages are built inside the real browser page.
    for await (const line of controls) {
      const command = JSON.parse(line);
      const table = JSON.parse(fs.readFileSync(process.env.METEOR_TYPE_VECTORS, 'utf8'));
      const selected = table.vectors[command.vector];
      if (!selected) {
        throw new Error(`Unknown type vector: ${command.vector}`);
      }
      command.value = { ...table.defaults, [selected.field]: selected.value,
        caseId: command.vector, receipt: `client:${command.vector}` };
      diagnostics.log('invocation-start', { target: command.target, format: command.format, vector: command.vector });
      const result = await page.evaluate(value => window.grpcWebTypeScenario(value), command);
      diagnostics.result(result);
      console.log(`RESULT ${JSON.stringify(result)}`);
    }
  } catch (failure) {
    diagnostics.log('failure', { message: failure.message, stack: failure.stack });
    throw failure;
  } finally {
    // Only this process owns Chromium and its page server; its caller owns the container lifecycle.
    diagnostics.log('shutdown-start');
    controls.close();
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

run().catch(failure => {
  diagnostics.log('process-error', { message: failure.message, stack: failure.stack });
  console.error(failure.stack || failure);
  process.exitCode = 1;
});

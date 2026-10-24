/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const fs = require('fs');
const http = require('http');
const readline = require('readline');
const path = require('path');
const { chromium } = require('playwright');

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
  const browser = await chromium.launch({ headless: true });
  try {
    const host = process.env.METEOR_TEST_PEER_HOST;
    if (!host) {
      throw new Error('METEOR_TEST_PEER_HOST is required');
    }
    const origin = `http://${host}:${server.address().port}`;
    const page = await browser.newPage();
    await page.goto(origin);
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
      const result = await page.evaluate(value => window.grpcWebTypeScenario(value), command);
      console.log(`RESULT ${JSON.stringify(result)}`);
    }
  } finally {
    // Only this process owns Chromium and its page server; its caller owns the container lifecycle.
    controls.close();
    await browser.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

run().catch(failure => {
  console.error(failure.stack || failure);
  process.exitCode = 1;
});

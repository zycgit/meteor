/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const { test } = require('node:test');
const dns = require('node:dns').promises;
const { createDiagnostics } = require('./browser-diagnostics');

function fixture(t, flags) {
  // Keep network and clock observations deterministic without launching Chromium or invoking business RPC.
  const temporaryRoot = path.resolve(__dirname, '../../../build/tmp/browser-diagnostics');
  fs.mkdirSync(temporaryRoot, { recursive: true });
  const directory = fs.mkdtempSync(path.join(temporaryRoot, 'run-'));
  const variable = 'METEOR_TEST_PEER_EVIDENCE_DIR';
  const previous = process.env[variable];
  process.env[variable] = directory;
  t.mock.method(dns, 'lookup', async () => []);
  t.mock.method(os, 'networkInterfaces', () => ({}));
  t.mock.method(global, 'setInterval', () => ({ unref() {} }));
  t.mock.method(global, 'clearInterval', () => {});
  const read = fs.readFileSync;
  let reads = 0;
  t.mock.method(fs, 'readFileSync', (file, ...arguments) => {
    if (file === '/proc/net/if_inet6') {
      const current = flags[Math.min(reads++, flags.length - 1)];
      return current ? `fd9c0000000000000000000000000002 02 80 00 ${current} eth0\n` : '';
    }
    return read.call(fs, file, ...arguments);
  });
  t.mock.method(fs, 'existsSync', file => file === '/proc/net/if_inet6');
  const diagnostics = createDiagnostics();
  t.after(() => {
    diagnostics.close();
    if (previous === undefined) {
      delete process.env[variable];
    } else {
      process.env[variable] = previous;
    }
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return { diagnostics, directory, reads: () => reads };
}

test('a permanent IPv6 address is ready without a delay', async t => {
  // IFA_F_PERMANENT (0x80) is ready, rather than an error or a tentative address.
  const { diagnostics } = fixture(t, ['80']);
  const delay = t.mock.method(global, 'setTimeout', () => assert.fail('Ready addresses must not wait'));

  // The startup check finishes once, before a browser is launched.
  await diagnostics.networkReady();
  assert.equal(delay.mock.callCount(), 0);
});

test('a tentative IPv6 address waits for DAD completion', async t => {
  // The same address progresses from permanent+tentative (0xc0) to permanent (0x80).
  const { diagnostics, reads } = fixture(t, ['c0', 'c0', '80']);
  const delay = t.mock.method(global, 'setTimeout', callback => queueMicrotask(callback));

  // Observation waits for the state transition, without a fixed startup sleep.
  await diagnostics.networkReady();
  assert.equal(delay.mock.callCount(), 1);
  assert.equal(reads(), 3);
});

test('a failed duplicate address detection rejects startup', async t => {
  // IFA_F_DADFAILED (0x08) cannot become a successful readiness result.
  const { diagnostics } = fixture(t, ['88']);

  // The readiness failure is reported directly, without attempting a page or RPC.
  await assert.rejects(diagnostics.networkReady(), /duplicate address detection failed/);
});

test('a permanently tentative IPv6 address reaches the readiness deadline', async t => {
  // Advance only the monotonic test clock while leaving the tentative state unchanged.
  let elapsed = 0;
  t.mock.method(performance, 'now', () => elapsed);
  const { diagnostics } = fixture(t, ['c0']);
  const delay = t.mock.method(global, 'setTimeout', callback => {
    elapsed += 10001;
    queueMicrotask(callback);
  });

  // The bounded wait fails; it does not turn the readiness timeout into success.
  await assert.rejects(diagnostics.networkReady(), /remained tentative/);
  assert.equal(delay.mock.callCount(), 1);
});

test('an IPv4-only peer is ready without introducing an IPv6 requirement', async t => {
  // An empty IPv6 table is valid for an IPv4-only network.
  const { diagnostics } = fixture(t, ['']);
  const delay = t.mock.method(global, 'setTimeout', () => assert.fail('IPv4-only peers must not wait'));

  // Existing IPv4 transport coverage does not acquire a synthetic IPv6 dependency.
  await diagnostics.networkReady();
  assert.equal(delay.mock.callCount(), 0);
});

test('successful browser results are persisted before Java assertions', t => {
  // A peer can exit zero even when a later Java assertion fails.
  const { diagnostics, directory } = fixture(t, ['80']);
  const result = { error: { code: 2, message: 'network failed' }, value: '' };

  // Persist the exact returned structure regardless of exit status.
  diagnostics.result(result);
  const saved = JSON.parse(fs.readFileSync(path.join(directory, 'browser-result.json'), 'utf8'));
  assert.deepEqual(saved, result);
});

test('network failure observation records evidence without resending a request', async t => {
  // A lightweight CDP emitter exercises diagnostics only, not an alternate browser transport.
  const { diagnostics, directory } = fixture(t, ['80']);
  const network = new EventEmitter();
  network.send = async () => {};
  const page = new EventEmitter();
  page.context = () => ({ newCDPSession: async () => network });
  const navigate = t.mock.fn();
  page.goto = navigate;
  await diagnostics.observe(page);

  // A failed navigation or RPC remains failed; the observer only appends its original event.
  network.emit('Network.loadingFailed', { requestId: 'request-1', errorText: 'net::ERR_NETWORK_CHANGED' });
  const events = fs.readFileSync(path.join(directory, 'browser-events.jsonl'), 'utf8')
    .trim().split('\n').map(line => JSON.parse(line));
  assert.equal(navigate.mock.callCount(), 0);
  assert.equal(events.filter(event => event.type === 'failed').length, 1);
  assert.equal(diagnostics.networkEvents[0].error, 'net::ERR_NETWORK_CHANGED');
});

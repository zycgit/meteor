/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const dns = require('dns').promises;
const fs = require('fs');
const os = require('os');
const path = require('path');
const { performance } = require('perf_hooks');

// Both browser entrypoints keep evidence in the shared workspace, including peers whose process exits successfully.
function createDiagnostics() {
  const directory = process.env.METEOR_TEST_PEER_EVIDENCE_DIR;
  if (!directory) {
    throw new Error('METEOR_TEST_PEER_EVIDENCE_DIR is required');
  }
  fs.mkdirSync(directory, { recursive: true });
  const started = performance.now();
  const requests = [];
  const responses = [];
  const networkEvents = [];
  const log = (type, detail = {}) => {
    const event = { elapsedMs: performance.now() - started, type, ...detail };
    fs.appendFileSync(path.join(directory, 'browser-events.jsonl'), `${JSON.stringify(event)}\n`);
    return event;
  };
  const snapshot = phase => {
    const interfaces = os.networkInterfaces();
    const ipv6 = fs.existsSync('/proc/net/if_inet6') ? fs.readFileSync('/proc/net/if_inet6', 'utf8') : '';
    log('interfaces', { phase, interfaces, ipv6 });
    return ipv6;
  };
  let previous = snapshot('process-start');
  const observer = setInterval(() => {
    const current = fs.existsSync('/proc/net/if_inet6') ? fs.readFileSync('/proc/net/if_inet6', 'utf8') : '';
    if (current !== previous) {
      previous = current;
      snapshot('address-change');
    }
  }, 100);
  observer.unref();
  for (const hostname of [process.env.METEOR_TEST_PEER_HOST, process.env.METEOR_TEST_RUNNER_HOST]) {
    if (hostname) {
      dns.lookup(hostname, { all: true }).then(addresses => log('dns', { hostname, addresses }))
        .catch(error => log('dns-error', { hostname, message: error.message }));
    }
  }

  return {
    log, snapshot, requests, responses, networkEvents,
    launchArgs: [`--log-net-log=${path.join(directory, 'chromium-netlog.json')}`, '--net-log-max-size-mb=20'],
    origin(port) {
      const host = process.env.METEOR_TEST_PEER_HOST;
      if (!host) {
        throw new Error('METEOR_TEST_PEER_HOST is required');
      }
      const origin = `http://${host}:${port}`;
      log('page-origin', { origin });
      return origin;
    },
    async networkReady() {
      // Chromium observes interface changes globally; finish IPv6 DAD before opening any page or RPC.
      const deadline = performance.now() + 10000;
      while (true) {
        const ipv6 = snapshot('network-readiness');
        const addresses = ipv6.trim().split('\n').filter(Boolean).map(line => line.trim().split(/\s+/));
        const failed = addresses.some(fields => (parseInt(fields[4], 16) & 0x08) !== 0);
        const tentative = addresses.some(fields => (parseInt(fields[4], 16) & 0x40) !== 0);
        if (failed) {
          throw new Error('Browser peer IPv6 duplicate address detection failed');
        }
        if (!tentative) {
          log('network-ready');
          return;
        }
        if (performance.now() >= deadline) {
          throw new Error('Browser peer IPv6 address remained tentative');
        }
        await new Promise(resolve => setTimeout(resolve, 20));
      }
    },
    async observe(page) {
      // Listeners are installed before the first navigation, so early startup failures retain their cause.
      page.on('console', message => log('console', { level: message.type(), text: message.text() }));
      page.on('pageerror', error => log('page-error', { message: error.message, stack: error.stack }));
      page.on('requestfailed', request => log('request-failed', {
        method: request.method(), url: request.url(), failure: request.failure()
      }));
      page.on('crash', () => log('page-crash'));
      const network = await page.context().newCDPSession(page);
      await network.send('Network.enable');
      const requestMethods = new Map();
      let receivedPostResponse;
      const postResponse = new Promise(resolve => { receivedPostResponse = resolve; });
      network.on('Network.requestWillBeSent', event => {
        requestMethods.set(event.requestId, event.request.method);
        const request = { method: event.request.method, url: event.request.url, headers: event.request.headers };
        requests.push(request);
        log('request', { requestId: event.requestId, ...request });
      });
      network.on('Network.responseReceived', event => {
        const response = { url: event.response.url, protocol: event.response.protocol, status: event.response.status,
          headers: event.response.headers };
        responses.push(response);
        log('response', { requestId: event.requestId, ...response });
        if (requestMethods.get(event.requestId) === 'POST') {
          receivedPostResponse();
        }
      });
      network.on('Network.dataReceived', event => {
        networkEvents.push(log('data', { requestId: event.requestId, length: event.dataLength }));
      });
      network.on('Network.loadingFailed', event => {
        networkEvents.push(log('failed', { requestId: event.requestId, error: event.errorText,
          cancelled: event.canceled, blockedReason: event.blockedReason,
          corsErrorStatus: event.corsErrorStatus }));
        snapshot('request-failure');
      });
      return { postResponse };
    },
    result(result) {
      fs.writeFileSync(path.join(directory, 'browser-result.json'), `${JSON.stringify(result, null, 2)}\n`);
      log('result', { result });
    },
    close() {
      snapshot('process-finish');
      clearInterval(observer);
      const netlog = path.join(directory, 'chromium-netlog.json');
      if (fs.existsSync(netlog)) {
        fs.chmodSync(netlog, 0o644);
      }
    }
  };
}

module.exports = { createDiagnostics };

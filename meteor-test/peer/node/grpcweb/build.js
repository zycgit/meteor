/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const esbuild = require('esbuild');
esbuild.buildSync({
  entryPoints: ['client.js'],
  outfile: 'browser.bundle.js',
  bundle: true,
  platform: 'browser',
  target: 'es2020'
});
// Keep the type-matrix client separate from existing lifecycle and CORS browser fixtures.
esbuild.buildSync({
  entryPoints: ['types-client.mjs'],
  outfile: 'types-browser.bundle.js',
  bundle: true,
  platform: 'browser',
  target: 'es2020'
});
// Exercise a real form through the official SDK with the test-owned order contract.
esbuild.buildSync({
  entryPoints: ['order-page/consumer.js'],
  outfile: 'order-page/dist/consumer.js',
  nodePaths: ['../connect/node_modules'],
  bundle: true,
  platform: 'browser',
  target: 'es2020'
});

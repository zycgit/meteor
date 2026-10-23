/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const esbuild = require('esbuild');
esbuild.buildSync({
  entryPoints: ['client.js'],
  outfile: 'browser.bundle.js',
  bundle: true,
  platform: 'browser',
  target: 'es2020'
});

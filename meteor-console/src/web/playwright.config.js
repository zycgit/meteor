import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  testMatch: '*.spec.js',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  use: { baseURL: 'http://127.0.0.1:18765', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: {
    command: 'node tests/start-fixture.js',
    wait: { stdout: /METEOR_CONSOLE_READY/ },
    timeout: 60000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 10000 }
  }
});

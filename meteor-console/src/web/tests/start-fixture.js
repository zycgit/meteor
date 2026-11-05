import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
const file =
  process.env.METEOR_CONSOLE_FIXTURE || new URL('../../../build/browser-fixture.json', import.meta.url);
const config = JSON.parse(readFileSync(file));
const child = spawn(
  config.java,
  [
    `-Djava.io.tmpdir=${config.temporaryDirectory}`,
    `-Duser.home=${config.userDirectory}`,
    '-cp',
    config.classpath,
    'net.hasor.meteor.test.scenario.ConsoleBrowserFixture'
  ],
  { stdio: 'inherit' }
);
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => child.kill(signal));
}
child.on('exit', (code) => process.exit(code ?? 0));
child.on('error', (error) => {
  console.error(error);
  process.exit(1);
});

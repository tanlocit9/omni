#!/usr/bin/env node

const { spawn } = require('node:child_process');
const { resolve } = require('node:path');

const isWindows = process.platform === 'win32';
const wrapper = isWindows ? 'gradlew.bat' : './gradlew';
const child = spawn(wrapper, process.argv.slice(2), {
  cwd: resolve(__dirname, '../apps/core'),
  shell: isWindows,
  stdio: 'inherit',
});

child.on('error', (error) => {
  console.error('Failed to run Gradle wrapper: ' + error.message);
  process.exitCode = 1;
});

child.on('exit', (code, signal) => {
  process.exitCode = signal ? 1 : code ?? 1;
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (!child.killed) {
      child.kill(signal);
    }
  });
}

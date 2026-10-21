#!/usr/bin/env node

const { spawn } = require('node:child_process');
const { existsSync } = require('node:fs');
const { resolve } = require('node:path');

const isWindows = process.platform === 'win32';
const localPython = resolve(
  __dirname,
  '..',
  '.venv',
  isWindows ? 'Scripts/python.exe' : 'bin/python'
);

if (isWindows && !existsSync(localPython)) {
  console.error(
    'Workspace Python is missing: create .venv before running tools.'
  );
  process.exitCode = 1;
} else {
  const python = existsSync(localPython) ? localPython : 'python3';
  const child = spawn(python, ['-u', ...process.argv.slice(2)], {
    shell: false,
    windowsHide: true,
    stdio: 'inherit',
  });
  child.on('error', (error) => {
    console.error('Failed to run Python: ' + error.message);
    process.exitCode = 1;
  });
  child.on('exit', (code, signal) => {
    process.exitCode = signal ? 1 : code ?? 1;
  });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      if (!child.killed) child.kill(signal);
    });
  }
}

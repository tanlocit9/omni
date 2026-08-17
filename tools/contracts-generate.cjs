const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const contractsRoot = path.join(repositoryRoot, 'libs', 'contracts');
const generatedRoot = path.join(contractsRoot, 'gen');
const buf = path.join(
  repositoryRoot,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'buf.cmd' : 'buf'
);

if (!fs.existsSync(buf)) {
  process.stderr.write(
    'The pinned Buf CLI is unavailable. Install workspace dependencies before running contracts:generate.\n'
  );
  process.exit(1);
}

fs.rmSync(generatedRoot, { recursive: true, force: true });

const generation = spawnSync(buf, ['generate'], {
  cwd: contractsRoot,
  encoding: 'utf8',
  stdio: 'inherit',
  shell: process.platform === 'win32',
});

if (generation.error) {
  process.stderr.write(`${generation.error.message}\n`);
  process.exit(1);
}

process.exit(generation.status ?? 1);

const { spawnSync } = require('node:child_process');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const contractsRoot = path.join(repositoryRoot, 'contracts');
const buf = path.join(
  repositoryRoot,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'buf.cmd' : 'buf'
);

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: contractsRoot,
    encoding: 'utf8',
    stdio: 'inherit',
    ...options,
  });
}

const baseline = spawnSync(
  'git',
  ['cat-file', '-e', 'origin/main:contracts/buf.yaml'],
  {
    cwd: repositoryRoot,
    stdio: 'ignore',
  }
);

if (baseline.status === 0) {
  process.exit(
    run(buf, [
      'breaking',
      '--against',
      `${repositoryRoot}/.git#branch=origin/main,subdir=contracts`,
    ]).status ?? 1
  );
}

console.log(
  'No contracts module exists on origin/main; validating the bootstrap schema instead.'
);
process.exit(run(buf, ['build']).status ?? 1);

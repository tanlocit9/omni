const { spawnSync } = require('node:child_process');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const contractsRoot = path.join(repositoryRoot, 'libs', 'contracts');
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
    shell: process.platform === 'win32',
    ...options,
  });
}

function baselineExists(subdir) {
  return (
    spawnSync('git', ['cat-file', '-e', `origin/main:${subdir}/buf.yaml`], {
      cwd: repositoryRoot,
      stdio: 'ignore',
    }).status === 0
  );
}

const baselineSubdir = baselineExists('libs/contracts')
  ? 'libs/contracts'
  : baselineExists('contracts')
  ? 'contracts'
  : null;

if (baselineSubdir !== null) {
  if (baselineSubdir === 'contracts') {
    console.log(
      'Using the transitional origin/main:contracts baseline; post-merge checks will use origin/main:libs/contracts.'
    );
  }
  process.exit(
    run(buf, [
      'breaking',
      '--against',
      `${repositoryRoot}/.git#branch=origin/main,subdir=${baselineSubdir}`,
    ]).status ?? 1
  );
}

console.log(
  'No contracts module exists on origin/main; validating the bootstrap schema instead.'
);
process.exit(run(buf, ['build']).status ?? 1);

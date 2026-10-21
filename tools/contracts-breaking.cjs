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
    windowsHide: true,
    shell: process.platform === 'win32',
    ...options,
  });
}

function resolveBaseline(baseline, git) {
  // Only immutable SHAs are accepted; refs must be resolved by the caller.
  if (!/^[a-f0-9]{40}$/.test(baseline || '') || /^0+$/.test(baseline)) {
    throw new Error('CONTRACTS_BASE_SHA must be a nonzero full commit SHA.');
  }
  if (git(['cat-file', '-e', `${baseline}^{commit}`]).status !== 0) {
    throw new Error(`Contract baseline commit is unavailable: ${baseline}`);
  }
  for (const subdir of ['libs/contracts', 'contracts']) {
    if (
      git(['cat-file', '-e', `${baseline}:${subdir}/buf.yaml`]).status === 0
    ) {
      return subdir;
    }
  }
  throw new Error(
    `No contracts module at baseline ${baseline}; breaking check cannot run.`
  );
}

if (require.main === module) {
  try {
    const baseline = process.env.CONTRACTS_BASE_SHA;
    const subdir = resolveBaseline(baseline, (args) =>
      spawnSync('git', args, {
        cwd: repositoryRoot,
        stdio: 'ignore',
        windowsHide: true,
      })
    );
    console.log(`Checking contracts against ${baseline}:${subdir}`);
    process.exit(
      run(buf, [
        'breaking',
        '--against',
        `${repositoryRoot}/.git#ref=${baseline},subdir=${subdir}`,
      ]).status ?? 1
    );
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = { resolveBaseline };

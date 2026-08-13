const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const contractsRoot = path.join(repositoryRoot, 'contracts');
const generatedRoot = path.join(contractsRoot, 'gen');
const buf = path.join(
  repositoryRoot,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'buf.cmd' : 'buf'
);

function digestDirectory(root) {
  if (!fs.existsSync(root)) {
    return '<missing>';
  }
  const hash = crypto.createHash('sha256');
  const visit = (directory) => {
    const entries = fs
      .readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else {
        hash.update(path.relative(root, absolutePath));
        hash.update(fs.readFileSync(absolutePath));
      }
    }
  };
  visit(root);
  return hash.digest('hex');
}

const before = digestDirectory(generatedRoot);
const generation = spawnSync(buf, ['generate'], {
  cwd: contractsRoot,
  encoding: 'utf8',
  stdio: 'inherit',
});
if (generation.status !== 0) {
  process.exit(generation.status ?? 1);
}

const after = digestDirectory(generatedRoot);
if (before !== after) {
  process.stderr.write(
    'Generated contract outputs are stale. Run nx run contracts:generate and commit the result.\n'
  );
  process.exit(1);
}

console.log('Generated Java and Python contract outputs are current.');

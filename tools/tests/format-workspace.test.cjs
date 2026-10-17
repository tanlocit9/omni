const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { gitFiles, pythonFiles, regularFile } = require('../format-workspace.cjs');

test('changed selection handles staged, unstaged, untracked and ignored files', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'omni-format-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const git = (args) => {
    const result = spawnSync('git', args, { cwd: directory, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout;
  };
  git(['init', '--quiet']);
  fs.writeFileSync(path.join(directory, '.gitignore'), '*.log\n');
  fs.writeFileSync(path.join(directory, 'staged file.js'), 'staged');
  fs.writeFileSync(path.join(directory, 'deleted.py'), 'delete me');
  git(['add', '--', '.gitignore', 'staged file.js', 'deleted.py']);
  fs.writeFileSync(path.join(directory, 'staged file.js'), 'unstaged');
  fs.unlinkSync(path.join(directory, 'deleted.py'));
  fs.writeFileSync(path.join(directory, '[new].js'), 'new');
  fs.writeFileSync(path.join(directory, 'debug.log'), 'ignored');

  const selected = gitFiles(false, git).filter((file) =>
    regularFile(file, directory)
  );
  assert.deepEqual(selected.sort(), ['.gitignore', '[new].js', 'staged file.js']);
  assert.equal(selected.filter((file) => file === 'staged file.js').length, 1);
});

test('changed mode never asks Git for all tracked files; all mode does', () => {
  const calls = [];
  const git = (args) => {
    calls.push(args);
    if (args.includes('--cached')) return 'unchanged.js\0space name.js\0';
    return '';
  };
  assert.deepEqual(gitFiles(true, git), ['unchanged.js', 'space name.js']);
  assert.ok(
    calls.some((args) => args[0] === 'ls-files' && args.includes('--cached'))
  );
  calls.length = 0;
  gitFiles(false, git);
  assert.ok(
    !calls.some((args) => args[0] === 'ls-files' && args.includes('--cached'))
  );
});

test('NUL parsing preserves unusual names and removes duplicate paths', () => {
  const name = 'space and\nnewline.js';
  assert.deepEqual(gitFiles(false, () => `${name}\0${name}\0`), [name]);
  assert.deepEqual(gitFiles(false, () => ''), []);
});

test('Python routing preserves project ownership and existing lint roots', () => {
  const files = [
    'apps/ingestor/app/handler.py',
    'apps/analyzer/tests/test_handler.py',
    'apps/query-service/main.py',
    'libs/py-common/py_common/types.pyi',
    'apps/ingestor/main.py',
    'apps/ingestor/app/config.json',
    'apps/ingestor/pyproject.toml',
    'apps/ingestor/app/../../core/example.py',
  ];
  assert.deepEqual(pythonFiles(files, 'ingestor'), [files[0]]);
  assert.deepEqual(pythonFiles(files, 'analyzer'), [files[1]]);
  assert.deepEqual(pythonFiles(files, 'query-service'), [files[2]]);
  assert.deepEqual(pythonFiles(files, 'py-common'), [files[3]]);
  assert.deepEqual(pythonFiles([], 'ingestor'), []);
  assert.throws(() => pythonFiles(files, 'other'), /Unknown Python project/);
});

test('filesystem selection rejects deleted files, directories and outside paths', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'omni-format-path-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(path.join(directory, 'safe.js'), 'safe');
  assert.equal(regularFile('safe.js', directory), true);
  assert.equal(regularFile('deleted.js', directory), false);
  assert.equal(regularFile('.', directory), false);
  assert.equal(regularFile('../outside.js', directory), false);
});

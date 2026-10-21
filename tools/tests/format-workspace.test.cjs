const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const {
  gitFiles,
  pythonFiles,
  javaFiles,
  routeJava,
  regularFile,
  formatPrettierFiles,
} = require('../format-workspace.cjs');

test('changed selection handles staged, unstaged, untracked and ignored files', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'omni-format-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const git = (args) => {
    const result = spawnSync('git', args, {
      cwd: directory,
      encoding: 'utf8',
      windowsHide: true,
    });
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
  assert.deepEqual(selected.sort(), [
    '.gitignore',
    '[new].js',
    'staged file.js',
  ]);
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
  assert.deepEqual(
    gitFiles(false, () => `${name}\0${name}\0`),
    [name]
  );
  assert.deepEqual(
    gitFiles(false, () => ''),
    []
  );
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

test('Java routing limits formatting to canonical Platform source roots', () => {
  const files = [
    'apps/core/src/main/java/example/Main.java',
    'apps/core/src/test/java/example/MainTest.java',
    'libs/contracts/gen/java/Generated.java',
    'apps/core/build/generated/Generated.java',
    'apps/core/src/main/java/example/Main.kt',
    'apps/core/src/main/java/../../../../external/Other.java',
    'apps\\core\\src\\main\\java\\Other.java',
    '/apps/core/src/main/java/Absolute.java',
  ];
  assert.deepEqual(javaFiles(files), files.slice(0, 2));
});

test('Java selection survives unknown Prettier parser and respects ignore rules', async () => {
  const java = 'apps/core/src/main/java/example/Main.java';
  const ignored = 'apps/core/src/test/java/example/Ignored.java';
  const prettier = {
    getFileInfo: async (file) => ({
      ignored: file.endsWith('Ignored.java'),
      inferredParser: null,
    }),
    format: () => assert.fail('Prettier must not try to format Java'),
  };
  const selected = await formatPrettierFiles(
    [java, ignored, 'unknown.bin'],
    prettier
  );
  assert.deepEqual(javaFiles(selected), [java]);
});

test('Java batches use only the Platform target and preserve filenames as data', () => {
  const files = Array.from(
    { length: 23 },
    (_, i) => `apps/core/src/main/java/example/File ${i}.java`
  );
  files[0] = 'apps/core/src/main/java/example/quote"and\nnewline.java';
  const calls = [];
  routeJava([...files, 'docs/example.md'], (command, args, options) => {
    calls.push({
      command,
      args,
      files: JSON.parse(options.env.OMNI_FORMAT_FILES),
    });
  });
  assert.equal(calls.length, 2);
  assert.deepEqual(
    calls.map((call) => call.files.length),
    [20, 3]
  );
  assert.deepEqual(
    calls.flatMap((call) => call.files),
    files
  );
  for (const call of calls) {
    assert.equal(call.command, 'nx');
    assert.deepEqual(call.args, ['run', 'platform:format']);
  }
});

test('Java routing is a no-op without Java and propagates formatter failures', () => {
  routeJava(['docs/example.md'], () =>
    assert.fail('Unexpected Java invocation')
  );
  assert.throws(
    () =>
      routeJava(['apps/core/src/main/java/Bad.java'], () => {
        throw new Error('Java parse failed');
      }),
    /Java parse failed/
  );
});

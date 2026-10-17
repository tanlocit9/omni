const assert = require('node:assert/strict');
const { test } = require('node:test');
const { selectProjects, gateErrors, projects } = require('../ci-policy.cjs');
const { resolveBaseline } = require('../contracts-breaking.cjs');

const sha = 'a'.repeat(40);

test('contract baseline requires an explicit immutable commit', () => {
  for (const value of [undefined, '', 'origin/main', 'HEAD', '0'.repeat(40)]) {
    assert.throws(() =>
      resolveBaseline(value, () => assert.fail('must not call git'))
    );
  }
});

test('missing commit and absent schema fail instead of falling back to build', () => {
  assert.throws(
    () => resolveBaseline(sha, () => ({ status: 1 })),
    /unavailable/
  );
  assert.throws(
    () =>
      resolveBaseline(sha, (args) => ({
        status: args[2].endsWith('^{commit}') ? 0 : 1,
      })),
    /No contracts module/
  );
});

test('baseline uses the requested SHA and supports the previous module location', () => {
  for (const subdir of ['libs/contracts', 'contracts']) {
    const calls = [];
    assert.equal(
      resolveBaseline(sha, (args) => {
        calls.push(args[2]);
        return {
          status: [`${sha}^{commit}`, `${sha}:${subdir}/buf.yaml`].includes(
            args[2]
          )
            ? 0
            : 1,
        };
      }),
      subdir
    );
    assert.ok(calls.every((ref) => ref.startsWith(sha)));
  }
});

test('Nx local selection and docs-only changes remain bounded', () => {
  const selected = selectProjects(
    ['omni-console'],
    ['apps/omni-console/src/App.tsx']
  );
  assert.equal(selected.typescript, true);
  assert.equal(selected.java, false);
  assert.ok(
    Object.values(selectProjects([], ['docs/README.md'])).every((v) => !v)
  );
});

test('cross-service inputs run every stack even when Nx reports no affected projects', () => {
  for (const file of [
    'libs/contracts/proto/job.proto',
    'libs/py-common/py_common/storage.py',
    'configs/shared/topics.yaml',
    'configs/shared/s3-paths.yaml',
    'database/migrations/V1.sql',
    'tools/contracts-breaking.cjs',
    'apps/core/src/main/java/JobCommand.java',
    'apps/analyzer/app/handler.py',
    'apps/ingestor/app/handler.py',
    'apps/query-service/app/api.py',
    '.github/workflows/python-ci.yml',
    'docker-compose.yaml',
    'nx.json',
    'project.json',
    'package-lock.json',
    'pyproject.toml',
    'uv.lock',
  ]) {
    assert.ok(Object.values(selectProjects([], [file])).every(Boolean), file);
  }
  assert.ok(Object.values(selectProjects(['omni'], [])).every(Boolean));
  assert.ok(Object.values(selectProjects([], [], true)).every(Boolean));
  assert.throws(() => selectProjects({}, []));
});

function needsFor(selected) {
  const outputs = Object.fromEntries(
    Object.entries(selected).map(([k, v]) => [k, String(v)])
  );
  const needs = { affected: { result: 'success', outputs } };
  for (const job of [...Object.keys(projects), 'ingestor-integration']) {
    needs[job] = {
      result: selected[job === 'ingestor-integration' ? 'ingestor' : job]
        ? 'success'
        : 'skipped',
    };
  }
  return needs;
}

test('gate accepts valid skips and successful selected jobs', () => {
  assert.deepEqual(gateErrors(needsFor(selectProjects([], []))), []);
  assert.deepEqual(gateErrors(needsFor(selectProjects(['ingestor'], []))), []);
  assert.deepEqual(gateErrors(needsFor(selectProjects([], [], true))), []);
});

test('gate rejects detection failure, malformed outputs and every required job failure', () => {
  for (const result of ['failure', 'cancelled', 'skipped']) {
    assert.ok(gateErrors({ affected: { result } }).length);
  }
  const missing = needsFor(selectProjects([], []));
  delete missing.affected.outputs.contracts;
  assert.ok(gateErrors(missing).length);
  for (const job of [...Object.keys(projects), 'ingestor-integration']) {
    for (const result of ['failure', 'cancelled', 'skipped', undefined]) {
      const needs = needsFor(selectProjects([], [], true));
      needs[job].result = result;
      assert.ok(gateErrors(needs).length, `${job}: ${result}`);
    }
  }
  const unexpected = needsFor(selectProjects([], []));
  unexpected.java.result = 'failure';
  assert.ok(gateErrors(unexpected).length);
});

const assert = require('node:assert/strict');
const { test } = require('node:test');
const {
  commandEnvironment,
  hiddenProcessOptions,
} = require('../process-options.cjs');

test('Windows defaults quiet Nx descendants without changing the caller environment', () => {
  const environment = { PATH: 'custom-path' };
  assert.deepEqual(commandEnvironment(environment, 'win32'), {
    PATH: 'custom-path',
    NX_NO_CLOUD: 'true',
    NX_NATIVE_COMMAND_RUNNER: 'false',
    NX_TUI: 'false',
  });
  assert.deepEqual(environment, { PATH: 'custom-path' });
});

test('explicit caller settings are retained, including empty strings', () => {
  const environment = {
    NX_NO_CLOUD: 'false',
    NX_NATIVE_COMMAND_RUNNER: 'true',
    NX_TUI: '',
  };
  assert.deepEqual(commandEnvironment(environment, 'win32'), environment);
});

test('non-Windows commands retain their environment without adding local Nx defaults', () => {
  const environment = { CI: 'true', PATH: '/bin' };
  const result = commandEnvironment(environment, 'linux');
  assert.deepEqual(result, environment);
  assert.notEqual(result, environment);
});

test('hidden options preserve shell requirements, stdio, cwd and additional environment', () => {
  const environment = { OMNI_FORMAT_FILES: '["file with spaces.java"]' };
  const options = {
    cwd: 'C:\\repo with spaces',
    shell: true,
    stdio: 'inherit',
    encoding: 'utf8',
    env: environment,
  };
  const result = hiddenProcessOptions(options, 'win32');
  assert.equal(result.windowsHide, true);
  assert.equal(result.shell, true);
  assert.equal(result.cwd, options.cwd);
  assert.equal(result.stdio, 'inherit');
  assert.equal(result.encoding, 'utf8');
  assert.equal(result.env.OMNI_FORMAT_FILES, environment.OMNI_FORMAT_FILES);
  assert.equal(result.env.NX_NO_CLOUD, 'true');
  assert.equal(options.windowsHide, undefined);
  assert.deepEqual(environment, { OMNI_FORMAT_FILES: '["file with spaces.java"]' });
});

test('native executables can explicitly avoid a shell', () => {
  const result = hiddenProcessOptions({ shell: false, env: {} }, 'win32');
  assert.equal(result.shell, false);
  assert.equal(result.windowsHide, true);
});

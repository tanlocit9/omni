// Shared child-process defaults; this module never launches a process.
function commandEnvironment(environment = process.env, platform = process.platform) {
  const childEnvironment = { ...environment };
  if (platform === 'win32') {
    for (const [name, value] of Object.entries({
      NX_NO_CLOUD: 'true',
      NX_NATIVE_COMMAND_RUNNER: 'false',
      NX_TUI: 'false',
    })) {
      if (childEnvironment[name] === undefined) childEnvironment[name] = value;
    }
  }
  return childEnvironment;
}

function hiddenProcessOptions(options = {}, platform = process.platform) {
  return {
    windowsHide: true,
    ...options,
    env: commandEnvironment(options.env ?? process.env, platform),
  };
}

module.exports = { commandEnvironment, hiddenProcessOptions };

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const pythonRoots = {
  analyzer: 'apps/analyzer',
  ingestor: 'apps/ingestor',
  'query-service': 'apps/query-service',
  'py-common': 'libs/py-common',
};

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: 'utf8',
    stdio: 'inherit',
    ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} failed (exit ${result.status}).`);
  }
  return result.stdout;
}

function gitFiles(all, git = (args) => run('git', args, { stdio: 'pipe' })) {
  const lists = all
    ? [git(['ls-files', '--cached', '-z'])]
    : [
        git(['diff', '--name-only', '--diff-filter=ACMRT', '--no-renames', '-z']),
        git([
          'diff',
          '--cached',
          '--name-only',
          '--diff-filter=ACMRT',
          '--no-renames',
          '-z',
        ]),
      ];
  lists.push(git(['ls-files', '--others', '--exclude-standard', '-z']));
  return [...new Set(lists.flatMap((list) => list.split('\0').filter(Boolean)))];
}

function pythonFiles(files, project) {
  const projectRoot = pythonRoots[project];
  if (!projectRoot) throw new Error(`Unknown Python project: ${project}`);
  return files.filter((file) => {
    if (path.posix.normalize(file) !== file || file.includes('\\')) return false;
    if (!file.startsWith(`${projectRoot}/`)) return false;
    const relative = file.slice(projectRoot.length + 1);
    if (!/\.(py|pyi)$/.test(relative)) return false;
    return project === 'py-common'
      ? /^(py_common|tests)\//.test(relative)
      : /^(app|tests)\//.test(relative) ||
          (project === 'query-service' && relative === 'main.py');
  });
}

function regularFile(file, workspace = root) {
  const absolute = path.resolve(workspace, file);
  const relative = path.relative(workspace, absolute);
  if (relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    return false;
  }
  try {
    // Do not follow symlinks, including symlinked parent directories outside root.
    const real = path.relative(workspace, fs.realpathSync(absolute));
    return (
      !real.startsWith(`..${path.sep}`) &&
      !path.isAbsolute(real) &&
      fs.lstatSync(absolute).isFile()
    );
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return false;
    throw error;
  }
}

function formatPython(project) {
  const selected = JSON.parse(process.env.OMNI_FORMAT_FILES || '[]');
  if (
    !Array.isArray(selected) ||
    !selected.every((file) => typeof file === 'string')
  ) {
    throw new Error('OMNI_FORMAT_FILES must be an array of repository paths.');
  }
  const files = pythonFiles(selected, project).filter((file) => regularFile(file));
  if (!files.length) return;
  const cwd = path.join(root, pythonRoots[project]);
  // Explicit files respect Ruff excludes; argv avoids shell/glob interpretation.
  for (let offset = 0; offset < files.length; offset += 20) {
    const batch = files
      .slice(offset, offset + 20)
      .map((file) => path.join(root, file));
    run(
      'uv',
      ['run', 'ruff', 'check', '--fix-only', '--force-exclude', '--', ...batch],
      { cwd }
    );
    run(
      'uv',
      ['run', 'ruff', 'format', '--force-exclude', '--', ...batch],
      { cwd }
    );
    run(
      'uv',
      [
        'run',
        'ruff',
        'check',
        '--no-fix',
        '--no-fix-only',
        '--force-exclude',
        '--',
        ...batch,
      ],
      { cwd }
    );
  }
}

async function main(args) {
  if (args.length === 1 && args[0].startsWith('--python=')) {
    formatPython(args[0].slice('--python='.length));
    return;
  }
  if (args.some((arg) => !['--all', '--all=true', '--all=false'].includes(arg))) {
    throw new Error('Usage: nx run omni:format [--all]');
  }
  const all = args.includes('--all') || args.includes('--all=true');
  const candidates = gitFiles(all).filter((file) => regularFile(file));
  if (!candidates.length) {
    console.log('No files to format.');
    return;
  }
  const prettier = require('prettier');
  const files = [];
  for (const file of candidates) {
    const absolute = path.join(root, file);
    const info = await prettier.getFileInfo(absolute, {
      ignorePath: path.join(root, '.prettierignore'),
    });
    if (info.ignored) continue;
    files.push(file);
    if (!info.inferredParser) continue;
    const config = await prettier.resolveConfig(absolute, { editorconfig: true });
    const before = fs.readFileSync(absolute, 'utf8');
    const after = await prettier.format(before, { ...config, filepath: absolute });
    if (after !== before) fs.writeFileSync(absolute, after);
    console.log(file);
  }
  for (const project of Object.keys(pythonRoots)) {
    const selected = pythonFiles(files, project);
    if (!selected.length) continue;
    // Only fixed project/target names enter this Windows command shell.
    run('nx', ['run', `${project}:lint-fix`, '--configuration=changed'], {
      shell: process.platform === 'win32',
      env: { ...process.env, OMNI_FORMAT_FILES: JSON.stringify(selected) },
    });
  }
}

if (require.main === module) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

module.exports = { gitFiles, pythonFiles, regularFile };

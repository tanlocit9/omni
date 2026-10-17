const fs = require('node:fs');

const projects = {
  analyzer: 'analyzer',
  contracts: 'contracts',
  ingestor: 'ingestor',
  java: 'platform',
  'py-common': 'py-common',
  'query-service': 'query-service',
  typescript: 'omni-console',
};

// Nx cannot infer every Kafka, storage, configuration or cross-language edge.
// Keep this conservative policy aligned with docs/development/004-ci-quality-gate.md.
function selectProjects(affected, changedFiles, forceAll = false) {
  if (
    !Array.isArray(affected) ||
    !affected.every((name) => typeof name === 'string')
  ) {
    throw new Error('Affected project list must be an array of project names.');
  }
  const shared = changedFiles.some(
    (file) =>
      /^(apps\/(core|analyzer|ingestor|query-service)\/|libs\/|configs\/|database\/|tools\/|\.github\/|docker-compose[^/]*$)/.test(
        file
      ) ||
      /^(nx\.json|project\.json|package(-lock)?\.json|pyproject\.toml|uv\.lock|\.nxignore|\.prettierignore|\.prettierrc[^/]*|tsconfig[^/]*)$/.test(
        file
      )
  );
  const all = forceAll || shared || affected.includes('omni');
  return Object.fromEntries(
    Object.entries(projects).map(([job, project]) => [
      job,
      all || affected.includes(project),
    ])
  );
}

function gateErrors(needs) {
  if (needs.affected?.result !== 'success') {
    return ['Affected detection must succeed.'];
  }
  const errors = [];
  for (const job of [...Object.keys(projects), 'ingestor-integration']) {
    const output = job === 'ingestor-integration' ? 'ingestor' : job;
    const selected = needs.affected.outputs?.[output];
    const result = needs[job]?.result;
    if (!['true', 'false'].includes(selected)) {
      errors.push(`${job}: missing or invalid selection output`);
    } else if (selected === 'true' && result !== 'success') {
      errors.push(`${job}: required job ended with ${result}`);
    } else if (selected === 'false' && result !== 'skipped') {
      errors.push(`${job}: unselected job unexpectedly ended with ${result}`);
    }
  }
  return errors;
}

if (require.main === module) {
  if (process.argv[2] === 'select') {
    const [, , , affectedFile, changedFile, outputFile, event] = process.argv;
    const selected = selectProjects(
      JSON.parse(fs.readFileSync(affectedFile, 'utf8')),
      fs.readFileSync(changedFile, 'utf8').split('\0').filter(Boolean),
      event === 'workflow_dispatch'
    );
    console.log(JSON.stringify(selected, null, 2));
    fs.appendFileSync(
      outputFile,
      Object.entries(selected)
        .map(([k, v]) => `${k}=${v}\n`)
        .join('')
    );
  } else if (process.argv[2] === 'gate') {
    const errors = gateErrors(JSON.parse(process.env.CI_NEEDS));
    errors.forEach((error) => console.error(error));
    process.exitCode = errors.length ? 1 : 0;
  } else {
    throw new Error('Expected select or gate command.');
  }
}

module.exports = { selectProjects, gateErrors, projects };

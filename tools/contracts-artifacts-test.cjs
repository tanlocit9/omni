const fs = require('node:fs');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');
const expectedArtifacts = [
  'contracts/gen/java/com/omni/contracts/common/v1/DatasetRef.java',
  'contracts/gen/java/com/omni/contracts/common/v1/DatasetOutput.java',
  'contracts/gen/java/com/omni/contracts/common/v1/ExecutionStatus.java',
  'contracts/gen/java/com/omni/contracts/job/v1/JobCommand.java',
  'contracts/gen/java/com/omni/contracts/job/v1/JobStatusEvent.java',
  'contracts/gen/python/omni/contracts/common/v1/dataset_pb2.py',
  'contracts/gen/python/omni/contracts/common/v1/execution_pb2.py',
  'contracts/gen/python/omni/contracts/job/v1/job_command_pb2.py',
  'contracts/gen/python/omni/contracts/job/v1/job_status_pb2.py',
];

const missing = expectedArtifacts.filter(
  (artifact) => !fs.existsSync(path.join(repositoryRoot, artifact))
);
if (missing.length > 0) {
  process.stderr.write(
    `Missing generated contract artifacts:\n${missing.join('\n')}\n`
  );
  process.exit(1);
}

console.log(
  `Verified ${expectedArtifacts.length} generated Java/Python contract artifacts.`
);

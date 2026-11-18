import { useEffect, useState } from 'react';
import { ApiError } from '../api';
import { getAttemptReadiness } from './api';
import type { AtomicRequestMember, WorkReadinessProof } from './types';

export function OutputReadinessPanel({
  member,
}: {
  member: AtomicRequestMember;
}) {
  return (
    <AttemptEvidence
      key={`${member.atomicWorkId}:${member.atomicAttemptId}:${member.executionId}`}
      member={member}
    />
  );
}

function AttemptEvidence({ member }: { member: AtomicRequestMember }) {
  const [proofs, setProofs] = useState<WorkReadinessProof[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setProofs(null);
    setError(null);
    getAttemptReadiness(
      member.atomicWorkId,
      member.atomicAttemptId,
      abort.signal
    )
      .then((items) => {
        if (!abort.signal.aborted) {
          if (items.some((item) => item.executionId !== member.executionId)) {
            setError('Proof identity does not match the selected execution.');
          } else setProofs(items);
        }
      })
      .catch((reason) => {
        if (abort.signal.aborted) return;
        setError(
          reason instanceof ApiError && reason.status === 401
            ? 'Your private operator session is missing or expired.'
            : reason instanceof ApiError && reason.status === 403
            ? 'This operator cannot read output evidence.'
            : 'Selected-attempt proof is unavailable. Current work coverage is not a substitute.'
        );
      });
    return () => abort.abort();
  }, [
    member.atomicWorkId,
    member.atomicAttemptId,
    member.executionId,
    revision,
  ]);

  return (
    <section
      className="panel work-panel output-evidence"
      aria-label="Output evidence"
    >
      <div className="panel-heading">
        <h3>Output evidence</h3>
        <button type="button" onClick={() => setRevision((value) => value + 1)}>
          Refresh proof
        </button>
      </div>
      <p>
        Selected attempt: <code>{member.atomicAttemptId}</code>
      </p>
      <p>
        Selected execution: <strong>{member.executionStatus}</strong> ·{' '}
        <code>{member.executionId}</code>
      </p>
      <h4>Current work output</h4>
      <p>
        Coverage:{' '}
        <strong>
          {member.outputReady === null
            ? 'Unknown'
            : member.outputReady
            ? 'Ready'
            : 'Not ready'}
        </strong>
      </p>
      <p className="muted">
        Current work coverage may come from a different attempt. It does not
        certify the selected attempt or change its execution status. Refresh
        detail to reload current-work coverage; Refresh proof reloads only
        selected-attempt evidence.
      </p>
      {member.readyOutputs.length === 0 ? (
        <p>No current output proofs are listed.</p>
      ) : (
        <div className="work-table">
          <table>
            <thead>
              <tr>
                <th>Dataset / partition / day</th>
                <th>Data version</th>
                <th>Providing attempt / execution</th>
              </tr>
            </thead>
            <tbody>
              {member.readyOutputs.map((proof, index) => (
                <tr key={`${proof.dataset}:${proof.dataVersion}:${index}`}>
                  <td>
                    {proof.dataset}
                    <p>
                      <code>{JSON.stringify(proof.partition)}</code>
                    </p>
                    {proof.businessDate ?? 'Unknown date'}
                  </td>
                  <td>
                    <code>{proof.dataVersion}</code>
                  </td>
                  <td>
                    <code>{proof.attemptId}</code>
                    <p>
                      <code>{proof.executionId}</code>
                    </p>
                    {(proof.attemptId !== member.atomicAttemptId ||
                      proof.executionId !== member.executionId) && (
                      <p className="callout warning">
                        Provided by a different attempt
                      </p>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h4>Selected-attempt retained proof</h4>
      {!proofs && !error && (
        <p role="status">Loading selected-attempt proof…</p>
      )}
      {error && (
        <p role="alert" className="callout warning">
          {error}
        </p>
      )}
      {proofs?.length === 0 && (
        <p>
          No retained READY proof is available for this attempt. This does not
          prove no physical data exists.
        </p>
      )}
      {proofs?.map((proof, index) => (
        <div className="schema-card" key={`${proof.dataVersion}:${index}`}>
          <h4>
            {proof.output.dataset} · {proof.businessDate}
          </h4>
          <p>
            Partition: <code>{JSON.stringify(proof.output.partition)}</code>
          </p>
          <p>
            Data version: <code>{proof.dataVersion}</code>
          </p>
          <p>Rows: {proof.output.rowCount ?? 'Unknown'}</p>
          <p>
            Schema hash: <code>{proof.output.schemaHash ?? 'Unknown'}</code>
          </p>
          <h4>Frozen approved inputs</h4>
          {proof.approvedInputs.length === 0 ? (
            <p>No approved inputs are listed.</p>
          ) : (
            <ul>
              {proof.approvedInputs.map((input, inputIndex) => (
                <li key={`${input.dataset}:${inputIndex}`}>
                  {input.dataset} ·{' '}
                  <code>{JSON.stringify(input.partition)}</code> ·{' '}
                  <code>{input.dataVersion}</code>
                </li>
              ))}
            </ul>
          )}
          <h4>Frozen upstream work attempts</h4>
          {proof.upstreamWorks.length === 0 ? (
            <p>No upstream work attempts are listed.</p>
          ) : (
            <ul>
              {proof.upstreamWorks.map((upstream) => (
                <li key={upstream.atomicAttemptId}>
                  <code>{upstream.atomicWorkId}</code> ·{' '}
                  <code>{upstream.atomicAttemptId}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
      <p className="muted">
        These are catalog proofs, not a fresh Parquet-byte or provider
        validation. Missing or unavailable proof never falls back to the latest
        work proof.
      </p>
    </section>
  );
}

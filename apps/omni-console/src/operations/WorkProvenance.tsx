import { useEffect, useState } from 'react';
import { inspectAtomicWork } from './api';
import type { WorkInspection } from './types';

interface Props {
  atomicWorkId: string;
  onSelectRequest?: (requestId: string) => void;
  onSelectWork?: (atomicWorkId: string) => void;
}

export function WorkProvenance(props: Props) {
  return <ProvenanceContent key={props.atomicWorkId} {...props} />;
}

function ProvenanceContent({
  atomicWorkId,
  onSelectRequest,
  onSelectWork,
}: Props) {
  const [detail, setDetail] = useState<WorkInspection | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setDetail(null);
    setError(null);
    inspectAtomicWork(atomicWorkId, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) setDetail(value);
      })
      .catch(() => {
        if (!abort.signal.aborted)
          setError(
            'Work provenance is unavailable; missing evidence must not be interpreted as no dependencies.'
          );
      });
    return () => abort.abort();
  }, [atomicWorkId, revision]);

  return (
    <section className="panel work-panel" aria-label="Work provenance">
      <div className="panel-heading">
        <h3>Work provenance</h3>
        <button type="button" onClick={() => setRevision((value) => value + 1)}>
          Refresh provenance
        </button>
      </div>
      <code>{atomicWorkId}</code>
      {error && (
        <p role="alert" className="callout warning">
          {error}
        </p>
      )}
      {!detail && !error && <p>Loading work provenance…</p>}
      {detail && (
        <>
          <p className="muted">
            One-hop upstreams and request owners. Execution status is separate
            from output READY. Trace presence does not prove propagation or
            query-level persistence.
          </p>
          {(detail.attemptsTruncated ||
            detail.ownersTruncated ||
            detail.upstreamsTruncated) && (
            <p className="callout warning">
              Work provenance is truncated; missing links are not proof of no
              dependency.
            </p>
          )}
          <h4>Attempts and approved inputs</h4>
          {!detail.attempts.length && (
            <p>No attempts are listed in this response.</p>
          )}
          <div className="work-table">
            <table>
              <thead>
                <tr>
                  <th>Attempt / execution</th>
                  <th>Execution / identity</th>
                  <th>Frozen approved inputs</th>
                </tr>
              </thead>
              <tbody>
                {detail.attempts.map((attempt) => (
                  <tr key={attempt.atomicAttemptId}>
                    <td>
                      <code>{attempt.atomicAttemptId}</code>
                      <p>
                        <code>{attempt.executionId}</code>
                      </p>
                    </td>
                    <td>
                      {attempt.status}
                      <p>
                        Identity:{' '}
                        {attempt.identityMatches ? 'Match' : 'Mismatch'}
                      </p>
                      <p>
                        {attempt.traceContextPresent
                          ? 'Trace context recorded'
                          : 'No trace context recorded'}
                      </p>
                    </td>
                    <td>
                      {attempt.approvedInputs === null ? (
                        'Approved inputs not reported'
                      ) : attempt.approvedInputs.length === 0 ? (
                        'No approved inputs listed'
                      ) : (
                        <ul>
                          {attempt.approvedInputs.map((input, index) => (
                            <li key={`${input.dataset}:${index}`}>
                              {input.dataset} ·{' '}
                              <code>{JSON.stringify(input.partition)}</code> ·{' '}
                              <code>{input.dataVersion}</code>
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h4>Request owners</h4>
          {!detail.owners.length && (
            <p>No owners are listed in this response.</p>
          )}
          <div className="work-table">
            <table>
              <thead>
                <tr>
                  <th>Request</th>
                  <th>Attempt</th>
                  <th>Request state / role</th>
                </tr>
              </thead>
              <tbody>
                {detail.owners.map((owner) => (
                  <tr key={`${owner.graphRequestId}:${owner.atomicAttemptId}`}>
                    <td>
                      {onSelectRequest ? (
                        <button
                          className="work-link"
                          type="button"
                          onClick={() => onSelectRequest(owner.graphRequestId)}
                        >
                          {owner.graphRequestId}
                        </button>
                      ) : (
                        <code>{owner.graphRequestId}</code>
                      )}
                    </td>
                    <td>
                      <code>{owner.atomicAttemptId}</code>
                    </td>
                    <td>
                      {owner.state} · {owner.isTarget ? 'Target' : 'Upstream'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h4>Upstream work attempts</h4>
          {!detail.upstreams.length && (
            <p>No upstream links are listed in this response.</p>
          )}
          <div className="work-table">
            <table>
              <thead>
                <tr>
                  <th>Dependent attempt</th>
                  <th>Upstream work / attempt</th>
                  <th>Upstream execution / status</th>
                </tr>
              </thead>
              <tbody>
                {detail.upstreams.map((upstream) => (
                  <tr
                    key={`${upstream.atomicAttemptId}:${upstream.upstreamAtomicAttemptId}`}
                  >
                    <td>
                      <code>{upstream.atomicAttemptId}</code>
                    </td>
                    <td>
                      {onSelectWork ? (
                        <button
                          className="work-link"
                          type="button"
                          onClick={() =>
                            onSelectWork(upstream.upstreamAtomicWorkId)
                          }
                        >
                          {upstream.upstreamAtomicWorkId}
                        </button>
                      ) : (
                        <code>{upstream.upstreamAtomicWorkId}</code>
                      )}
                      <p>
                        <code>{upstream.upstreamAtomicAttemptId}</code>
                      </p>
                    </td>
                    <td>
                      <code>{upstream.upstreamExecutionId}</code>
                      <p>{upstream.status}</p>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <details>
            <summary>Advanced provenance JSON</summary>
            <pre className="work-json">{JSON.stringify(detail, null, 2)}</pre>
          </details>
        </>
      )}
    </section>
  );
}

import { useEffect, useState } from 'react';
import { ApiError } from '../api';
import { getAtomicRequestDetail } from './api';
import { OutputReadinessPanel } from './OutputReadinessPanel';
import type { AtomicRequestDetail } from './types';

interface Props {
  requestId: string;
  onClose: () => void;
  onInspectWork?: (atomicWorkId: string) => void;
}

function timestamp(value: string | null) {
  return value
    ? new Date(value).toLocaleString('en-GB', { timeZone: 'Asia/Ho_Chi_Minh' })
    : 'Unknown';
}

function errorText(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 404)
      return 'This request no longer exists or is unavailable.';
    if (error.status === 401)
      return 'Your private operator session is missing or expired.';
    if (error.status === 403)
      return 'This operator cannot read request details.';
  }
  return 'Request detail is unavailable. Execution and output state remain unknown.';
}

export function RequestDetail(props: Props) {
  return <DetailContent key={props.requestId} {...props} />;
}

function DetailContent({ requestId, onClose, onInspectWork }: Props) {
  const [detail, setDetail] = useState<AtomicRequestDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState('');
  const [role, setRole] = useState('all');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [selectedAttempt, setSelectedAttempt] = useState<string | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    setDetail(null);
    setError(null);
    setPage(0);
    setSelectedAttempt(null);
    getAtomicRequestDetail(requestId, abort.signal)
      .then((result) => {
        if (!abort.signal.aborted) setDetail(result);
      })
      .catch((reason) => {
        if (!abort.signal.aborted) setError(errorText(reason));
      });
    return () => abort.abort();
  }, [requestId, revision]);

  const members =
    detail?.members.filter(
      (member) =>
        (role === 'all' || member.isTarget === (role === 'target')) &&
        (!status || member.executionStatus === status) &&
        [member.atomicWorkId, member.atomicAttemptId, member.executionId].some(
          (value) => value.toLowerCase().includes(search.trim().toLowerCase())
        )
    ) ?? [];
  const shown = members.slice(page * 50, (page + 1) * 50);
  const selected = detail?.members.find(
    (member) => member.atomicAttemptId === selectedAttempt
  );

  return (
    <section className="panel work-panel" aria-label="Request detail">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Read-only evidence</p>
          <h3>Request detail</h3>
        </div>
        <div className="actions">
          <button
            type="button"
            onClick={() => setRevision((value) => value + 1)}
          >
            Refresh detail
          </button>
          <button type="button" onClick={onClose}>
            Close request
          </button>
        </div>
      </div>
      <code>{requestId}</code>
      <p className="muted">
        Times are in Asia/Ho_Chi_Minh. This endpoint loads the complete member
        and graph response. Frontend pages limit rendered rows only. Refresh is
        manual.
      </p>
      {!detail && !error && <p role="status">Loading request detail…</p>}
      {error && (
        <p role="alert" className="callout warning">
          {error}
        </p>
      )}
      {detail && (
        <>
          <div className="stat-grid work-stats">
            <div>
              <span>Request state</span>
              <strong>{detail.request.state}</strong>
            </div>
            <div>
              <span>Member settlement</span>
              <strong>{detail.isSettled ? 'Settled' : 'Not settled'}</strong>
            </div>
            <div>
              <span>Unfinished members</span>
              <strong>{detail.unfinishedMembers}</strong>
            </div>
            <div>
              <span>Member failures</span>
              <strong>
                {detail.hasFailure ? 'Reported' : 'None reported'}
              </strong>
            </div>
          </div>
          <p>
            Execution: <code>{detail.request.executionId}</code>
          </p>
          <p>
            Target definition: <code>{detail.request.targetDefinitionId}</code>
          </p>
          <p>Created: {timestamp(detail.request.createdAt)}</p>
          <p className="muted">
            Request closure, member settlement and output READY are separate
            observations.
          </p>
          <div className="work-toolbar">
            <label>
              Find member
              <input
                value={search}
                onChange={(event) => {
                  setSearch(event.target.value);
                  setPage(0);
                }}
                placeholder="Work, attempt or execution ID"
              />
            </label>
            <label>
              Member role
              <select
                value={role}
                onChange={(event) => {
                  setRole(event.target.value);
                  setPage(0);
                }}
              >
                <option value="all">All roles</option>
                <option value="target">Target</option>
                <option value="upstream">Upstream</option>
              </select>
            </label>
            <label>
              Member execution status
              <select
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                {[
                  ...new Set(
                    detail.members.map((member) => member.executionStatus)
                  ),
                ]
                  .sort()
                  .map((value) => (
                    <option key={value}>{value}</option>
                  ))}
              </select>
            </label>
          </div>
          {!detail.members.length && (
            <p>
              No members are recorded for this request. This does not establish
              request completion.
            </p>
          )}
          {!!detail.members.length && !members.length && (
            <p>No members match these filters.</p>
          )}
          {!!shown.length && (
            <div className="work-table">
              <table>
                <thead>
                  <tr>
                    <th>Work / role</th>
                    <th>Attempt / execution</th>
                    <th>Execution / outbox</th>
                    <th>Times</th>
                    <th>Review / outputs</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((member) => (
                    <tr
                      key={member.atomicAttemptId}
                      className={
                        member.atomicAttemptId === selectedAttempt
                          ? 'selected-member'
                          : undefined
                      }
                    >
                      <td>
                        <code>{member.atomicWorkId}</code>
                        <p>{member.isTarget ? 'Target' : 'Upstream'}</p>
                      </td>
                      <td>
                        <code>{member.atomicAttemptId}</code>
                        <p>
                          <code>{member.executionId}</code>
                        </p>
                      </td>
                      <td>
                        <strong>{member.executionStatus}</strong>
                        <p>Outbox: {member.outboxStatus ?? 'Unknown'}</p>
                      </td>
                      <td>
                        Started: {timestamp(member.startedAt)}
                        <p>Finished: {timestamp(member.finishedAt)}</p>
                      </td>
                      <td>
                        <p>Reason: {member.reasonCode ?? 'None reported'}</p>
                        {member.orphaned && (
                          <p className="callout warning">
                            No active request owner
                          </p>
                        )}
                        {member.requiresExecutionReview && (
                          <p className="callout warning">
                            Execution review required
                          </p>
                        )}
                        <button
                          type="button"
                          onClick={() =>
                            setSelectedAttempt(member.atomicAttemptId)
                          }
                          aria-label={`View outputs ${member.atomicAttemptId}`}
                        >
                          View outputs
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {!!members.length && (
            <div className="actions">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                Previous members
              </button>
              <span>
                Member page {page + 1} of {Math.ceil(members.length / 50)} ·{' '}
                {members.length} matching members
              </span>
              <button
                type="button"
                disabled={(page + 1) * 50 >= members.length}
                onClick={() => setPage((value) => value + 1)}
              >
                Next members
              </button>
            </div>
          )}
          {selected && (
            <>
              {onInspectWork && (
                <button
                  type="button"
                  onClick={() => onInspectWork(selected.atomicWorkId)}
                >
                  Inspect work provenance
                </button>
              )}
              <OutputReadinessPanel
                key={`${selected.atomicWorkId}:${selected.atomicAttemptId}`}
                member={selected}
              />
            </>
          )}
        </>
      )}
    </section>
  );
}

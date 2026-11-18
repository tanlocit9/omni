import { useEffect, useState } from 'react';
import { ApiError } from '../api';
import { listAtomicRequests } from './api';
import type {
  AtomicRequestCursor,
  AtomicRequestFilter,
  AtomicRequestPage,
  AtomicRequestQuery,
} from './types';

interface View {
  status: AtomicRequestFilter;
  observedAt?: string;
  cursor?: AtomicRequestCursor;
  revision: number;
  refreshToken: number;
}

function requestError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401)
      return 'Your private operator session is missing or expired.';
    if (error.status === 403) return 'This operator cannot read work requests.';
    if (error.status === 503)
      return 'Work requests are unavailable. Check Platform schema and database health.';
  }
  return error instanceof Error
    ? error.message
    : 'Work requests are unavailable.';
}

function counts(value: Record<string, number>) {
  const entries = Object.entries(value).sort(([left], [right]) =>
    left.localeCompare(right)
  );
  return entries.length
    ? entries.map(([state, count]) => `${state}: ${count}`).join(' · ')
    : 'No member statuses';
}

function time(value: string | null) {
  return value
    ? new Date(value).toLocaleString('en-GB', {
        timeZone: 'Asia/Ho_Chi_Minh',
        hour12: false,
      })
    : 'Unknown';
}

function elapsed(value: number | null) {
  if (value === null) return 'Unknown';
  const seconds = Math.floor(value / 1000);
  return `${Math.floor(seconds / 60)} min ${seconds % 60} sec`;
}

export function RequestList({
  refreshToken = 0,
  acceptedRequestId,
  onSelectRequest,
}: {
  refreshToken?: number;
  acceptedRequestId?: string;
  onSelectRequest?: (requestId: string) => void;
}) {
  const [view, setView] = useState<View>({
    status: 'active',
    revision: 0,
    refreshToken,
  });
  // A newly accepted request resets filters/cursors before issuing the next read.
  const current: View =
    view.refreshToken === refreshToken
      ? view
      : { status: 'active', revision: 0, refreshToken };
  const { status, observedAt, cursor, revision } = current;
  const beforeCreatedAt = cursor?.beforeCreatedAt;
  const beforeId = cursor?.beforeId;
  const [page, setPage] = useState<AtomicRequestPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const query: AtomicRequestQuery = { status, limit: 20 };
    if (observedAt) query.observedAt = observedAt;
    if (beforeCreatedAt && beforeId) {
      query.beforeCreatedAt = beforeCreatedAt;
      query.beforeId = beforeId;
    }
    setPage(null);
    setError(null);
    const load = async () => {
      setLoading(true);
      try {
        const result = await listAtomicRequests(query, abort.signal);
        if (!abort.signal.aborted) {
          setPage(result);
          setError(null);
        }
      } catch (reason) {
        if (!abort.signal.aborted) setError(requestError(reason));
      } finally {
        if (!abort.signal.aborted) {
          setLoading(false);
          if (status === 'active' && !beforeId)
            timer = setTimeout(() => void load(), 5000);
        }
      }
    };
    void load();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [status, observedAt, beforeCreatedAt, beforeId, revision, refreshToken]);

  return (
    <section className="panel work-panel" aria-label="Work requests">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Request monitoring</p>
          <h3>Work requests</h3>
        </div>
        <button
          type="button"
          onClick={() =>
            setView({ status, revision: revision + 1, refreshToken })
          }
        >
          Refresh requests
        </button>
      </div>
      <div className="work-toolbar">
        <label>
          Request filter
          <select
            value={status}
            onChange={(event) =>
              setView({
                status: event.target.value as AtomicRequestFilter,
                revision: revision + 1,
                refreshToken,
              })
            }
          >
            <option value="active">Active</option>
            <option value="history">History</option>
            <option value="all">All</option>
          </select>
        </label>
      </div>
      <p className="muted">
        {status === 'active' && !beforeId
          ? 'The first active page refreshes every 5 seconds after each response.'
          : 'Automatic refresh is paused for this view. Refresh returns to the first page.'}{' '}
        Times are in Asia/Ho_Chi_Minh. Elapsed time starts at orchestration
        acceptance, including queue and dependency wait; it is not worker time.
      </p>
      <p className="muted">
        Member counts belong to each request and include upstream jobs. Shared
        attempts can appear in multiple requests; do not sum these counts as
        unique executions. Request closure does not prove all members have
        settled.
      </p>
      {acceptedRequestId && (
        <p className="callout">
          Accepted request: <code>{acceptedRequestId}</code>. If it is absent
          from this page, check History or All; absence does not prove
          completion.
        </p>
      )}
      <div aria-live="polite">
        {loading && (
          <p>{page ? 'Refreshing requests…' : 'Loading requests…'}</p>
        )}
        {error && (
          <p role="alert" className="callout warning">
            {error}{' '}
            {page
              ? 'Showing stale data from the last successful observation.'
              : 'Request state is unknown; this is not an empty result.'}
          </p>
        )}
      </div>
      {page && (
        <>
          <p className="work-clock">Observed UTC: {page.observedAt}</p>
          {!page.items.length && !error && (
            <p>
              {status === 'active'
                ? 'No active requests on this page. Check History for terminal requests.'
                : 'No requests match this page.'}
            </p>
          )}
          {!!page.items.length && (
            <div className="work-table" aria-busy={loading}>
              <table>
                <thead>
                  <tr>
                    <th>Request / goal</th>
                    <th>Request state</th>
                    <th>Members / settlement</th>
                    <th>Accepted / elapsed</th>
                    <th>Records</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((request) => (
                    <tr key={request.requestId}>
                      <td>
                        <strong>{request.goal}</strong> · {request.mode}
                        <br />
                        <code>{request.requestId}</code>
                        {onSelectRequest && (
                          <p>
                            <button
                              type="button"
                              onClick={() => onSelectRequest(request.requestId)}
                              aria-label={`View request ${request.requestId}`}
                            >
                              View request
                            </button>
                          </p>
                        )}
                        {request.requestId === acceptedRequestId && (
                          <p className="status-chip ready">Newly accepted</p>
                        )}
                        <p>
                          Execution: <code>{request.executionId}</code>
                        </p>
                      </td>
                      <td>
                        <span className="status-chip">{request.state}</span>
                        <p>
                          {request.hasFailure
                            ? 'Member failures reported'
                            : 'No member failures reported'}
                        </p>
                      </td>
                      <td>
                        <p>Total members: {request.totalMembers}</p>
                        <p>{counts(request.memberCounts)}</p>
                        <p>Unfinished members: {request.unfinishedMembers}</p>
                        <p>
                          {request.isSettled
                            ? 'Members settled'
                            : 'Members not settled'}
                        </p>
                        <details>
                          <summary>
                            Job breakdown ({request.jobs.length})
                          </summary>
                          <ul>
                            {request.jobs.map((job) => (
                              <li key={job.jobType}>
                                <strong>{job.jobType}</strong>:{' '}
                                {job.totalMembers} members
                                <br />
                                {counts(job.memberCounts)}
                              </li>
                            ))}
                          </ul>
                        </details>
                      </td>
                      <td>
                        {time(request.createdAt)}
                        <p>Elapsed: {elapsed(request.elapsedMs)}</p>
                        <p>Finished: {time(request.finishedAt)}</p>
                      </td>
                      <td>
                        <p>Synced: {request.recordsSynced ?? 'Unknown'}</p>
                        <p>Skipped: {request.recordsSkipped ?? 'Unknown'}</p>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {page.hasMore && page.nextCursor && (
            <button
              type="button"
              disabled={loading || !!error}
              onClick={() =>
                setView({
                  status,
                  observedAt: page.observedAt,
                  cursor: page.nextCursor ?? undefined,
                  revision: revision + 1,
                  refreshToken,
                })
              }
            >
              Next requests page
            </button>
          )}
        </>
      )}
    </section>
  );
}

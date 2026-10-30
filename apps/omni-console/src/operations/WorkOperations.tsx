import { useEffect, useState } from 'react';
import { ApiError } from '../api';
import { getAtomicSnapshot, getPublishedUnfinished, inspectAtomicWork, type SnapshotWindow } from './api';
import type { AtomicSnapshot, PublishedPage, WorkInspection } from './types';

function errorText(error: unknown) {
  if (error instanceof ApiError && error.status === 401) return 'Your private operator session is missing or expired.';
  if (error instanceof ApiError && error.status === 403) return 'This operator cannot read work diagnostics.';
  if (error instanceof ApiError && error.status === 503) return 'Work diagnostics are unavailable. Check Platform schema and database health.';
  return error instanceof Error ? error.message : 'Work diagnostics are unavailable.';
}
function age(seconds: number | null) {
  return seconds === null ? 'Unknown' : `${Math.floor(seconds / 60)} min`;
}

export function WorkOperations() {
  const [window, setWindow] = useState<SnapshotWindow>({ hours: 24 });
  const [snapshot, setSnapshot] = useState<AtomicSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [status, setStatus] = useState('');
  const [selectedWork, setSelectedWork] = useState<string | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    setLoading(true); setError(null); setSnapshot(null); setSelectedWork(null);
    getAtomicSnapshot(window, abort.signal)
      .then(value => { if (!abort.signal.aborted) setSnapshot(value); })
      .catch(reason => { if (!abort.signal.aborted) setError(errorText(reason)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [window]);

  const attempts = snapshot?.attempts.filter(attempt =>
    (!status || attempt.status === status) &&
    [attempt.jobType, attempt.workKey, attempt.atomicWorkId, attempt.executionId]
      .some(value => value?.toLowerCase().includes(filter.toLowerCase()))
  ) ?? [];
  const counts = snapshot ? [snapshot.requests.length, snapshot.attempts.length,
    snapshot.attempts.filter(attempt => attempt.publishedUnfinishedOver30m).length,
    snapshot.attempts.filter(attempt => attempt.ownerCount > 1).length] : null;

  return <section className="work-operations" aria-label="Work operations">
    <section className="panel work-panel">
      <div className="panel-heading"><div><p className="eyebrow">P14 · read-only</p><h2>Work Operations</h2></div>
        <button type="button" disabled={loading} onClick={() => setWindow({ hours: window.hours })}>Refresh snapshot</button></div>
      <div className="work-toolbar"><label>Request creation window
        <select value={window.hours} onChange={event => setWindow({ hours: Number(event.target.value) })}>
          <option value={1}>Last hour</option><option value={24}>Last 24 hours</option><option value={168}>Last 7 days</option>
        </select></label></div>
      <p className="muted">Requests and distinct attempts in this page only. Shared attempts can recur across pages; do not sum pages.</p>
      <div aria-live="polite">{loading && <p>Loading work snapshot…</p>}{error && <p role="alert" className="callout warning">{error}</p>}</div>
      {snapshot && <><p className="work-clock">Captured UTC: {snapshot.capturedAt} · observation UTC: {snapshot.observedAt}</p>
        <p className="work-clock">Requests created in [{snapshot.since}, {snapshot.until})</p>
        {(snapshot.attemptsTruncated || snapshot.edgesTruncated) && <p className="callout warning">Partial detail: {snapshot.attemptsTruncated && 'attempts capped at 1,000. '}{snapshot.edgesTruncated && 'edges capped at 2,000.'}</p>}
        {snapshot.hasMoreRequests && <p className="callout warning">More requests exist. Counts are not global backlog.</p>}</>}
      <div className="stat-grid work-stats">{['Requests on page', 'Distinct sampled attempts', 'Published unfinished >30m (sample)', 'Attempts with multiple owners'].map((label, index) =>
        <div key={label}><span>{label}</span><strong>{counts?.[index] ?? '—'}</strong></div>)}</div>
      <p className="callout warning">RUNNING can mean preparation. Publication is transport acknowledgement, not worker start. Worker processing and dataset READY are unknown here.</p>
      <p className="muted">Metadata registration and watchdog are planned I10. Query tracing is reserved, not implemented. This view never changes execution or readiness state.</p>
    </section>
    <section className="panel work-panel"><h3>Atomic attempts</h3>
      <div className="work-toolbar"><label>Find work or execution<input value={filter} onChange={event => setFilter(event.target.value)} placeholder="Job type, work key, identity" /></label>
        <label>Execution status<select value={status} onChange={event => setStatus(event.target.value)}><option value="">All</option>{['PENDING','RUNNING','SUCCESS','FAILED','ERROR','BLOCKED'].map(value => <option key={value}>{value}</option>)}</select></label></div>
      {!loading && snapshot && !attempts.length && <p>No attempts in this sample match the filters.</p>}
      <div className="work-table"><table><thead><tr><th>Work / date</th><th>Attempt / execution</th><th>Status / evidence</th><th>Owners / upstream</th><th>Outbox</th><th>Publish age</th></tr></thead><tbody>
        {attempts.map(attempt => <tr key={attempt.atomicAttemptId}><td><strong>{attempt.jobType}</strong><br />{attempt.workKey ?? 'Unknown'} · {attempt.businessDate ?? 'Unknown'}<br />
          <button className="work-link" type="button" onClick={() => setSelectedWork(attempt.atomicWorkId)}>{attempt.atomicWorkId}</button></td>
          <td><code>{attempt.atomicAttemptId}</code><br /><code>{attempt.executionId}</code></td>
          <td>{attempt.status}<br /><small>{attempt.observation}</small>{(!attempt.identityMatches || attempt.statusIdentityMatches === false) && <p className="callout warning">Identity mismatch</p>}</td>
          <td>{attempt.activeOwnerCount} active / {attempt.ownerCount} total<br />{attempt.upstreamCount} upstream / {attempt.upstreamFailed} failed</td>
          <td>{attempt.publishedMessages} published / {attempt.pendingMessages} pending / {attempt.blockedMessages} blocked<br />{attempt.liveLeases} leases / {attempt.retryDelayedMessages} delayed / {attempt.expiredTokenMessages} expired tokens</td>
          <td>{age(attempt.publishAgeSeconds)}{attempt.publishedUnfinishedOver30m && <p className="callout warning">Investigate; not automatic FAILED</p>}</td></tr>)}
      </tbody></table></div>
      {snapshot?.hasMoreRequests && snapshot.nextCursor && <button type="button" disabled={loading} onClick={() => setWindow({hours: window.hours, since: snapshot.since, until: snapshot.until, observedAt: snapshot.observedAt, afterId: snapshot.nextCursor ?? undefined})}>Next request page</button>}
    </section>
    {selectedWork && <WorkDetail atomicWorkId={selectedWork} />}
    <PublishedUnfinished />
    {snapshot && <><section className="panel work-panel"><h3>Requests on page</h3><div className="work-table"><table><thead><tr><th>Graph request</th><th>State</th><th>Created UTC</th><th>Members / targets / sampled</th></tr></thead><tbody>
      {snapshot.requests.map(request => <tr key={request.graphRequestId}><td><code>{request.graphRequestId}</code></td><td>{request.state}</td><td>{request.createdAt}</td><td>{request.memberCount} / {request.targetCount} / {request.sampledMemberCount}</td></tr>)}
    </tbody></table></div>{!snapshot.requests.length && <p>No requests created in this window.</p>}</section>
    <section className="panel work-panel"><h3>Definition inventory</h3><p className="muted">Whole stored catalog, outside the request window. Schedule-enabled presets are not terminal entry counts.</p><div className="work-table"><table><thead><tr><th>Job type</th><th>Stored</th><th>Schedule-enabled</th><th>Retired aliases</th></tr></thead><tbody>
      {snapshot.definitions.map(definition => <tr key={definition.jobType}><td>{definition.jobType}</td><td>{definition.stored}</td><td>{definition.scheduleEnabled}</td><td>{definition.retired}</td></tr>)}
    </tbody></table></div></section></>}
  </section>;
}

function WorkDetail({ atomicWorkId }: { atomicWorkId: string }) {
  const [detail, setDetail] = useState<WorkInspection | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const abort = new AbortController();
    setDetail(null); setError(null);
    inspectAtomicWork(atomicWorkId, abort.signal)
      .then(value => { if (!abort.signal.aborted) setDetail(value); })
      .catch(reason => { if (!abort.signal.aborted) setError(errorText(reason)); });
    return () => abort.abort();
  }, [atomicWorkId]);
  return <section className="panel work-panel"><h3>Work provenance</h3><code>{atomicWorkId}</code>
    {error && <p role="alert" className="callout warning">{error}</p>}
    {!error && !detail && <p>Loading work provenance…</p>}
    {detail && <><p className="muted">One-hop upstreams and owners. Trace presence does not prove propagation or query-level persistence.</p>
      {(detail.attemptsTruncated || detail.ownersTruncated || detail.upstreamsTruncated) && <p className="callout warning">Work provenance is truncated; missing links are not proof of no dependency.</p>}
      <pre className="work-json">{JSON.stringify(detail, null, 2)}</pre></>}
  </section>;
}

function PublishedUnfinished() {
  const [page, setPage] = useState<{ afterId?: string; observedAt?: string }>({});
  const [result, setResult] = useState<PublishedPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true); setError(null); setResult(null);
    getPublishedUnfinished(page, abort.signal)
      .then(value => { if (!abort.signal.aborted) setResult(value); })
      .catch(reason => { if (!abort.signal.aborted) setError(errorText(reason)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [page]);
  return <section className="panel work-panel"><div className="panel-heading"><h3>Published unfinished over 30 minutes</h3><button type="button" disabled={loading} onClick={() => setPage({})}>Refresh publications</button></div>
    <p className="muted">Includes old legacy/I8/I9 work outside the request creation window. Message page, not distinct work count. No automatic timeout action.</p>
    {loading && <p>Loading unfinished publications…</p>}{error && <p role="alert" className="callout warning">{error}</p>}
    {result && <><p className="work-clock">Observation UTC: {result.observedAt}</p>{!result.rows.length && <p>No matching publications in this page.</p>}
      <div className="work-table"><table><thead><tr><th>Job / work</th><th>Execution</th><th>Status</th><th>Published UTC</th><th>Age</th></tr></thead><tbody>
        {result.rows.map(row => <tr key={row.messageId}><td>{row.jobType} · {row.workKey ?? 'Unknown'}<br /><code>{row.atomicWorkId ?? 'Legacy work without atomic identity'}</code></td><td><code>{row.executionId}</code></td><td>{row.status}</td><td>{row.publishedAt}</td><td>{age(row.publishAgeSeconds)}</td></tr>)}
      </tbody></table></div>{result.hasMore && result.nextCursor && <button type="button" disabled={loading} onClick={() => setPage({afterId: result.nextCursor ?? undefined, observedAt: result.observedAt})}>Next publication page</button>}</>}
  </section>;
}

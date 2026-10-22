import { useEffect, useState } from 'react';

import {
  ApiError,
  getJobDefinition,
  listJobDefinitions,
  type JobDefinitionDetail,
  type JobDefinitionSummary,
} from '../api';

function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'The job service is unavailable.';
  if (error.status === 401)
    return 'Your private operator session is missing or expired.';
  if (error.status === 403)
    return 'This operator is not allowed to perform that action.';
  if (error.status === 404)
    return 'The job definition or execution no longer exists.';
  if (error.status === 409)
    return error.message || 'The job is already owned by another execution.';
  if (error.status === 422)
    return error.message || 'The trigger request is invalid.';
  return error.message || `The job service returned ${error.status}.`;
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : '—';
}

export function JobsPanel() {
  const [jobs, setJobs] = useState<JobDefinitionSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<JobDefinitionDetail | null>(null);
  const [query, setQuery] = useState('');
  const [activeOnly, setActiveOnly] = useState(true);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setMessage(null);
      listJobDefinitions({
        q: query.trim() || undefined,
        active: activeOnly ? true : undefined,
      })
        .then((page) => {
          if (!current) return;
          setJobs(page.items);
          setSelectedId((value) =>
            value && page.items.some((job) => job.id === value)
              ? value
              : page.items[0]?.id ?? null
          );
        })
        .catch((error) => current && setMessage(describeError(error)))
        .finally(() => current && setLoading(false));
    }, 200);
    return () => {
      current = false;
      window.clearTimeout(timer);
    };
  }, [query, activeOnly]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    let current = true;
    getJobDefinition(selectedId)
      .then((value) => current && setDetail(value))
      .catch((error) => current && setMessage(describeError(error)));
    return () => {
      current = false;
    };
  }, [selectedId]);

  const selected = jobs.find((job) => job.id === selectedId) ?? null;

  return (
    <section className="jobs-grid" aria-label="Job operations">
      <aside className="panel jobs-list">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Operations</p>
            <h2>Job catalog</h2>
          </div>
          <span>{jobs.length}</span>
        </div>
        <label>
          <span className="field-label">Search jobs</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Type or source"
          />
        </label>
        <label className="checkbox-row">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(event) => setActiveOnly(event.target.checked)}
          />
          Active definitions only
        </label>
        <div className="stack-list" aria-busy={loading}>
          {loading && <p className="muted">Loading definitions…</p>}
          {!loading && jobs.length === 0 && (
            <p className="muted">No matching definitions.</p>
          )}
          {jobs.map((job) => (
            <button
              key={job.id}
              className={`list-item ${selectedId === job.id ? 'active' : ''}`}
              onClick={() => setSelectedId(job.id)}
            >
              <strong>{job.title || job.jobType}</strong>
              <small>
                {job.source} · {job.jobType}
              </small>
              <span
                className={`status-chip ${job.active ? 'ready' : 'blocked'}`}
              >
                {job.active ? 'ACTIVE' : 'INACTIVE'}
              </span>
            </button>
          ))}
        </div>
      </aside>

      <div className="panel job-detail">
        <div aria-live="polite">
          {message && <p className="callout warning">{message}</p>}
        </div>
        {!selected && <p className="muted">Select a job definition.</p>}
        {selected && !detail && <p className="muted">Loading job details…</p>}
        {detail && (
          <>
            <div className="panel-heading">
              <div>
                <p className="eyebrow">{detail.source}</p>
                <h2>{detail.title || detail.jobType}</h2>
              </div>
              <span>{detail.active ? 'ACTIVE' : 'INACTIVE'}</span>
            </div>
            <div className="stat-grid job-stats">
              <div>
                <span>Job type</span>
                <strong>{detail.jobType}</strong>
              </div>
              <div>
                <span>Next scheduled run</span>
                <strong>{formatTime(detail.nextRun)}</strong>
              </div>
              <div>
                <span>Last status</span>
                <strong>{detail.lastExecution?.status ?? 'NEVER'}</strong>
              </div>
              <div>
                <span>Last triggered</span>
                <strong>{formatTime(detail.lastExecution?.triggeredAt)}</strong>
              </div>
            </div>

            <div className="job-sections">
              <section className="schema-card job-card">
                <h3>Dependencies</h3>
                <p>
                  <b>Jobs:</b>{' '}
                  {detail.dependencies.jobs.join(', ') || 'None declared'}
                </p>
                <p>
                  <b>Datasets:</b>{' '}
                  {detail.dependencies.datasets.join(', ') || 'None declared'}
                </p>
                <p>
                  <b>Produces:</b>{' '}
                  {detail.dependencies.produces.join(', ') || 'None declared'}
                </p>
              </section>
            </div>

            <section className="recent-runs">
              <h3>Recent executions</h3>
              {detail.recentExecutions.length === 0 ? (
                <p className="muted">No execution history.</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>Status</th>
                      <th>Triggered</th>
                      <th>Finished</th>
                      <th>Records</th>
                      <th>Execution</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.recentExecutions.map((run) => (
                      <tr key={run.id}>
                        <td>{run.status}</td>
                        <td>{formatTime(run.triggeredAt)}</td>
                        <td>{formatTime(run.finishedAt)}</td>
                        <td>{run.recordsSynced ?? '—'}</td>
                        <td>
                          <code>{run.id}</code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </>
        )}
      </div>
    </section>
  );
}

import { useEffect, useMemo, useRef, useState } from 'react';

import {
  ApiError,
  getJobDefinition,
  getTriggerStatus,
  listJobDefinitions,
  triggerJob,
  type JobDefinitionDetail,
  type JobDefinitionSummary,
  type JobExecutionSummary,
  type ManualTriggerResponse,
} from '../api';

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'ERROR', 'CANCELLED']);

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
  const [triggerableOnly, setTriggerableOnly] = useState(false);
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [trigger, setTrigger] = useState<ManualTriggerResponse | null>(null);
  const [execution, setExecution] = useState<JobExecutionSummary | null>(null);
  const pollGeneration = useRef(0);

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

  useEffect(
    () => () => {
      pollGeneration.current += 1;
    },
    []
  );

  const filtered = useMemo(
    () => (triggerableOnly ? jobs.filter((job) => job.triggerable) : jobs),
    [jobs, triggerableOnly]
  );

  const selected = useMemo(
    () => filtered.find((job) => job.id === selectedId) ?? null,
    [filtered, selectedId]
  );

  async function poll(requestId: string) {
    const generation = ++pollGeneration.current;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) =>
        window.setTimeout(resolve, Math.min(5000, 750 + attempt * 250))
      );
      if (generation !== pollGeneration.current) return;
      try {
        const status = await getTriggerStatus(requestId);
        setTrigger(status.trigger);
        setExecution(status.execution);
        if (
          status.trigger.state !== 'ACCEPTED' ||
          (status.execution && TERMINAL.has(status.execution.status))
        )
          return;
      } catch (error) {
        setMessage(describeError(error));
        return;
      }
    }
    setMessage(
      'Execution status polling timed out. The execution may still be running.'
    );
  }

  async function submitTrigger() {
    if (!detail || !reason.trim() || submitting) return;
    if (!window.confirm(`Trigger ${detail.jobType} for ${detail.source}?`))
      return;
    setSubmitting(true);
    setMessage(null);
    setExecution(null);
    pollGeneration.current += 1;
    try {
      const idempotencyKey = crypto.randomUUID();
      const response = await triggerJob(
        detail.id,
        reason.trim(),
        idempotencyKey
      );
      setTrigger(response);
      if (response.state === 'BLOCKED' || response.state === 'CONFLICT') {
        setMessage(response.blockReason ?? 'The trigger was not dispatched.');
      } else if (response.state === 'ACCEPTED') {
        setReason('');
        void poll(response.requestId);
      }
    } catch (error) {
      setMessage(describeError(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="jobs-grid" aria-label="Job operations">
      <aside className="panel jobs-list">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Operations</p>
            <h2>Job catalog</h2>
          </div>
          <span>{filtered.length}</span>
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
        <label className="checkbox-row">
          <input
            type="checkbox"
            checked={triggerableOnly}
            onChange={(event) => setTriggerableOnly(event.target.checked)}
          />
          Allow-list only (triggerable)
        </label>
        <div className="stack-list" aria-busy={loading}>
          {loading && <p className="muted">Loading definitions…</p>}
          {!loading && filtered.length === 0 && (
            <p className="muted">No matching definitions.</p>
          )}
          {filtered.map((job) => (
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
                className={`status-chip ${
                  job.triggerable ? 'ready' : 'blocked'
                }`}
              >
                {job.triggerable ? 'READY' : 'UNAVAILABLE'}
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
              <section className="schema-card job-card">
                <h3>Manual trigger</h3>
                {!detail.triggerable && (
                  <p className="callout warning">{detail.triggerBlockReason}</p>
                )}
                <label>
                  <span className="field-label">
                    Operational reason (required)
                  </span>
                  <input
                    value={reason}
                    maxLength={500}
                    disabled={!detail.triggerable || submitting}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder="Why is this run needed?"
                  />
                </label>
                <button
                  className="primary-button"
                  disabled={!detail.triggerable || !reason.trim() || submitting}
                  onClick={submitTrigger}
                >
                  {submitting ? 'Submitting…' : 'Review & trigger'}
                </button>
              </section>
            </div>

            <div>
              {trigger && (
                <section className="trigger-result">
                  <div>
                    <span>Request</span>
                    <code>{trigger.requestId}</code>
                  </div>
                  <div>
                    <span>Trigger state</span>
                    <strong>
                      {trigger.state}
                      {trigger.duplicate ? ' · IDEMPOTENT REPLAY' : ''}
                    </strong>
                  </div>
                  <div>
                    <span>Execution</span>
                    <code>{trigger.executionId ?? 'Not created'}</code>
                  </div>
                  <div>
                    <span>Execution state</span>
                    <strong>
                      {execution?.status ??
                        (trigger.state === 'ACCEPTED' ? 'PENDING' : '—')}
                    </strong>
                  </div>
                  {execution?.error && (
                    <p className="callout danger">{execution.error}</p>
                  )}
                </section>
              )}
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

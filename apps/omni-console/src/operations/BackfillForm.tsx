import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../api';
import { createBackfill, getBackfillGoals } from './api';
import type { BackfillAccepted, BackfillGoal, BackfillRequest } from './types';

type Policy = 'default' | 'recalculate' | 'refresh';

function vietnamToday() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const value = (type: string) =>
    parts.find((part) => part.type === type)?.value;
  return `${value('year')}-${value('month')}-${value('day')}`;
}

function submissionError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401)
      return 'Your private operator session is missing or expired.';
    if (error.status === 403)
      return 'This operator is not allowed to create backfills.';
    if (error.status === 400 || error.status === 422)
      return (
        error.message || 'The backfill scope was rejected. Review the fields.'
      );
    if (error.status === 409)
      return (
        error.message ||
        'The request conflicts with an existing request or target selection.'
      );
    if (error.status === 503)
      return `${
        error.message || 'The backfill service is unavailable.'
      } Acceptance could not be confirmed. Retry the unchanged request to reuse its idempotency key, or check request history.`;
  }
  return 'Acceptance could not be confirmed. Retry the unchanged request to reuse its idempotency key; do not assume no work was created.';
}

export function BackfillForm({
  onAccepted,
}: {
  onAccepted: (result: BackfillAccepted) => void;
}) {
  const [goals, setGoals] = useState<BackfillGoal[]>([]);
  const [loading, setLoading] = useState(true);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [goal, setGoal] = useState('');
  const [target, setTarget] = useState('');
  const [sectorLevel, setSectorLevel] = useState('1');
  const [sectorCodes, setSectorCodes] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [policy, setPolicy] = useState<Policy>('default');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accepted, setAccepted] = useState<BackfillAccepted | null>(null);
  const submission = useRef<{ payload: string; key: string } | null>(null);
  const submitting = useRef(false);
  const submitAbort = useRef<AbortController | null>(null);

  useEffect(() => () => submitAbort.current?.abort(), []);

  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setCatalogError(null);
    getBackfillGoals(abort.signal)
      .then((items) => {
        if (abort.signal.aborted) return;
        setGoals(items);
        setGoal(items[0]?.goal ?? '');
        setTarget(
          items[0]?.targets.length === 1
            ? items[0].targets[0].targetDefinitionId
            : ''
        );
        submission.current = null;
      })
      .catch((reason) => {
        if (abort.signal.aborted) return;
        setGoals([]);
        setCatalogError(
          reason instanceof ApiError && reason.status === 401
            ? 'Your private operator session is missing or expired.'
            : reason instanceof ApiError && reason.status === 403
            ? 'This operator cannot read backfill goals.'
            : 'Backfill goals are unavailable. Check Platform and retry discovery.'
        );
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [reload]);

  const targets = goals.find((item) => item.goal === goal)?.targets ?? [];
  const frozen = busy || accepted !== null;
  const today = vietnamToday();

  function changed() {
    setError(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || accepted || loading || catalogError) return;
    setError(null);
    const codes = [
      ...new Set(
        sectorCodes
          .split(/[,\n]/)
          .map((code) => code.trim().toUpperCase())
          .filter(Boolean)
      ),
    ].sort();
    const level = Number(sectorLevel);
    if (!goal || !targets.some((item) => item.targetDefinitionId === target)) {
      setError('Select an available goal and target definition.');
      return;
    }
    if (
      !Number.isInteger(level) ||
      level < 1 ||
      level > 4 ||
      codes.length < 1 ||
      codes.length > 100 ||
      codes.some((code) => !/^[A-Z0-9_-]{1,64}$/.test(code))
    ) {
      setError(
        'Enter a sector level from 1 to 4 and 1–100 valid sector codes.'
      );
      return;
    }
    const start = Date.parse(`${dateFrom}T00:00:00Z`);
    const end = Date.parse(`${dateTo}T00:00:00Z`);
    const validDay = (value: string, parsed: number) =>
      /^\d{4}-\d{2}-\d{2}$/.test(value) &&
      Number.isFinite(parsed) &&
      new Date(parsed).toISOString().slice(0, 10) === value;
    if (
      !validDay(dateFrom, start) ||
      !validDay(dateTo, end) ||
      end < start ||
      (end - start) / 86400000 + 1 > 366 ||
      dateTo > today
    ) {
      setError(
        'Choose an ordered range of 1–366 calendar days, ending no later than today in Vietnam.'
      );
      return;
    }
    const input: BackfillRequest = {
      goal,
      targetDefinitionId: target,
      sectorLevel: level,
      sectorCodes: codes,
      dateFrom,
      dateTo,
      rerun: policy !== 'default',
      force: policy === 'refresh',
    };
    const payload = JSON.stringify(input);
    const previous = submission.current;
    const key =
      previous && previous.payload === payload
        ? previous.key
        : crypto.randomUUID();
    submission.current = { payload, key };
    const abort = new AbortController();
    submitAbort.current = abort;
    submitting.current = true;
    setBusy(true);
    try {
      const result = await createBackfill(input, key, abort.signal);
      if (abort.signal.aborted) return;
      setAccepted(result);
      onAccepted(result);
    } catch (reason) {
      if (!abort.signal.aborted) setError(submissionError(reason));
    } finally {
      submitting.current = false;
      if (!abort.signal.aborted) setBusy(false);
    }
  }

  return (
    <section className="panel work-panel" aria-label="New backfill">
      <h3>New backfill</h3>
      <p className="muted">
        Choose a supported goal and current sector membership. Platform
        validates scope and dependencies when accepting the request.
      </p>
      {loading && <p role="status">Loading backfill goals…</p>}
      {catalogError && (
        <div className="callout warning" role="alert">
          <p>{catalogError}</p>
          <button type="button" onClick={() => setReload((value) => value + 1)}>
            Retry goal discovery
          </button>
        </div>
      )}
      {!loading && !catalogError && goals.length === 0 && (
        <p>No eligible backfill goals are available.</p>
      )}
      {!loading && !catalogError && goals.length > 0 && (
        <form className="backfill-form" onSubmit={submit} noValidate>
          <fieldset disabled={frozen}>
            <legend>Backfill scope</legend>
            <div className="work-toolbar">
              <label>
                Goal
                <select
                  value={goal}
                  onChange={(event) => {
                    changed();
                    setGoal(event.target.value);
                    const options =
                      goals.find((item) => item.goal === event.target.value)
                        ?.targets ?? [];
                    setTarget(
                      options.length === 1 ? options[0].targetDefinitionId : ''
                    );
                  }}
                >
                  {goals.map((item) => (
                    <option key={item.goal} value={item.goal}>
                      {item.goal}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Target definition
                <select
                  value={target}
                  onChange={(event) => {
                    changed();
                    setTarget(event.target.value);
                  }}
                >
                  <option value="">Select a target definition</option>
                  {targets.map((item) => (
                    <option
                      key={item.targetDefinitionId}
                      value={item.targetDefinitionId}
                    >
                      {item.title
                        ? `${item.title} (${item.targetDefinitionId})`
                        : item.targetDefinitionId}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Sector level
                <select
                  value={sectorLevel}
                  onChange={(event) => {
                    changed();
                    setSectorLevel(event.target.value);
                  }}
                >
                  {[1, 2, 3, 4].map((level) => (
                    <option key={level} value={level}>
                      {level}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Sector codes
                <textarea
                  value={sectorCodes}
                  onChange={(event) => {
                    changed();
                    setSectorCodes(event.target.value);
                  }}
                  placeholder="Comma-separated codes or one per line"
                  rows={2}
                />
              </label>
              <label>
                From date
                <input
                  type="date"
                  value={dateFrom}
                  max={today}
                  onChange={(event) => {
                    changed();
                    setDateFrom(event.target.value);
                  }}
                />
              </label>
              <label>
                To date
                <input
                  type="date"
                  value={dateTo}
                  max={today}
                  onChange={(event) => {
                    changed();
                    setDateTo(event.target.value);
                  }}
                />
              </label>
              <label>
                Execution policy
                <select
                  value={policy}
                  onChange={(event) => {
                    changed();
                    setPolicy(event.target.value as Policy);
                  }}
                >
                  <option value="default">Default</option>
                  <option value="recalculate">Recalculate</option>
                  <option value="refresh">
                    Refresh source and recalculate
                  </option>
                </select>
              </label>
            </div>
            <p className="muted">
              {policy === 'default'
                ? 'Share compatible work that is still in flight when possible. New EOD work prefers exact-day stored data and fetches if missing; calculations run on new attempts.'
                : policy === 'recalculate'
                ? 'Create new attempts throughout the chain. EOD prefers exact-day stored data and fetches if missing; recalculate from approved inputs.'
                : 'Create new attempts throughout the chain and refresh EOD from the provider, even when stored day rows exist. Recalculate from approved refreshed inputs.'}
            </p>
            <p className="muted">
              Discovery does not guarantee admission or READY. Missing dates are
              not inferred as no-trade days.
            </p>
            <button type="submit" disabled={frozen}>
              {busy ? 'Submitting backfill…' : 'Create backfill'}
            </button>
          </fieldset>
        </form>
      )}
      {error && (
        <p className="callout warning" role="alert">
          {error}
        </p>
      )}
      {accepted && (
        <div className="callout" role="status">
          <p>
            Backfill accepted. This is not execution completion or output READY.
          </p>
          <p>
            Request: <code>{accepted.requestId}</code>
          </p>
          <p>
            Execution: <code>{accepted.executionId}</code>
          </p>
          <button
            type="button"
            onClick={() => {
              submission.current = null;
              setAccepted(null);
              setError(null);
            }}
          >
            New request
          </button>
        </div>
      )}
    </section>
  );
}

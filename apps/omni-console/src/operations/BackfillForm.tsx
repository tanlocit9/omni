import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../api';
import { createBackfill, getBackfillGoals, getBackfillSectors } from './api';
import type {
  BackfillAccepted,
  BackfillGoal,
  BackfillRequest,
  BackfillSector,
} from './types';

type Policy = 'PREFER_EXISTING' | 'REFRESH';

function isoDate(value: string) {
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value);
  if (!match) return '';
  const day = `${match[3]}-${match[2]}-${match[1]}`;
  const parsed = Date.parse(`${day}T00:00:00Z`);
  return Number.isFinite(parsed) &&
    new Date(parsed).toISOString().slice(0, 10) === day
    ? day
    : '';
}

function DateEndpoint({
  label,
  value,
  max,
  onChange,
}: {
  label: string;
  value: string;
  max: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  return (
    <div className="date-range-endpoint">
      <label htmlFor={id}>{label}</label>
      <div className="date-range-value">
        <input
          id={id}
          type="text"
          inputMode="numeric"
          placeholder="dd/mm/yyyy"
          maxLength={10}
          value={value}
          onChange={(event) => {
            const digits = event.target.value.replace(/\D/g, '').slice(0, 8);
            onChange(
              [digits.slice(0, 2), digits.slice(2, 4), digits.slice(4)]
                .filter(Boolean)
                .join('/')
            );
          }}
        />
        <span className="date-range-calendar">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            aria-hidden="true"
          >
            <rect x="3" y="5" width="18" height="16" rx="2" />
            <path d="M16 3v4M8 3v4M3 11h18" />
          </svg>
          <input
            type="date"
            aria-label={`Choose ${label.toLowerCase()}`}
            value={isoDate(value)}
            max={max}
            onChange={(event) =>
              onChange(event.target.value.split('-').reverse().join('/'))
            }
          />
        </span>
      </div>
    </div>
  );
}

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
  const [sectorLevel, setSectorLevel] = useState('2');
  const [sectorCodes, setSectorCodes] = useState<string[]>([]);
  const [sectors, setSectors] = useState<BackfillSector[]>([]);
  const [sectorLoading, setSectorLoading] = useState(false);
  const [sectorError, setSectorError] = useState<string | null>(null);
  const [sectorCatalogLevel, setSectorCatalogLevel] = useState<number | null>(
    null
  );
  const [sectorReload, setSectorReload] = useState(0);
  const [exchange, setExchange] = useState('HOSE');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [policy, setPolicy] = useState<Policy>('PREFER_EXISTING');
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
  const symbolsSnapshot = goal === 'SYNC_SYMBOLS';

  useEffect(() => {
    const abort = new AbortController();
    setSectors([]);
    setSectorCatalogLevel(null);
    setSectorError(null);
    if (!goal || symbolsSnapshot) {
      setSectorLoading(false);
      return () => abort.abort();
    }
    setSectorLoading(true);
    const level = Number(sectorLevel);
    getBackfillSectors(level, abort.signal)
      .then((items) => {
        if (abort.signal.aborted) return;
        setSectors(items);
        setSectorCatalogLevel(level);
        setSectorCodes((selected) =>
          selected.filter((code) => items.some((item) => item.code === code))
        );
      })
      .catch(() => {
        if (abort.signal.aborted) return;
        setSectorError('Sector catalog is unavailable. Retry loading sectors.');
      })
      .finally(() => {
        if (!abort.signal.aborted) setSectorLoading(false);
      });
    return () => abort.abort();
  }, [goal, symbolsSnapshot, sectorLevel, sectorReload]);

  function changed() {
    setError(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || accepted || loading || catalogError) return;
    setError(null);
    const codes = [...new Set(sectorCodes)].sort();
    const level = Number(sectorLevel);
    if (!goal || !targets.some((item) => item.targetDefinitionId === target)) {
      setError('Select an available goal and target definition.');
      return;
    }
    if (
      !symbolsSnapshot &&
      (sectorLoading || sectorError || sectorCatalogLevel !== level)
    ) {
      setError(
        'Load the sector catalog for the selected level before submitting.'
      );
      return;
    }
    if (
      !symbolsSnapshot &&
      (!Number.isInteger(level) ||
        level < 1 ||
        level > 4 ||
        codes.length < 1 ||
        codes.length > 100 ||
        codes.some((code) => !sectors.some((item) => item.code === code)))
    ) {
      setError(
        'Select a sector level from 1 to 4 and 1-100 available sectors.'
      );
      return;
    }
    const fromDay = isoDate(dateFrom);
    const toDay = isoDate(dateTo);
    const start = Date.parse(`${fromDay}T00:00:00Z`);
    const end = Date.parse(`${toDay}T00:00:00Z`);
    if (
      !symbolsSnapshot &&
      (!fromDay ||
        !toDay ||
        end < start ||
        (end - start) / 86400000 + 1 > 366 ||
        toDay > today)
    ) {
      setError(
        'Enter dates as dd/mm/yyyy. Choose an ordered range of 1–366 calendar days, ending no later than today in Vietnam.'
      );
      return;
    }
    if (symbolsSnapshot && !['HOSE', 'HNX', 'UPCOM'].includes(exchange)) {
      setError('Select a supported exchange.');
      return;
    }
    const input: BackfillRequest = symbolsSnapshot
      ? {
          goal,
          targetDefinitionId: target,
          exchange,
          dataPolicy: policy,
        }
      : {
          goal,
          targetDefinitionId: target,
          sectorLevel: level,
          sectorCodes: codes,
          dateFrom: fromDay,
          dateTo: toDay,
          dataPolicy: policy,
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
                      {item.title ? `${item.title}` : item.targetDefinitionId}
                    </option>
                  ))}
                </select>
              </label>
              {symbolsSnapshot ? (
                <label>
                  Exchange
                  <select
                    value={exchange}
                    onChange={(event) => {
                      changed();
                      setExchange(event.target.value);
                    }}
                  >
                    {['HOSE', 'HNX', 'UPCOM'].map((value) => (
                      <option key={value} value={value}>
                        {value}
                      </option>
                    ))}
                  </select>
                </label>
              ) : (
                <>
                  <label>
                    Sector level
                    <select
                      value={sectorLevel}
                      onChange={(event) => {
                        changed();
                        if (event.target.value !== sectorLevel)
                          setSectorCodes([]);
                        setSectorLevel(event.target.value);
                      }}
                    >
                      {[1, 2, 3, 4].map((level) => (
                        <option key={level} value={level}>
                          {level === 2 ? '2 (Recommended)' : level}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div className="sector-picker-field">
                    <span id="sector-codes-label">Sector codes</span>
                    <details className="sector-picker">
                      <summary
                        aria-labelledby="sector-codes-label"
                        aria-disabled={frozen}
                        onClick={(event) => {
                          if (frozen) event.preventDefault();
                        }}
                      >
                        {sectorLoading
                          ? 'Loading sectors...'
                          : sectorCatalogLevel !== null &&
                            !sectorError &&
                            sectors.length === 0
                          ? 'No sectors available at this level'
                          : sectorCodes.length
                          ? `${sectorCodes.length} selected: ${sectorCodes
                              .slice(0, 3)
                              .join(', ')}${
                              sectorCodes.length > 3 ? ', ...' : ''
                            }`
                          : 'Select sector codes'}
                      </summary>
                      <div
                        className="sector-picker-options"
                        role="group"
                        aria-label="Available sectors"
                      >
                        {sectors.map((item) => (
                          <label key={item.code}>
                            <input
                              type="checkbox"
                              value={item.code}
                              checked={sectorCodes.includes(item.code)}
                              disabled={
                                frozen ||
                                (sectorCodes.length >= 100 &&
                                  !sectorCodes.includes(item.code))
                              }
                              onChange={(event) => {
                                changed();
                                const checked = event.target.checked;
                                setSectorCodes((selected) =>
                                  checked
                                    ? [...selected, item.code]
                                    : selected.filter(
                                        (code) => code !== item.code
                                      )
                                );
                              }}
                            />
                            <span>
                              {item.name === item.code
                                ? item.code
                                : `${item.code} - ${item.name}`}
                            </span>
                          </label>
                        ))}
                        {!sectorLoading &&
                          !sectorError &&
                          sectorCatalogLevel !== null &&
                          sectors.length === 0 && (
                            <p>
                              No sectors with active symbols are available at
                              this level.
                            </p>
                          )}
                      </div>
                    </details>
                    <small className="muted">
                      Level 2 is recommended. Select up to 100 sectors.
                    </small>
                    {sectorError && (
                      <div className="callout warning" role="alert">
                        <p>{sectorError}</p>
                        <button
                          type="button"
                          onClick={() => setSectorReload((value) => value + 1)}
                        >
                          Retry sector discovery
                        </button>
                      </div>
                    )}
                  </div>
                  <fieldset className="backfill-date-range">
                    <legend>Date range · dd/mm/yyyy</legend>
                    <div className="date-range-fields">
                      <DateEndpoint
                        label="From date"
                        value={dateFrom}
                        max={today}
                        onChange={(value) => {
                          changed();
                          setDateFrom(value);
                        }}
                      />
                      <span className="date-range-separator" aria-hidden="true">
                        →
                      </span>
                      <DateEndpoint
                        label="To date"
                        value={dateTo}
                        max={today}
                        onChange={(value) => {
                          changed();
                          setDateTo(value);
                        }}
                      />
                    </div>
                  </fieldset>
                </>
              )}
              <label>
                Data policy
                <select
                  value={policy}
                  onChange={(event) => {
                    changed();
                    setPolicy(event.target.value as Policy);
                  }}
                >
                  <option value="PREFER_EXISTING">Prefer existing data</option>
                  <option value="REFRESH">Refresh source data</option>
                </select>
              </label>
            </div>
            <p className="muted">
              {symbolsSnapshot
                ? 'Sync the current exchange symbol snapshot. Compatible in-flight work is shared automatically; completed work gets a new attempt.'
                : policy === 'PREFER_EXISTING'
                ? 'Share compatible in-flight work automatically. New EOD work uses exact-day stored data when available and fetches missing data.'
                : 'Refresh EOD from the provider. Pending work is upgraded before dispatch; dispatched work is shared only when its data policy satisfies this request.'}
            </p>
            <p className="muted">
              Discovery does not guarantee admission or READY. Missing dates are
              not inferred as no-trade days.
            </p>
            <button
              type="submit"
              disabled={
                frozen ||
                (!symbolsSnapshot &&
                  (sectorLoading ||
                    sectorError !== null ||
                    sectorCatalogLevel !== Number(sectorLevel) ||
                    sectors.length === 0))
              }
            >
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

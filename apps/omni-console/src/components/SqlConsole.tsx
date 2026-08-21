import Editor, { type Monaco } from '@monaco-editor/react';
import { useMemo, useRef, useState } from 'react';

import {
  cancelQuery,
  getArrowResult,
  submitQuery,
  waitForQuery,
  type DatasetManifest,
  type QueryResult,
  type QueryStatus,
} from '../api';
import { ResultTable } from './ResultTable';

interface Props {
  manifest: DatasetManifest | null;
}

interface HistoryItem {
  sql: string;
  ranAt: string;
  durationMs: number | null;
  state: string;
}

const HISTORY_KEY = 'omni-console.query-history.v1';

function initialHistory(): HistoryItem[] {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]') as HistoryItem[];
  } catch {
    return [];
  }
}

function csvValue(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

export function SqlConsole({ manifest }: Props) {
  const alias = manifest?.dataset.replaceAll('-', '_').replaceAll('.', '_') ?? 'dataset';
  const defaultSql = `SELECT *\nFROM "${alias}"\nLIMIT 200`;
  const [sql, setSql] = useState(defaultSql);
  const [queryId, setQueryId] = useState<string | null>(null);
  const [status, setStatus] = useState<QueryStatus | null>(null);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [history, setHistory] = useState<HistoryItem[]>(initialHistory);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const running = status?.state === 'QUEUED' || status?.state === 'RUNNING';

  const schemaSuggestions = useMemo(
    () => manifest?.columns.map((column) => column.name) ?? [],
    [manifest],
  );

  function configureMonaco(monaco: Monaco) {
    monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: () => ({
        suggestions: [alias, ...schemaSuggestions].map((label) => ({
          label,
          kind: monaco.languages.CompletionItemKind.Field,
          insertText: `"${label}"`,
          range: undefined as never,
        })),
      }),
    });
  }

  function recordHistory(finalStatus: QueryStatus) {
    const next = [
      { sql, ranAt: new Date().toISOString(), durationMs: finalStatus.durationMs, state: finalStatus.state },
      ...history.filter((item) => item.sql !== sql),
    ].slice(0, 20);
    setHistory(next);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(next));
  }

  async function run() {
    if (!manifest) { setError('Select a READY dataset first.'); return; }
    setError(null);
    setResult(null);
    abortRef.current?.abort();
    abortRef.current = new AbortController();
    try {
      const id = await submitQuery(
        sql,
        [{ dataset: manifest.dataset, partition: manifest.partition, dataVersion: manifest.dataVersion }],
        5000,
      );
      setQueryId(id);
      setStatus({
        queryId: id,
        state: 'QUEUED',
        createdAt: new Date().toISOString(),
        startedAt: null,
        completedAt: null,
        durationMs: null,
        rowCount: null,
        truncated: false,
        dataVersions: {},
        error: null,
      });
      const finalStatus = await waitForQuery(id, abortRef.current.signal);
      setStatus(finalStatus);
      recordHistory(finalStatus);
      if (finalStatus.state !== 'SUCCEEDED') throw new Error(finalStatus.error ?? finalStatus.state);
      setResult(await getArrowResult(id, finalStatus));
    } catch (reason) {
      if (reason instanceof DOMException && reason.name === 'AbortError') return;
      setError(reason instanceof Error ? reason.message : 'Query failed');
    }
  }

  async function cancel() {
    if (!queryId) return;
    abortRef.current?.abort();
    setStatus(await cancelQuery(queryId));
  }

  function exportCsv() {
    if (!result) return;
    const csv = [
      result.columns.map(csvValue).join(','),
      ...result.rows.map((row) => result.columns.map((column) => csvValue(row[column])).join(',')),
    ].join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    link.download = `omni-query-${queryId ?? 'result'}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  return (
    <section className="console-grid">
      <aside className="panel schema-browser">
        <div className="panel-heading"><h2>Schema</h2></div>
        {manifest ? (
          <>
            <button className="list-item active" onClick={() => setSql(defaultSql)}>
              <strong>{alias}</strong>
              <small>{manifest.dataVersion.slice(0, 20)}…</small>
            </button>
            <div className="schema-list">
              {manifest.columns.map((column) => (
                <button key={column.name} onClick={() => setSql(`${sql}\n-- ${column.name}: ${column.type}`)}>
                  <code>{column.name}</code><span>{column.type}</span>
                </button>
              ))}
            </div>
          </>
        ) : <div className="empty-state">Select a dataset in Explorer.</div>}
        <h3>Recent queries</h3>
        <div className="history-list">
          {history.map((item) => (
            <button key={`${item.ranAt}-${item.sql}`} onClick={() => setSql(item.sql)}>
              <code>{item.sql.split('\n')[0]}</code>
              <small>{item.state} · {item.durationMs ?? '—'} ms</small>
            </button>
          ))}
        </div>
      </aside>
      <main className="panel sql-workbench">
        <div className="panel-heading">
          <div><p className="eyebrow">Server-side DuckDB</p><h2>SQL Console</h2></div>
          <div className="actions">
            <button className="secondary" onClick={exportCsv} disabled={!result}>Export CSV</button>
            {running ? (
              <button className="danger" onClick={cancel}>Cancel</button>
            ) : (
              <button className="primary" onClick={run}>Run query</button>
            )}
          </div>
        </div>
        <div className="editor-shell">
          <Editor
            height="330px"
            defaultLanguage="sql"
            theme="vs-dark"
            value={sql}
            beforeMount={configureMonaco}
            onChange={(value) => setSql(value ?? '')}
            options={{ minimap: { enabled: false }, fontSize: 14, wordWrap: 'on' }}
          />
        </div>
        {status ? (
          <div className={`status-line ${status.state.toLowerCase()}`}>
            <strong>{status.state}</strong>
            <span>{status.durationMs ?? '—'} ms</span>
            <span>{status.rowCount ?? result?.rowCount ?? '—'} rows</span>
            {Object.entries(status.dataVersions).map(([name, version]) => (
              <code key={name}>{name}: {version.slice(0, 18)}…</code>
            ))}
          </div>
        ) : null}
        {error && <div className="error-banner">{error}</div>}
        <ResultTable result={result} />
      </main>
    </section>
  );
}

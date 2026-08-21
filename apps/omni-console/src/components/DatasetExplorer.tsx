import { useEffect, useMemo, useState } from 'react';

import {
  getJsonResult,
  listDatasets,
  listPartitions,
  submitQuery,
  waitForQuery,
  type DatasetManifest,
  type DatasetSummary,
  type QueryResult,
} from '../api';
import { ResultTable } from './ResultTable';

interface Props {
  onSelect: (manifest: DatasetManifest) => void;
}

function quoteIdentifier(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}

export function DatasetExplorer({ onSelect }: Props) {
  const [datasets, setDatasets] = useState<DatasetSummary[]>([]);
  const [dataset, setDataset] = useState('');
  const [partitions, setPartitions] = useState<DatasetManifest[]>([]);
  const [manifest, setManifest] = useState<DatasetManifest | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('');
  const [sortColumn, setSortColumn] = useState('');
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('ASC');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const pageSize = 100;

  useEffect(() => {
    listDatasets()
      .then((items) => {
        setDatasets(items);
        if (items[0]) setDataset(items[0].name);
      })
      .catch((reason: Error) => setError(reason.message));
  }, []);

  useEffect(() => {
    if (!dataset) return;
    setLoading(true);
    listPartitions(dataset)
      .then((items) => {
        setPartitions(items);
        setManifest(items[0] ?? null);
        if (items[0]) onSelect(items[0]);
      })
      .catch((reason: Error) => setError(reason.message))
      .finally(() => setLoading(false));
  }, [dataset, onSelect]);

  const filteredDatasets = useMemo(
    () => datasets.filter((item) => item.name.includes(search.toLowerCase())),
    [datasets, search],
  );

  async function preview(nextPage = page) {
    if (!manifest) return;
    setLoading(true);
    setError(null);
    const alias = manifest.dataset.replaceAll('-', '_').replaceAll('.', '_');
    const where = filter.trim() ? ` WHERE ${filter.trim()}` : '';
    const order = sortColumn
      ? ` ORDER BY ${quoteIdentifier(sortColumn)} ${sortDirection}`
      : '';
    const sql = `SELECT * FROM ${quoteIdentifier(alias)}${where}${order} LIMIT ${pageSize} OFFSET ${nextPage * pageSize}`;
    try {
      const queryId = await submitQuery(
        sql,
        [{
          dataset: manifest.dataset,
          partition: manifest.partition,
          dataVersion: manifest.dataVersion,
        }],
        pageSize,
      );
      const status = await waitForQuery(queryId);
      if (status.state !== 'SUCCEEDED') throw new Error(status.error ?? status.state);
      setResult(await getJsonResult(queryId));
      setPage(nextPage);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Preview failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="workspace-grid explorer-grid">
      <aside className="panel dataset-list">
        <div className="panel-heading"><h2>Datasets</h2><span>{datasets.length}</span></div>
        <input
          aria-label="Search datasets"
          placeholder="Search dataset"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="stack-list">
          {filteredDatasets.map((item) => (
            <button
              className={item.name === dataset ? 'list-item active' : 'list-item'}
              key={item.name}
              onClick={() => setDataset(item.name)}
            >
              <strong>{item.name}</strong>
              <small>{item.description ?? item.dataPrefix}</small>
            </button>
          ))}
        </div>
      </aside>

      <main className="panel detail-panel">
        {error && <div className="error-banner">{error}</div>}
        {loading && <div className="loading-line">Loading…</div>}
        {!manifest && !loading ? <div className="empty-state">No READY partition found.</div> : null}
        {manifest ? (
          <>
            <div className="panel-heading">
              <div><p className="eyebrow">READY dataset</p><h2>{manifest.dataset}</h2></div>
              <select
                aria-label="Partition"
                value={manifest.dataVersion}
                onChange={(event) => {
                  const selected = partitions.find((item) => item.dataVersion === event.target.value);
                  if (selected) { setManifest(selected); onSelect(selected); }
                }}
              >
                {partitions.map((item) => (
                  <option key={item.dataVersion} value={item.dataVersion}>
                    {Object.entries(item.partition).map(([key, value]) => `${key}=${value}`).join(' / ') || '_default'}
                  </option>
                ))}
              </select>
            </div>
            <div className="stat-grid">
              <div><span>Rows</span><strong>{manifest.rowCount.toLocaleString()}</strong></div>
              <div><span>Columns</span><strong>{manifest.columnCount}</strong></div>
              <div><span>Size</span><strong>{(manifest.totalBytes / 1024 / 1024).toFixed(2)} MB</strong></div>
              <div><span>Published</span><strong>{new Date(manifest.generatedAt).toLocaleString()}</strong></div>
            </div>
            <details className="schema-card" open>
              <summary>Schema</summary>
              <div className="schema-list">
                {manifest.columns.map((column) => (
                  <button key={column.name} onClick={() => setSortColumn(column.name)}>
                    <code>{column.name}</code><span>{column.type}{column.nullable ? '?' : ''}</span>
                  </button>
                ))}
              </div>
            </details>
            <div className="query-toolbar">
              <input
                aria-label="SQL filter"
                placeholder="Filter, e.g. close > 100"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
              <select value={sortColumn} onChange={(event) => setSortColumn(event.target.value)}>
                <option value="">No sort</option>
                {manifest.columns.map((column) => <option key={column.name}>{column.name}</option>)}
              </select>
              <button className="secondary" onClick={() => setSortDirection(sortDirection === 'ASC' ? 'DESC' : 'ASC')}>
                {sortDirection}
              </button>
              <button className="primary" onClick={() => preview(0)} disabled={loading}>Preview</button>
            </div>
            <ResultTable result={result} />
            <div className="pager">
              <button disabled={page === 0 || loading} onClick={() => preview(page - 1)}>Previous</button>
              <span>Page {page + 1}</span>
              <button disabled={!result || result.rowCount < pageSize || loading} onClick={() => preview(page + 1)}>Next</button>
            </div>
          </>
        ) : null}
      </main>
    </section>
  );
}

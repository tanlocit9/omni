import { useEffect, useMemo, useRef, useState } from 'react';

import {
  getJsonResult,
  listDatasets,
  getDatasetPartitionPage,
  submitQuery,
  waitForQuery,
  type DatasetManifest,
  type DatasetSummary,
  type QueryResult,
} from '../api';
import { ResultTable } from './ResultTable';

interface Props {
  onSelect: (manifest: DatasetManifest | null) => void;
  onOpenWork?: () => void;
}

function quoteIdentifier(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}

function partitionKey(value: DatasetManifest) {
  return JSON.stringify(
    Object.entries(value.partition).sort(([left], [right]) =>
      left.localeCompare(right)
    )
  );
}

export function DatasetExplorer({ onSelect, onOpenWork }: Props) {
  const [datasets, setDatasets] = useState<DatasetSummary[]>([]);
  const [dataset, setDataset] = useState('');
  const [partitions, setPartitions] = useState<DatasetManifest[]>([]);
  const [partitionFilter, setPartitionFilter] = useState<
    Record<string, string>
  >({});
  const [manifest, setManifest] = useState<DatasetManifest | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('');
  const [sortColumn, setSortColumn] = useState('');
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('ASC');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [partitionTotal, setPartitionTotal] = useState(0);
  const [catalogRevision, setCatalogRevision] = useState(0);
  const datasetAbort = useRef<AbortController | null>(null);
  const selectionEpoch = useRef(0);
  const pageSize = 100;

  useEffect(() => {
    let current = true;
    listDatasets()
      .then((items) => {
        if (!current) return;
        setDatasets(items);
        if (items[0]) setDataset(items[0].name);
      })
      .catch((reason: Error) => {
        if (current) setError(reason.message);
      });
    return () => {
      current = false;
    };
  }, []);

  useEffect(() => {
    if (!dataset) return;
    const abort = new AbortController();
    datasetAbort.current = abort;
    selectionEpoch.current += 1;
    setManifest(null);
    onSelect(null);
    setPartitions([]);
    setPartitionTotal(0);
    setResult(null);
    setPreviewing(false);
    setPage(0);
    setFilter('');
    setSortColumn('');
    setError(null);
    setLoading(true);
    getDatasetPartitionPage(dataset, 0, abort.signal)
      .then((response) => {
        if (abort.signal.aborted) return;
        setPartitions(response.items);
        setPartitionTotal(response.total);
        setPartitionFilter({});
        setManifest(response.items[0] ?? null);
        if (response.items[0]) onSelect(response.items[0]);
      })
      .catch((reason: Error) => {
        if (!abort.signal.aborted) setError(reason.message);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => {
      abort.abort();
      selectionEpoch.current += 1;
    };
  }, [dataset, onSelect, catalogRevision]);

  async function loadMorePartitions() {
    const abort = datasetAbort.current;
    if (!abort || loading || partitions.length >= partitionTotal) return;
    setLoading(true);
    setError(null);
    try {
      const response = await getDatasetPartitionPage(
        dataset,
        partitions.length,
        abort.signal
      );
      if (abort.signal.aborted) return;
      const existingKeys = new Set(partitions.map(partitionKey));
      if (response.items.some((item) => existingKeys.has(partitionKey(item)))) {
        throw new Error(
          'Catalog pagination changed. Refresh the catalog before continuing.'
        );
      }
      setPartitions((items) => [...items, ...response.items]);
      setPartitionTotal(response.total);
    } catch (reason) {
      if (!abort.signal.aborted)
        setError(
          reason instanceof Error
            ? reason.message
            : 'Partition page unavailable'
        );
    } finally {
      if (!abort.signal.aborted) setLoading(false);
    }
  }

  const filteredDatasets = useMemo(
    () => datasets.filter((item) => item.name.includes(search.toLowerCase())),
    [datasets, search]
  );

  const selectedDataset = datasets.find((item) => item.name === dataset);

  function selectPartition(next: Record<string, string>) {
    selectionEpoch.current += 1;
    setResult(null);
    setPreviewing(false);
    setPage(0);
    setSortColumn('');
    setFilter('');
    setPartitionFilter(next);
    const selected = partitions.find((item) =>
      Object.entries(next).every(
        ([key, value]) => !value || String(item.partition[key]) === value
      )
    );
    setManifest(selected ?? null);
    onSelect(selected ?? null);
  }

  async function preview(nextPage = page) {
    if (!manifest) return;
    const epoch = selectionEpoch.current;
    setPreviewing(true);
    setError(null);
    const alias = manifest.dataset.replaceAll('-', '_').replaceAll('.', '_');
    const where = filter.trim() ? ` WHERE ${filter.trim()}` : '';
    const order = sortColumn
      ? ` ORDER BY ${quoteIdentifier(sortColumn)} ${sortDirection}`
      : '';
    const sql = `SELECT * FROM ${quoteIdentifier(
      alias
    )}${where}${order} LIMIT ${pageSize} OFFSET ${nextPage * pageSize}`;
    try {
      const queryId = await submitQuery(
        sql,
        [
          {
            dataset: manifest.dataset,
            partition: manifest.partition,
            dataVersion: manifest.dataVersion,
          },
        ],
        pageSize
      );
      const status = await waitForQuery(queryId);
      if (selectionEpoch.current !== epoch) return;
      if (status.state !== 'SUCCEEDED')
        throw new Error(status.error ?? status.state);
      const nextResult = await getJsonResult(queryId);
      if (selectionEpoch.current !== epoch) return;
      setResult(nextResult);
      setPage(nextPage);
    } catch (reason) {
      if (selectionEpoch.current === epoch)
        setError(reason instanceof Error ? reason.message : 'Preview failed');
    } finally {
      if (selectionEpoch.current === epoch) setPreviewing(false);
    }
  }

  return (
    <section className="workspace-grid explorer-grid">
      <aside className="panel dataset-list">
        <div className="panel-heading">
          <h2>Datasets</h2>
          <span>{datasets.length}</span>
        </div>
        <input
          aria-label="Search datasets"
          placeholder="Search dataset"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="stack-list">
          {filteredDatasets.map((item) => (
            <button
              className={
                item.name === dataset ? 'list-item active' : 'list-item'
              }
              key={item.name}
              onClick={() => setDataset(item.name)}
            >
              <strong>{item.label}</strong>
              <small>{item.partitionCount.toLocaleString()} partitions</small>
            </button>
          ))}
        </div>
      </aside>

      <main className="panel detail-panel">
        {dataset && (
          <div className="actions">
            <button
              className="secondary"
              type="button"
              disabled={loading || previewing}
              onClick={() => setCatalogRevision((value) => value + 1)}
            >
              Refresh catalog
            </button>
            <span>
              Loaded {partitions.length} of {partitionTotal} catalog partitions
            </span>
            {partitions.length < partitionTotal && (
              <button
                className="secondary"
                type="button"
                disabled={loading}
                onClick={() => void loadMorePartitions()}
              >
                Load more partitions
              </button>
            )}
          </div>
        )}
        {error && <div className="error-banner">{error}</div>}
        {(loading || previewing) && (
          <div className="loading-line">Loading…</div>
        )}
        {!manifest && !loading && !error ? (
          <div className="empty-state">No metadata partition found.</div>
        ) : null}
        {manifest ? (
          <>
            <div className="panel-heading">
              <div>
                <p className="eyebrow">Query catalog metadata</p>
                <h2>{manifest.dataset}</h2>
              </div>
              <div className="partition-controls">
                {selectedDataset?.partitionKeys.map((definition, index) => {
                  const prior = selectedDataset.partitionKeys.slice(0, index);
                  const options = Array.from(
                    new Set(
                      partitions
                        .filter((item) =>
                          prior.every(
                            (key) =>
                              !partitionFilter[key.name] ||
                              String(item.partition[key.name]) ===
                                partitionFilter[key.name]
                          )
                        )
                        .map((item) => String(item.partition[definition.name]))
                    )
                  ).sort();
                  return (
                    <label key={definition.name}>
                      <span>{definition.label ?? definition.name}</span>
                      <select
                        aria-label={definition.label ?? definition.name}
                        value={partitionFilter[definition.name] ?? ''}
                        onChange={(event) =>
                          selectPartition({
                            ...Object.fromEntries(
                              prior
                                .filter((key) => partitionFilter[key.name])
                                .map((key) => [
                                  key.name,
                                  partitionFilter[key.name],
                                ])
                            ),
                            [definition.name]: event.target.value,
                          })
                        }
                      >
                        <option value="">All</option>
                        {options.map((value) => (
                          <option key={value} value={value}>
                            {value}
                          </option>
                        ))}
                      </select>
                    </label>
                  );
                })}
              </div>
            </div>
            <p className="muted">
              Catalog partition status: {manifest.status}. Partition metadata
              does not prove exact-date work coverage or the readiness of a
              particular attempt. Catalog pages are separate observations.
            </p>
            {onOpenWork && (
              <button className="secondary" type="button" onClick={onOpenWork}>
                Inspect work readiness
              </button>
            )}
            <details className="schema-card">
              <summary>Partition version and lineage</summary>
              <p>
                Data version: <code>{manifest.dataVersion}</code>
              </p>
              <p>
                Source execution:{' '}
                <code>{manifest.sourceExecutionId ?? 'Not reported'}</code>
              </p>
              <p>
                Schema hash: <code>{manifest.schemaHash}</code>
              </p>
              {manifest.inputs.length ? (
                <ul>
                  {manifest.inputs.map((input, index) => (
                    <li key={`${input.dataset}:${index}`}>
                      {input.dataset} ·{' '}
                      <code>{JSON.stringify(input.partition)}</code> ·{' '}
                      <code>{input.dataVersion}</code>
                    </li>
                  ))}
                </ul>
              ) : (
                <p>No input versions are listed.</p>
              )}
            </details>
            <div className="stat-grid">
              <div>
                <span>Rows</span>
                <strong>{manifest.rowCount.toLocaleString()}</strong>
              </div>
              <div>
                <span>Columns</span>
                <strong>{manifest.columnCount}</strong>
              </div>
              <div>
                <span>Size</span>
                <strong>
                  {(manifest.totalBytes / 1024 / 1024).toFixed(2)} MB
                </strong>
              </div>
              <div>
                <span>Published</span>
                <strong>
                  {new Date(manifest.generatedAt).toLocaleString()}
                </strong>
              </div>
            </div>
            <details className="schema-card" open>
              <summary>Schema</summary>
              <div className="schema-list">
                {manifest.columns.map((column) => (
                  <button
                    key={column.name}
                    onClick={() => setSortColumn(column.name)}
                  >
                    <code>{column.name}</code>
                    <span>
                      {column.type}
                      {column.nullable ? '?' : ''}
                    </span>
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
              <select
                value={sortColumn}
                onChange={(event) => setSortColumn(event.target.value)}
              >
                <option value="">No sort</option>
                {manifest.columns.map((column) => (
                  <option key={column.name}>{column.name}</option>
                ))}
              </select>
              <button
                className="secondary"
                onClick={() =>
                  setSortDirection(sortDirection === 'ASC' ? 'DESC' : 'ASC')
                }
              >
                {sortDirection}
              </button>
              <button
                className="primary"
                onClick={() => preview(0)}
                disabled={loading || previewing}
              >
                Preview
              </button>
            </div>
            <ResultTable result={result} />
            <div className="pager">
              <button
                disabled={page === 0 || loading || previewing}
                onClick={() => preview(page - 1)}
              >
                Previous
              </button>
              <span>Page {page + 1}</span>
              <button
                disabled={
                  !result || result.rowCount < pageSize || loading || previewing
                }
                onClick={() => preview(page + 1)}
              >
                Next
              </button>
            </div>
          </>
        ) : null}
      </main>
    </section>
  );
}

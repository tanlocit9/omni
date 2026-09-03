import { useEffect, useMemo, useState } from 'react';

import {
  getJsonResult,
  getTriggerStatus,
  listDatasets,
  listJobDefinitions,
  listPartitions,
  submitQuery,
  triggerJob,
  waitForQuery,
  type DatasetManifest,
  type DatasetSummary,
  type QueryResult,
} from '../api';
import { ResultTable } from './ResultTable';

interface Props {
  onSelect: (manifest: DatasetManifest) => void;
}

const DEFAULT_SYNC_REASON = 'Manual metadata recovery from Omni Console';

function quoteIdentifier(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}

export function DatasetExplorer({ onSelect }: Props) {
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
  const [syncing, setSyncing] = useState(false);
  const [syncReason, setSyncReason] = useState('');
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
        setPartitionFilter({});
        setManifest(items[0] ?? null);
        if (items[0]) onSelect(items[0]);
      })
      .catch((reason: Error) => setError(reason.message))
      .finally(() => setLoading(false));
  }, [dataset, onSelect]);

  const filteredDatasets = useMemo(
    () => datasets.filter((item) => item.name.includes(search.toLowerCase())),
    [datasets, search]
  );

  const selectedDataset = datasets.find((item) => item.name === dataset);

  function selectPartition(next: Record<string, string>) {
    setPartitionFilter(next);
    const selected = partitions.find((item) =>
      Object.entries(next).every(
        ([key, value]) => String(item.partition[key]) === value
      )
    );
    setManifest(selected ?? null);
    if (selected) onSelect(selected);
  }

  async function synchronize(
    mode: 'full' | 'dataset' | 'exact',
    targetDataset = dataset
  ) {
    if (syncing) return;
    const parameters =
      mode === 'full'
        ? {}
        : mode === 'dataset'
        ? { dataset: targetDataset }
        : { dataset: targetDataset, partition: manifest?.partition };
    const logicalTarget =
      mode === 'full'
        ? 'all registered datasets'
        : mode === 'dataset'
        ? `dataset ${targetDataset}`
        : `${targetDataset} ${JSON.stringify(manifest?.partition)}`;
    if (!window.confirm(`Synchronize metadata for ${logicalTarget}?`)) return;

    setSyncing(true);
    setError(null);
    try {
      const definitions = await listJobDefinitions({
        jobType: 'SYNC_METADATA',
        active: true,
      });
      const definition = definitions.items.find((item) => item.triggerable);
      if (!definition)
        throw new Error('No triggerable SYNC_METADATA job is available');
      const trigger = await triggerJob(
        definition.id,
        syncReason.trim() || DEFAULT_SYNC_REASON,
        crypto.randomUUID(),
        parameters
      );
      if (trigger.state !== 'ACCEPTED') {
        throw new Error(trigger.blockReason ?? trigger.error ?? trigger.state);
      }
      for (let attempt = 0; attempt < 60; attempt += 1) {
        const status = await getTriggerStatus(trigger.requestId);
        const state = status.execution?.status;
        if (state === 'SUCCESS') {
          const [nextDatasets, nextPartitions] = await Promise.all([
            listDatasets(),
            dataset ? listPartitions(dataset) : Promise.resolve([]),
          ]);
          setDatasets(nextDatasets);
          setPartitions(nextPartitions);
          const refreshed = nextPartitions.find((item) =>
            Object.entries(partitionFilter).every(
              ([key, value]) => String(item.partition[key]) === value
            )
          );
          setManifest(refreshed ?? nextPartitions[0] ?? null);
          setSyncReason('');
          return;
        }
        if (state && ['FAILED', 'ERROR', 'CANCELLED'].includes(state)) {
          throw new Error(
            status.execution?.error ?? `Synchronization ${state}`
          );
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
      throw new Error('Synchronization status polling timed out');
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : 'Synchronization failed'
      );
    } finally {
      setSyncing(false);
    }
  }

  async function preview(nextPage = page) {
    if (!manifest) return;
    setLoading(true);
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
      if (status.state !== 'SUCCEEDED')
        throw new Error(status.error ?? status.state);
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
        {error && <div className="error-banner">{error}</div>}
        {(loading || syncing) && (
          <div className="loading-line">
            {syncing ? 'Synchronizing metadata…' : 'Loading…'}
          </div>
        )}
        <div className="query-toolbar" aria-label="Metadata synchronization">
          <input
            aria-label="Synchronization reason"
            placeholder="Operator reason (optional)"
            maxLength={500}
            value={syncReason}
            onChange={(event) => setSyncReason(event.target.value)}
          />
          <button
            disabled={syncing}
            onClick={() => synchronize('dataset', 'eod')}
          >
            Sync EOD metadata
          </button>
          <button
            disabled={syncing}
            onClick={() => synchronize('dataset', 'indicators')}
          >
            Sync indicator metadata
          </button>
          <button disabled={syncing} onClick={() => synchronize('full')}>
            Sync all metadata
          </button>
        </div>
        {!manifest && !loading ? (
          <div className="empty-state">No metadata partition found.</div>
        ) : null}
        {manifest ? (
          <>
            <div className="panel-heading">
              <div>
                <p className="eyebrow">Canonical metadata</p>
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
                disabled={loading}
              >
                Preview
              </button>
            </div>
            <ResultTable result={result} />
            <div className="pager">
              <button
                disabled={page === 0 || loading}
                onClick={() => preview(page - 1)}
              >
                Previous
              </button>
              <span>Page {page + 1}</span>
              <button
                disabled={!result || result.rowCount < pageSize || loading}
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

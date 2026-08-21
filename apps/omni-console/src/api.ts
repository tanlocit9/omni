import { tableFromIPC } from 'apache-arrow';

export interface DatasetSummary {
  name: string;
  description: string | null;
  dataPrefix: string;
}

export interface ColumnMetadata {
  name: string;
  type: string;
  nullable: boolean;
}

export interface DatasetManifest {
  dataset: string;
  partition: Record<string, string>;
  status: 'READY' | 'PROCESSING' | 'FAILED';
  dataVersion: string;
  rowCount: number;
  columnCount: number;
  totalBytes: number;
  columns: ColumnMetadata[];
  generatedAt: string;
  minTimestamp: string | null;
  maxTimestamp: string | null;
}

export interface DatasetRef {
  dataset: string;
  partition: Record<string, string>;
  alias?: string;
  dataVersion?: string;
}

export interface QueryStatus {
  queryId: string;
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT';
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  rowCount: number | null;
  truncated: boolean;
  dataVersions: Record<string, string>;
  error: string | null;
}

export interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
  truncated: boolean;
  dataVersions: Record<string, string>;
}

const API_BASE = import.meta.env.VITE_QUERY_SERVICE_URL ?? 'http://localhost:8002';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { detail?: string } | null;
    throw new Error(payload?.detail ?? `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export function listDatasets(): Promise<DatasetSummary[]> {
  return request('/v1/datasets');
}

export function listPartitions(dataset: string): Promise<DatasetManifest[]> {
  return request(`/v1/datasets/${encodeURIComponent(dataset)}/partitions`);
}

export async function submitQuery(
  sql: string,
  datasets: DatasetRef[],
  rowLimit = 200,
): Promise<string> {
  const accepted = await request<{ queryId: string }>('/v1/queries', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sql, datasets, rowLimit }),
  });
  return accepted.queryId;
}

export function getQuery(queryId: string): Promise<QueryStatus> {
  return request(`/v1/queries/${encodeURIComponent(queryId)}`);
}

export function cancelQuery(queryId: string): Promise<QueryStatus> {
  return request(`/v1/queries/${encodeURIComponent(queryId)}`, { method: 'DELETE' });
}

export async function waitForQuery(
  queryId: string,
  signal?: AbortSignal,
): Promise<QueryStatus> {
  while (true) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const status = await getQuery(queryId);
    if (!['QUEUED', 'RUNNING'].includes(status.state)) return status;
    await new Promise((resolve) => window.setTimeout(resolve, 250));
  }
}

export async function getJsonResult(queryId: string): Promise<QueryResult> {
  return request(`/v1/queries/${encodeURIComponent(queryId)}/result?format=json`);
}

export async function getArrowResult(
  queryId: string,
  status: QueryStatus,
): Promise<QueryResult> {
  const response = await fetch(
    `${API_BASE}/v1/queries/${encodeURIComponent(queryId)}/result?format=arrow`,
  );
  if (!response.ok) throw new Error(`Arrow result failed (${response.status})`);
  const table = tableFromIPC(await response.arrayBuffer());
  const columns = table.schema.fields.map((field) => field.name);
  const rows: Record<string, unknown>[] = [];
  for (let rowIndex = 0; rowIndex < table.numRows; rowIndex += 1) {
    const row: Record<string, unknown> = {};
    columns.forEach((column, columnIndex) => {
      row[column] = table.getChildAt(columnIndex)?.get(rowIndex) ?? null;
    });
    rows.push(row);
  }
  return {
    columns,
    rows,
    rowCount: table.numRows,
    truncated: status.truncated,
    dataVersions: status.dataVersions,
  };
}

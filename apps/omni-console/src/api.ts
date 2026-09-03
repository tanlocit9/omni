import { tableFromIPC } from 'apache-arrow';

export type PartitionValueType = 'STRING' | 'DATE' | 'INTEGER' | 'BOOLEAN';

export interface PartitionKeyDefinition {
  name: string;
  type: PartitionValueType;
  required: boolean;
  order: number;
  queryable: boolean;
  label: string | null;
}

export interface DatasetSummary {
  name: string;
  label: string;
  partitionKeys: PartitionKeyDefinition[];
  partitionCount: number;
}

export interface ColumnMetadata {
  name: string;
  type: string;
  nullable: boolean;
}

export interface DatasetManifest {
  dataset: string;
  partition: Record<string, string | number | boolean>;
  status: 'READY';
  dataVersion: string;
  rowCount: number;
  columnCount: number;
  totalBytes: number;
  columns: ColumnMetadata[];
  generatedAt: string;
  minTimestamp: string | null;
  maxTimestamp: string | null;
  schemaVersion: number;
  schemaHash: string;
  objectCount: number;
  inputs: Array<{
    dataset: string;
    partition: Record<string, string | number | boolean>;
    dataVersion: string;
  }>;
  sourceExecutionId: string | null;
}

export interface DatasetRef {
  dataset: string;
  partition: Record<string, string | number | boolean>;
  alias?: string;
  dataVersion?: string;
}

export interface QueryStatus {
  queryId: string;
  state:
    | 'QUEUED'
    | 'RUNNING'
    | 'SUCCEEDED'
    | 'FAILED'
    | 'CANCELLED'
    | 'TIMED_OUT';
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

const API_BASE =
  import.meta.env.VITE_QUERY_SERVICE_URL ?? 'http://localhost:8002';
const PLATFORM_API_BASE =
  import.meta.env.VITE_PLATFORM_API_URL ?? '/api/platform';
const SYSTEM_OPERATOR_UUID = import.meta.env.SYSTEM_OPERATOR_UUID;

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string
  ) {
    super(message);
  }
}

export async function queryServiceRequest<T>(
  path: string,
  init?: RequestInit
): Promise<T> {
  const headers = new Headers(init?.headers);
  if (SYSTEM_OPERATOR_UUID) headers.set('X-Omni-User', SYSTEM_OPERATOR_UUID);
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as {
      detail?: string;
      code?: string;
    } | null;
    throw new ApiError(
      payload?.detail ?? `Request failed (${response.status})`,
      response.status,
      payload?.code
    );
  }
  return response.json() as Promise<T>;
}

async function platformRequest<T>(
  path: string,
  init?: RequestInit
): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set('X-Omni-User', SYSTEM_OPERATOR_UUID);
  const response = await fetch(`${PLATFORM_API_BASE}${path}`, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as {
      detail?: string;
      code?: string;
    } | null;
    throw new ApiError(
      payload?.detail ?? `Request failed (${response.status})`,
      response.status,
      payload?.code
    );
  }
  return response.json() as Promise<T>;
}

export type JobExecutionState =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'ERROR'
  | 'CANCELLED';

export interface JobExecutionSummary {
  id: string;
  status: JobExecutionState;
  triggeredAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  recordsSynced: number | null;
  recordsSkipped: number | null;
  error: string | null;
}

export interface JobDefinitionSummary {
  id: string;
  title: string | null;
  source: string;
  jobType: string;
  workType: string;
  workKey: string;
  active: boolean;
  cronExpression: string | null;
  nextRun: string | null;
  triggerable: boolean;
  triggerBlockReason: string | null;
  lastExecution: JobExecutionSummary | null;
}

export interface JobDefinitionDetail extends JobDefinitionSummary {
  dependencies: {
    jobs: string[];
    datasets: string[];
    produces: string[];
  };
  acceptedTriggerParameters: string[];
  recentExecutions: JobExecutionSummary[];
}

export interface JobDefinitionPage {
  items: JobDefinitionSummary[];
  page: number;
  size: number;
  total: number;
}

export interface ManualTriggerResponse {
  requestId: string;
  definitionId: string;
  executionId: string | null;
  state: 'REQUESTED' | 'ACCEPTED' | 'BLOCKED' | 'CONFLICT' | 'FAILED';
  duplicate: boolean;
  blockReason: string | null;
  error: string | null;
  requestedAt: string;
  resolvedAt: string | null;
}

export interface TriggerStatusResponse {
  trigger: ManualTriggerResponse;
  execution: JobExecutionSummary | null;
}

export function listJobDefinitions(filters: {
  q?: string;
  jobType?: string;
  active?: boolean;
}): Promise<JobDefinitionPage> {
  const query = new URLSearchParams({ page: '0', size: '100' });
  if (filters.q) query.set('q', filters.q);
  if (filters.jobType) query.set('jobType', filters.jobType);
  if (filters.active !== undefined) query.set('active', String(filters.active));
  return platformRequest(`/api/v1/jobs/definitions?${query.toString()}`);
}

export function getJobDefinition(id: string): Promise<JobDefinitionDetail> {
  return platformRequest(`/api/v1/jobs/definitions/${encodeURIComponent(id)}`);
}

export function triggerJob(
  id: string,
  reason: string,
  idempotencyKey: string,
  parameters: Record<string, unknown> = {}
): Promise<ManualTriggerResponse> {
  return fetch(
    `${PLATFORM_API_BASE}/api/v1/jobs/definitions/${encodeURIComponent(
      id
    )}/triggers`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Omni-User': SYSTEM_OPERATOR_UUID,
      },
      body: JSON.stringify({ reason, idempotencyKey, parameters }),
    }
  ).then(async (response) => {
    if (response.ok || response.status === 409) {
      return response.json() as Promise<ManualTriggerResponse>;
    }
    const payload = (await response.json().catch(() => null)) as {
      detail?: string;
      code?: string;
    } | null;
    throw new ApiError(
      payload?.detail ?? `Request failed (${response.status})`,
      response.status,
      payload?.code
    );
  });
}

export function getTriggerStatus(id: string): Promise<TriggerStatusResponse> {
  return platformRequest(`/api/v1/jobs/triggers/${encodeURIComponent(id)}`);
}

export function listDatasets(): Promise<DatasetSummary[]> {
  return queryServiceRequest('/v1/datasets');
}

export interface DatasetPartitionPage {
  items: DatasetManifest[];
  offset: number;
  limit: number;
  total: number;
}

export function listPartitions(dataset: string): Promise<DatasetManifest[]> {
  return queryServiceRequest<DatasetPartitionPage>(
    `/v1/datasets/${encodeURIComponent(dataset)}/partitions?offset=0&limit=500`
  ).then((page) => page.items);
}

export function listPartitionOptions(
  dataset: string,
  key: string,
  filters: Record<string, string> = {}
): Promise<Array<string | number | boolean>> {
  const query = new URLSearchParams({ limit: '200' });
  Object.entries(filters).forEach(([name, value]) =>
    query.set(`filter.${name}`, value)
  );
  return queryServiceRequest(
    `/v1/datasets/${encodeURIComponent(
      dataset
    )}/partition-options/${encodeURIComponent(key)}?${query.toString()}`
  );
}

export async function submitQuery(
  sql: string,
  datasets: DatasetRef[],
  rowLimit = 200
): Promise<string> {
  const accepted = await queryServiceRequest<{ queryId: string }>(
    '/v1/queries',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql, datasets, rowLimit }),
    }
  );
  return accepted.queryId;
}

export function getQuery(queryId: string): Promise<QueryStatus> {
  return queryServiceRequest(`/v1/queries/${encodeURIComponent(queryId)}`);
}

export function cancelQuery(queryId: string): Promise<QueryStatus> {
  return queryServiceRequest(`/v1/queries/${encodeURIComponent(queryId)}`, {
    method: 'DELETE',
  });
}

export async function waitForQuery(
  queryId: string,
  signal?: AbortSignal
): Promise<QueryStatus> {
  while (true) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const status = await getQuery(queryId);
    if (!['QUEUED', 'RUNNING'].includes(status.state)) return status;
    await new Promise((resolve) => window.setTimeout(resolve, 250));
  }
}

export async function getJsonResult(queryId: string): Promise<QueryResult> {
  return queryServiceRequest(
    `/v1/queries/${encodeURIComponent(queryId)}/result?format=json`
  );
}

export async function getArrowResult(
  queryId: string,
  status: QueryStatus
): Promise<QueryResult> {
  const headers = new Headers();
  if (SYSTEM_OPERATOR_UUID) headers.set('X-Omni-User', SYSTEM_OPERATOR_UUID);
  const response = await fetch(
    `${API_BASE}/v1/queries/${encodeURIComponent(queryId)}/result?format=arrow`,
    { headers }
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

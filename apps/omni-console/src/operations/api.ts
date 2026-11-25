import { platformRequest } from '../api';
export { getAtomicRequestDetail, getAttemptReadiness } from './detailApi';
import type {
  AtomicRequestPage,
  AtomicRequestQuery,
  AtomicSnapshot,
  BackfillAccepted,
  BackfillGoal,
  BackfillRequest,
  BackfillSector,
  PublishedPage,
  WorkInspection,
} from './types';

export async function getBackfillSectors(level: number, signal?: AbortSignal) {
  const sectors = await platformRequest<BackfillSector[]>(
    `/api/v1/jobs/backfills/sectors?sectorLevel=${level}`,
    { signal }
  );
  if (
    !Array.isArray(sectors) ||
    sectors.some(
      (item) =>
        !item ||
        typeof item.code !== 'string' ||
        !/^[A-Z0-9_-]{1,64}$/.test(item.code) ||
        typeof item.name !== 'string' ||
        !item.name.trim()
    ) ||
    new Set(sectors.map((item) => item.code)).size !== sectors.length
  ) {
    throw new Error('The sector catalog is unavailable.');
  }
  return [...sectors].sort((a, b) => a.code.localeCompare(b.code));
}

export async function getBackfillGoals(signal?: AbortSignal) {
  const goals = await platformRequest<BackfillGoal[]>(
    '/api/v1/jobs/backfills/goals',
    { signal }
  );
  if (
    !Array.isArray(goals) ||
    goals.some(
      (goal) =>
        !goal ||
        typeof goal.goal !== 'string' ||
        !Array.isArray(goal.targets) ||
        goal.targets.some(
          (target) => !target || typeof target.targetDefinitionId !== 'string'
        )
    )
  ) {
    throw new Error('The backfill goal catalog is unavailable.');
  }
  return goals;
}

export async function createBackfill(
  input: BackfillRequest,
  idempotencyKey: string,
  signal?: AbortSignal
) {
  const result = await platformRequest<BackfillAccepted>(
    '/api/v1/jobs/backfills',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(input),
      signal,
    }
  );
  if (
    !result ||
    result.status !== 'ACCEPTED' ||
    typeof result.requestId !== 'string' ||
    typeof result.executionId !== 'string'
  ) {
    throw new Error(
      'The acceptance response is unavailable. Check request history before starting another request.'
    );
  }
  return result;
}

export async function listAtomicRequests(
  query: AtomicRequestQuery,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({
    status: query.status,
    limit: String(query.limit ?? 20),
  });
  for (const key of ['observedAt', 'beforeCreatedAt', 'beforeId'] as const) {
    const value = query[key];
    if (value) params.set(key, value);
  }
  const result = await platformRequest<AtomicRequestPage>(
    `/api/v1/jobs/atomic-work/requests?${params}`,
    { signal }
  );
  if (
    !result ||
    !Array.isArray(result.items) ||
    typeof result.observedAt !== 'string' ||
    typeof result.hasMore !== 'boolean' ||
    (result.hasMore &&
      (!result.nextCursor ||
        typeof result.nextCursor.beforeCreatedAt !== 'string' ||
        typeof result.nextCursor.beforeId !== 'string'))
  ) {
    throw new Error('The request list contract is unavailable.');
  }
  return result;
}

export interface SnapshotWindow {
  hours: number;
  afterId?: string;
  since?: string;
  until?: string;
  observedAt?: string;
}

export async function getAtomicSnapshot(
  window: SnapshotWindow,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({
    hours: String(window.hours),
    limit: '25',
  });
  for (const key of ['afterId', 'since', 'until', 'observedAt'] as const) {
    const value = window[key];
    if (value) params.set(key, value);
  }
  const result = await platformRequest<AtomicSnapshot>(
    `/api/v1/jobs/atomic-work/snapshot?${params}`,
    { signal }
  );
  if (
    result.version !== 2 ||
    !result.validWindow ||
    !Array.isArray(result.attempts) ||
    !Array.isArray(result.requests) ||
    !Array.isArray(result.definitions)
  ) {
    throw new Error('The work snapshot contract is unavailable.');
  }
  return result;
}

export async function getPublishedUnfinished(
  page: { afterId?: string; observedAt?: string },
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ limit: '50' });
  if (page.afterId) params.set('afterId', page.afterId);
  if (page.observedAt) params.set('observedAt', page.observedAt);
  const result = await platformRequest<PublishedPage>(
    `/api/v1/jobs/atomic-work/published-unfinished?${params}`,
    { signal }
  );
  if (result.version !== 2 || !Array.isArray(result.rows)) {
    throw new Error('The publication snapshot contract is unavailable.');
  }
  return result;
}

export async function inspectAtomicWork(
  atomicWorkId: string,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ atomicWorkId });
  const result = await platformRequest<WorkInspection>(
    `/api/v1/jobs/atomic-work/work?${params}`,
    { signal }
  );
  if (
    !result ||
    result.atomicWorkId !== atomicWorkId ||
    !Array.isArray(result.attempts) ||
    !Array.isArray(result.owners) ||
    !Array.isArray(result.upstreams)
  ) {
    throw new Error('The work provenance contract is unavailable.');
  }
  return result;
}

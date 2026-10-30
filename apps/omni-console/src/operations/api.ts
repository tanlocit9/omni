import { platformRequest } from '../api';
import type { AtomicSnapshot, PublishedPage, WorkInspection } from './types';

export interface SnapshotWindow {
  hours: number;
  afterId?: string;
  since?: string;
  until?: string;
  observedAt?: string;
}

export async function getAtomicSnapshot(window: SnapshotWindow, signal?: AbortSignal) {
  const params = new URLSearchParams({ hours: String(window.hours), limit: '25' });
  for (const key of ['afterId', 'since', 'until', 'observedAt'] as const) {
    const value = window[key];
    if (value) params.set(key, value);
  }
  const result = await platformRequest<AtomicSnapshot>(
    `/api/v1/jobs/atomic-work/snapshot?${params}`, { signal }
  );
  if (result.version !== 2 || !result.validWindow || !Array.isArray(result.attempts)
      || !Array.isArray(result.requests) || !Array.isArray(result.definitions)) {
    throw new Error('The work snapshot contract is unavailable.');
  }
  return result;
}

export async function getPublishedUnfinished(
  page: { afterId?: string; observedAt?: string }, signal?: AbortSignal
) {
  const params = new URLSearchParams({ limit: '50' });
  if (page.afterId) params.set('afterId', page.afterId);
  if (page.observedAt) params.set('observedAt', page.observedAt);
  const result = await platformRequest<PublishedPage>(
    `/api/v1/jobs/atomic-work/published-unfinished?${params}`, { signal }
  );
  if (result.version !== 2 || !Array.isArray(result.rows)) {
    throw new Error('The publication snapshot contract is unavailable.');
  }
  return result;
}

export function inspectAtomicWork(atomicWorkId: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ atomicWorkId });
  return platformRequest<WorkInspection>(`/api/v1/jobs/atomic-work/work?${params}`, { signal });
}

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { platformRequest } from '../api';
import {
  createBackfill,
  getBackfillGoals,
  inspectAtomicWork,
  listAtomicRequests,
} from './api';
import type { BackfillRequest } from './types';

vi.mock('../api', () => ({ platformRequest: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('I10 operations API', () => {
  it('rejects a provenance response for a different selected work', async () => {
    vi.mocked(platformRequest).mockResolvedValue({
      atomicWorkId: 'wrong-work',
      attempts: [],
      owners: [],
      upstreams: [],
    });
    await expect(inspectAtomicWork('selected-work')).rejects.toThrow(
      'provenance contract is unavailable'
    );
  });
  it('distinguishes an empty goal catalog from an invalid contract', async () => {
    vi.mocked(platformRequest).mockResolvedValueOnce([]);
    await expect(getBackfillGoals()).resolves.toEqual([]);
    vi.mocked(platformRequest).mockResolvedValueOnce({ items: [] });
    await expect(getBackfillGoals()).rejects.toThrow('catalog is unavailable');
  });

  it('sends the selected target, policy and unchanged idempotency key as JSON', async () => {
    const input: BackfillRequest = {
      goal: 'SYNC_SIGNALS',
      targetDefinitionId: 'target-1',
      sectorCodes: ['BANK'],
      sectorLevel: 1,
      dateFrom: '2026-09-01',
      dateTo: '2026-09-02',
      rerun: true,
      force: true,
    };
    const result = {
      status: 'ACCEPTED',
      requestId: 'request-1',
      executionId: 'execution-1',
    };
    vi.mocked(platformRequest).mockResolvedValue(result);
    const signal = new AbortController().signal;
    await expect(createBackfill(input, 'request-key', signal)).resolves.toEqual(
      result
    );
    expect(platformRequest).toHaveBeenCalledWith('/api/v1/jobs/backfills', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'request-key',
      },
      body: JSON.stringify(input),
      signal,
    });
  });

  it('does not present an invalid mutation response as accepted', async () => {
    vi.mocked(platformRequest).mockResolvedValue({ status: 'SUCCESS' });
    await expect(createBackfill({} as BackfillRequest, 'key')).rejects.toThrow(
      'Check request history'
    );
  });

  it('forwards the complete cursor and observation timestamp without changing them', async () => {
    const observedAt = '2026-11-15T10:00:00Z';
    const beforeCreatedAt = '2026-11-14T10:00:00Z';
    vi.mocked(platformRequest).mockResolvedValue({
      observedAt,
      items: [],
      hasMore: false,
      nextCursor: null,
    });
    await listAtomicRequests({
      status: 'history',
      observedAt,
      beforeCreatedAt,
      beforeId: 'id-2',
    });
    const [path] = vi.mocked(platformRequest).mock.calls[0];
    const url = new URL(path, 'http://console.test');
    expect(url.pathname).toBe('/api/v1/jobs/atomic-work/requests');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      status: 'history',
      limit: '20',
      observedAt,
      beforeCreatedAt,
      beforeId: 'id-2',
    });
  });

  it('rejects an incomplete next-page cursor rather than silently repeating a page', async () => {
    vi.mocked(platformRequest).mockResolvedValue({
      observedAt: '2026-11-15T10:00:00Z',
      items: [],
      hasMore: true,
      nextCursor: { beforeId: 'id-2' },
    });
    await expect(listAtomicRequests({ status: 'active' })).rejects.toThrow(
      'contract is unavailable'
    );
  });
});

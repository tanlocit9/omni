import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { getAtomicSnapshot, getPublishedUnfinished, inspectAtomicWork } from './api';
import { WorkOperations } from './WorkOperations';
import type { AtomicSnapshot } from './types';

vi.mock('./api', () => ({ getAtomicSnapshot: vi.fn(), getPublishedUnfinished: vi.fn(), inspectAtomicWork: vi.fn() }));
const snapshot: AtomicSnapshot = {
  version: 2, capturedAt: '2026-10-30T10:00:00Z', observedAt: '2026-10-30T10:00:00Z',
  since: '2026-10-29T10:00:00Z', until: '2026-10-30T10:00:00Z', validWindow: true,
  hasMoreRequests: false, nextCursor: null, attemptsTruncated: false, edgesTruncated: false,
  requests: [], attempts: [], definitions: [],
};
afterEach(cleanup);
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getAtomicSnapshot).mockResolvedValue(snapshot);
  vi.mocked(getPublishedUnfinished).mockResolvedValue({ version: 2, observedAt: snapshot.observedAt, hasMore: false, nextCursor: null, rows: [] });
});
describe('Work Operations', () => {
  it('separates empty snapshots from unknown worker/readiness and future tracing', async () => {
    render(<WorkOperations />);
    expect(screen.getByText('Loading work snapshot…')).toBeInTheDocument();
    expect(await screen.findByText('No requests created in this window.')).toBeInTheDocument();
    expect(screen.getByText(/Worker processing and dataset READY are unknown/)).toBeInTheDocument();
    expect(screen.getByText(/Query tracing is reserved, not implemented/)).toBeInTheDocument();
    expect(await screen.findByText('No matching publications in this page.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel|retry|trigger/i })).not.toBeInTheDocument();
  });
  it.each([401, 403, 503])('reports HTTP %s without presenting an empty success', async status => {
    vi.mocked(getAtomicSnapshot).mockRejectedValue(new ApiError('failure', status));
    render(<WorkOperations />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('No requests created in this window.')).not.toBeInTheDocument();
  });
  it('keeps the exact observation window across request pages and resets it on refresh', async () => {
    vi.mocked(getAtomicSnapshot).mockResolvedValue({ ...snapshot, hasMoreRequests: true, nextCursor: 'next-request', attemptsTruncated: true });
    render(<WorkOperations />);
    fireEvent.click(await screen.findByRole('button', { name: 'Next request page' }));
    await waitFor(() => expect(getAtomicSnapshot).toHaveBeenLastCalledWith({ hours: 24, since: snapshot.since, until: snapshot.until, observedAt: snapshot.observedAt, afterId: 'next-request' }, expect.any(AbortSignal)));
    expect(await screen.findByText(/Partial detail:/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh snapshot' })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: 'Refresh snapshot' }));
    await waitFor(() => expect(getAtomicSnapshot).toHaveBeenLastCalledWith({ hours: 24 }, expect.any(AbortSignal)));
  });
  it('renders per-attempt identity and loads provenance on selection', async () => {
    const workId=`work:${'a'.repeat(64)}`;
    vi.mocked(getAtomicSnapshot).mockResolvedValue({ ...snapshot, attempts: [{
      atomicWorkId: workId, atomicAttemptId: 'attempt-1', executionId: 'execution-1',
      parentExecutionId: 'parent-1', jobRunId: 'run-1', jobType: 'SYNC_STOCK_PRICE',
      workType: 'SYMBOL', workKey: 'HOSE-VCB', businessDate: '2026-10-29', status: 'RUNNING',
      observation: 'PUBLISHED_BUT_UNFINISHED', ownerCount: 2, activeOwnerCount: 2,
      upstreamCount: 1, upstreamFailed: 0, upstreamUnfinished: 0, publishedMessages: 1,
      pendingMessages: 0, blockedMessages: 0, liveLeases: 0, expiredTokenMessages: 0,
      retryDelayedMessages: 0, firstPublishedAt: snapshot.since, publishAgeSeconds: 3600,
      publishedUnfinishedOver30m: true, identityMatches: true, statusIdentityMatches: null,
    }] });
    vi.mocked(inspectAtomicWork).mockResolvedValue({ atomicWorkId: workId, attemptsTruncated: false, ownersTruncated: false, upstreamsTruncated: false, attempts: [], owners: [], upstreams: [] });
    render(<WorkOperations />);
    fireEvent.click(await screen.findByRole('button', { name: workId }));
    expect(await screen.findByRole('heading', { name: 'Work provenance' })).toBeInTheDocument();
    await waitFor(() => expect(inspectAtomicWork).toHaveBeenCalledWith(workId, expect.any(AbortSignal)));
    expect(screen.getByText('Investigate; not automatic FAILED')).toBeInTheDocument();
  });
});

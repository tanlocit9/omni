import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { listAtomicRequests } from './api';
import { RequestList } from './RequestList';
import type { AtomicRequestPage, AtomicRequestSummary } from './types';

vi.mock('./api', () => ({ listAtomicRequests: vi.fn() }));

const request: AtomicRequestSummary = {
  requestId: 'request-one',
  executionId: 'execution-one',
  targetDefinitionId: 'definition-one',
  goal: 'SYNC_SIGNALS',
  mode: 'BACKFILL',
  state: 'CANCELLED',
  createdAt: '2026-11-08T01:00:00Z',
  startedAt: '2026-11-08T01:00:00Z',
  finishedAt: null,
  elapsedMs: 65000,
  totalMembers: 3,
  memberCounts: { FAILED: 1, RUNNING: 2 },
  unfinishedMembers: 2,
  isSettled: false,
  hasFailure: true,
  jobs: [
    {
      jobType: 'SYNC_SIGNALS',
      totalMembers: 3,
      memberCounts: { FAILED: 1, RUNNING: 2 },
    },
  ],
  recordsSynced: null,
  recordsSkipped: 0,
};

const page: AtomicRequestPage = {
  observedAt: '2026-11-08T01:01:05Z',
  items: [request],
  hasMore: false,
  nextCursor: null,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listAtomicRequests).mockResolvedValue(page);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Work request list', () => {
  it('opens the exact request selected from the list', async () => {
    const onSelectRequest = vi.fn();
    render(<RequestList onSelectRequest={onSelectRequest} />);
    fireEvent.click(
      await screen.findByRole('button', {
        name: `View request ${request.requestId}`,
      })
    );
    expect(onSelectRequest).toHaveBeenCalledWith(request.requestId);
  });
  it('keeps request closure separate from member settlement and unknown counters', async () => {
    render(<RequestList acceptedRequestId={request.requestId} />);
    expect(await screen.findByText('CANCELLED')).toBeInTheDocument();
    expect(screen.getByText('Members not settled')).toBeInTheDocument();
    expect(screen.getByText('Unfinished members: 2')).toBeInTheDocument();
    expect(screen.getByText('Member failures reported')).toBeInTheDocument();
    expect(screen.getByText('Synced: Unknown')).toBeInTheDocument();
    expect(screen.getByText('Skipped: 0')).toBeInTheDocument();
    expect(screen.getByText('Elapsed: 1 min 5 sec')).toBeInTheDocument();
    expect(screen.getByText('Newly accepted')).toBeInTheDocument();
    expect(screen.getByText('Job breakdown (1)')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /cancel|retry|repair/i })
    ).not.toBeInTheDocument();
    expect(listAtomicRequests).toHaveBeenCalledWith(
      { status: 'active', limit: 20 },
      expect.any(AbortSignal)
    );
  });

  it('preserves the server cursor and observation, then resets pagination on refresh', async () => {
    const cursor = {
      beforeCreatedAt: request.createdAt,
      beforeId: request.requestId,
    };
    vi.mocked(listAtomicRequests)
      .mockResolvedValueOnce({ ...page, hasMore: true, nextCursor: cursor })
      .mockResolvedValueOnce({ ...page, items: [] });
    render(<RequestList />);
    fireEvent.click(
      await screen.findByRole('button', { name: 'Next requests page' })
    );
    await waitFor(() =>
      expect(listAtomicRequests).toHaveBeenLastCalledWith(
        { status: 'active', limit: 20, observedAt: page.observedAt, ...cursor },
        expect.any(AbortSignal)
      )
    );
    expect(
      await screen.findByText(/Automatic refresh is paused/)
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh requests' }));
    await waitFor(() =>
      expect(listAtomicRequests).toHaveBeenLastCalledWith(
        { status: 'active', limit: 20 },
        expect.any(AbortSignal)
      )
    );
    fireEvent.change(screen.getByRole('combobox', { name: 'Request filter' }), {
      target: { value: 'history' },
    });
    await waitFor(() =>
      expect(listAtomicRequests).toHaveBeenLastCalledWith(
        { status: 'history', limit: 20 },
        expect.any(AbortSignal)
      )
    );
  });

  it('ignores responses from aborted filters and resets to active when a request is accepted', async () => {
    const old = deferred<AtomicRequestPage>();
    vi.mocked(listAtomicRequests).mockReturnValueOnce(old.promise);
    const { rerender } = render(<RequestList />);
    const oldSignal = vi.mocked(listAtomicRequests).mock.calls[0][1];
    fireEvent.change(screen.getByRole('combobox', { name: 'Request filter' }), {
      target: { value: 'history' },
    });
    expect(await screen.findByText('CANCELLED')).toBeInTheDocument();
    expect(oldSignal?.aborted).toBe(true);
    await act(async () => {
      old.resolve({
        ...page,
        items: [{ ...request, requestId: 'stale-request', state: 'SUCCESS' }],
      });
    });
    expect(screen.queryByText('stale-request')).not.toBeInTheDocument();
    rerender(<RequestList refreshToken={1} acceptedRequestId="new-request" />);
    await waitFor(() =>
      expect(listAtomicRequests).toHaveBeenLastCalledWith(
        { status: 'active', limit: 20 },
        expect.any(AbortSignal)
      )
    );
    expect(
      screen.getByRole('combobox', { name: 'Request filter' })
    ).toHaveValue('active');
    expect(
      screen.getByText(/absence does not prove completion/)
    ).toBeInTheDocument();
  });

  it('polls only after a read completes and cleans up pending polls on unmount', async () => {
    vi.useFakeTimers();
    const slow = deferred<AtomicRequestPage>();
    vi.mocked(listAtomicRequests).mockReturnValueOnce(slow.promise);
    const { unmount } = render(<RequestList />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(1);
    await act(async () => {
      slow.resolve(page);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4999);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(2);
    expect(listAtomicRequests).toHaveBeenLastCalledWith(
      { status: 'active', limit: 20 },
      expect.any(AbortSignal)
    );
    const signal = vi.mocked(listAtomicRequests).mock.calls[1][1];
    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(2);
  });

  it('does not poll cursor pages or the history filter', async () => {
    vi.useFakeTimers();
    vi.mocked(listAtomicRequests).mockResolvedValueOnce({
      ...page,
      hasMore: true,
      nextCursor: {
        beforeCreatedAt: request.createdAt,
        beforeId: request.requestId,
      },
    });
    render(<RequestList />);
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Next requests page' }));
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(2);
    fireEvent.change(screen.getByRole('combobox', { name: 'Request filter' }), {
      target: { value: 'history' },
    });
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(listAtomicRequests).toHaveBeenCalledTimes(3);
  });

  it('retains an explicitly stale observation when polling fails', async () => {
    vi.useFakeTimers();
    vi.mocked(listAtomicRequests)
      .mockResolvedValueOnce(page)
      .mockRejectedValueOnce(new ApiError('unavailable', 503));
    render(<RequestList />);
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/Showing stale data/);
    expect(screen.getByText('CANCELLED')).toBeInTheDocument();
    expect(
      screen.queryByText(/No active requests on this page/)
    ).not.toBeInTheDocument();
  });

  it.each([401, 403, 503])(
    'reports HTTP %s as unknown rather than an empty success',
    async (status) => {
      vi.mocked(listAtomicRequests).mockRejectedValue(
        new ApiError('failure', status)
      );
      render(<RequestList />);
      expect(await screen.findByRole('alert')).toHaveTextContent(
        /not an empty result/
      );
      expect(
        screen.queryByText(/No active requests on this page/)
      ).not.toBeInTheDocument();
    }
  );

  it('shows a genuine empty response without inferring previous requests succeeded', async () => {
    vi.mocked(listAtomicRequests).mockResolvedValue({ ...page, items: [] });
    render(<RequestList />);
    expect(
      await screen.findByText(/No active requests on this page. Check History/)
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

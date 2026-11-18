import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { getAttemptReadiness } from './api';
import { OutputReadinessPanel } from './OutputReadinessPanel';
import type { AtomicRequestMember, WorkReadinessProof } from './types';

vi.mock('./api', () => ({ getAttemptReadiness: vi.fn() }));
const member: AtomicRequestMember = {
  atomicWorkId: 'work-one',
  atomicAttemptId: 'old-attempt',
  executionId: 'old-execution',
  jobRunId: null,
  isTarget: true,
  executionStatus: 'FAILED',
  startedAt: null,
  finishedAt: null,
  outboxStatus: 'PUBLISHED',
  outputReady: true,
  readyOutputs: [
    {
      dataset: 'signals',
      partition: { model_version: 'v1' },
      dataVersion: 'new-version',
      attemptId: 'new-attempt',
      executionId: 'new-execution',
      businessDate: '2026-11-14',
    },
  ],
  reasonCode: null,
  orphaned: false,
  requiresExecutionReview: false,
};
const proof: WorkReadinessProof = {
  atomicWorkId: 'work-one',
  atomicAttemptId: 'old-attempt',
  executionId: 'old-execution',
  businessDate: '2026-11-14',
  dataVersion: 'old-version',
  output: {
    dataset: 'signals',
    partition: { model_version: 'v1' },
    rowCount: 0,
    schemaHash: 'schema-one',
  },
  approvedInputs: [
    {
      dataset: 'indicators',
      partition: { date: '2026-11-14' },
      dataVersion: 'frozen-input-version',
    },
  ],
  upstreamWorks: [
    {
      atomicWorkId: 'upstream-work',
      atomicAttemptId: 'frozen-upstream-attempt',
    },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getAttemptReadiness).mockResolvedValue([]);
});

describe('Output evidence', () => {
  it('does not turn a failed selected attempt into success when a newer work proof is READY', async () => {
    render(<OutputReadinessPanel member={member} />);
    await screen.findByText(/No retained READY proof is available/);
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByText('Ready')).toBeInTheDocument();
    expect(
      screen.getByText('Provided by a different attempt')
    ).toBeInTheDocument();
    expect(screen.getByText('new-version')).toBeInTheDocument();
    expect(getAttemptReadiness).toHaveBeenCalledWith(
      'work-one',
      'old-attempt',
      expect.any(AbortSignal)
    );
    expect(getAttemptReadiness).toHaveBeenCalledTimes(1);
  });

  it.each([
    [false, 'Not ready'],
    [null, 'Unknown'],
  ] as const)(
    'preserves current coverage %s as %s',
    async (outputReady, label) => {
      render(
        <OutputReadinessPanel
          member={{ ...member, outputReady, readyOutputs: [] }}
        />
      );
      expect(screen.getByText(label)).toBeInTheDocument();
      await screen.findByText(/No retained READY proof is available/);
    }
  );

  it('renders selected proof and frozen lineage, including a zero row count', async () => {
    vi.mocked(getAttemptReadiness).mockResolvedValue([proof]);
    render(<OutputReadinessPanel member={member} />);
    expect(await screen.findByText('old-version')).toBeInTheDocument();
    expect(screen.getByText('frozen-input-version')).toBeInTheDocument();
    expect(screen.getByText('frozen-upstream-attempt')).toBeInTheDocument();
    expect(screen.getByText('Rows: 0')).toBeInTheDocument();
    expect(screen.getByText('schema-one')).toBeInTheDocument();
  });

  it.each([401, 403, 409, 503])(
    'keeps HTTP %s proof failure separate from current coverage',
    async (status) => {
      vi.mocked(getAttemptReadiness).mockRejectedValue(
        new ApiError('unavailable', status)
      );
      render(<OutputReadinessPanel member={member} />);
      expect(await screen.findByRole('alert')).toBeInTheDocument();
      expect(screen.getByText('Ready')).toBeInTheDocument();
      expect(
        screen.queryByText(/No retained READY proof is available/)
      ).not.toBeInTheDocument();
      expect(getAttemptReadiness).toHaveBeenCalledTimes(1);
    }
  );

  it('rejects proof belonging to another execution even with the same selected identity', async () => {
    vi.mocked(getAttemptReadiness).mockResolvedValue([
      { ...proof, executionId: 'wrong-execution' },
    ]);
    render(<OutputReadinessPanel member={member} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Proof identity does not match'
    );
    expect(screen.queryByText('old-version')).not.toBeInTheDocument();
  });

  it('clears old selected proof, aborts stale reads and supports explicit refresh', async () => {
    let resolveOld!: (proofs: WorkReadinessProof[]) => void;
    vi.mocked(getAttemptReadiness).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      })
    );
    const { rerender, unmount } = render(
      <OutputReadinessPanel member={member} />
    );
    const signal = vi.mocked(getAttemptReadiness).mock.calls[0][2];
    rerender(
      <OutputReadinessPanel
        member={{
          ...member,
          atomicAttemptId: 'next-attempt',
          executionId: 'next-execution',
        }}
      />
    );
    expect(screen.queryByText('old-attempt')).not.toBeInTheDocument();
    expect(signal?.aborted).toBe(true);
    await screen.findByText(/No retained READY proof is available/);
    await act(async () => {
      resolveOld([proof]);
    });
    expect(screen.queryByText('old-version')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh proof' }));
    await screen.findByText(/No retained READY proof is available/);
    expect(getAttemptReadiness).toHaveBeenCalledTimes(3);
    unmount();
    expect(vi.mocked(getAttemptReadiness).mock.calls[2][2]?.aborted).toBe(true);
  });
});

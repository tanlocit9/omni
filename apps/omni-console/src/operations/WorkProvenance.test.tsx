import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { inspectAtomicWork } from './api';
import { WorkProvenance } from './WorkProvenance';
import type { WorkInspection } from './types';

vi.mock('./api', () => ({ inspectAtomicWork: vi.fn() }));
const evidence: WorkInspection = {
  atomicWorkId: 'work-one',
  attemptsTruncated: false,
  ownersTruncated: false,
  upstreamsTruncated: false,
  attempts: [
    {
      atomicAttemptId: 'attempt-one',
      executionId: 'execution-one',
      status: 'FAILED',
      identityMatches: false,
      statusAtomicWorkId: null,
      approvedInputs: [
        {
          dataset: 'eod',
          partition: { date: '2026-11-14' },
          dataVersion: 'frozen-version',
        },
      ],
      traceContextPresent: true,
      traceContextType: 'object',
    },
  ],
  owners: [
    {
      atomicAttemptId: 'attempt-one',
      graphRequestId: 'request-one',
      state: 'FAILED',
      isTarget: true,
    },
  ],
  upstreams: [
    {
      atomicAttemptId: 'attempt-one',
      upstreamAtomicAttemptId: 'upstream-attempt',
      upstreamAtomicWorkId: 'upstream-work',
      upstreamExecutionId: 'upstream-execution',
      status: 'SUCCESS',
    },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(inspectAtomicWork).mockResolvedValue(evidence);
});

describe('Work provenance', () => {
  it('renders readable ownership and frozen lineage with exact navigation callbacks', async () => {
    const onSelectRequest = vi.fn(),
      onSelectWork = vi.fn();
    render(
      <WorkProvenance
        atomicWorkId="work-one"
        onSelectRequest={onSelectRequest}
        onSelectWork={onSelectWork}
      />
    );
    await screen.findByText('Frozen approved inputs');
    await screen.findByText('frozen-version');
    expect(screen.getByText('Identity: Mismatch')).toBeInTheDocument();
    expect(screen.getByText('Trace context recorded')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'request-one' }));
    fireEvent.click(screen.getByRole('button', { name: 'upstream-work' }));
    expect(onSelectRequest).toHaveBeenCalledWith('request-one');
    expect(onSelectWork).toHaveBeenCalledWith('upstream-work');
    expect(screen.getByText('Advanced provenance JSON')).toBeInTheDocument();
  });

  it('shows truncation and unknown approved inputs without claiming no dependencies', async () => {
    vi.mocked(inspectAtomicWork).mockResolvedValue({
      ...evidence,
      attemptsTruncated: true,
      attempts: [{ ...evidence.attempts[0], approvedInputs: null }],
      owners: [],
      upstreams: [],
    });
    render(<WorkProvenance atomicWorkId="work-one" />);
    expect(
      await screen.findByText('Approved inputs not reported')
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Work provenance is truncated/)
    ).toBeInTheDocument();
    expect(
      screen.getByText(/No upstream links are listed in this response/)
    ).toBeInTheDocument();
  });

  it('reports unavailable evidence rather than empty ownership', async () => {
    vi.mocked(inspectAtomicWork).mockRejectedValue(new Error('unavailable'));
    render(<WorkProvenance atomicWorkId="work-one" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'missing evidence'
    );
    expect(
      screen.queryByText('No owners are listed in this response.')
    ).not.toBeInTheDocument();
  });

  it('ignores aborted work responses and resets the rendered identity', async () => {
    let resolveOld!: (value: WorkInspection) => void;
    vi.mocked(inspectAtomicWork).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      })
    );
    const { rerender } = render(<WorkProvenance atomicWorkId="work-one" />);
    const signal = vi.mocked(inspectAtomicWork).mock.calls[0][1];
    vi.mocked(inspectAtomicWork).mockResolvedValue({
      ...evidence,
      atomicWorkId: 'work-two',
      attempts: [],
      owners: [],
      upstreams: [],
    });
    rerender(<WorkProvenance atomicWorkId="work-two" />);
    await screen.findByText('No attempts are listed in this response.');
    expect(signal?.aborted).toBe(true);
    await act(async () => {
      resolveOld(evidence);
    });
    expect(screen.queryByText('frozen-version')).not.toBeInTheDocument();
  });
});

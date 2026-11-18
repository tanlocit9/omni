import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { getAtomicRequestDetail } from './api';
import { RequestDetail } from './RequestDetail';
import type { AtomicRequestDetail, AtomicRequestMember } from './types';

vi.mock('./api', () => ({ getAtomicRequestDetail: vi.fn() }));
vi.mock('./OutputReadinessPanel', () => ({
  OutputReadinessPanel: ({ member }: { member: AtomicRequestMember }) => (
    <p>
      Proof selected: {member.atomicWorkId} / {member.atomicAttemptId}
    </p>
  ),
}));

const member: AtomicRequestMember = {
  atomicWorkId: 'work-one',
  atomicAttemptId: 'attempt-one',
  jobRunId: 'run-one',
  executionId: 'execution-one',
  isTarget: true,
  executionStatus: 'FAILED',
  startedAt: null,
  finishedAt: null,
  outboxStatus: 'PUBLISHED',
  outputReady: true,
  readyOutputs: [],
  reasonCode: 'ATOMIC_UPSTREAM_FAILED',
  orphaned: true,
  requiresExecutionReview: true,
};
const detail: AtomicRequestDetail = {
  request: {
    requestId: 'request-one',
    executionId: 'root-one',
    state: 'FAILED',
    targetDefinitionId: 'target-one',
    createdAt: '2026-11-15T00:00:00Z',
  },
  members: [member],
  memberCounts: { FAILED: 1 },
  unfinishedMembers: 1,
  isSettled: false,
  hasFailure: true,
};
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getAtomicRequestDetail).mockResolvedValue(detail);
});

describe('Request detail', () => {
  it('keeps failed request state separate from unsettled members and opens exact member evidence', async () => {
    const onClose = vi.fn(),
      onInspectWork = vi.fn();
    render(
      <RequestDetail
        requestId="request-one"
        onClose={onClose}
        onInspectWork={onInspectWork}
      />
    );
    expect(screen.getByRole('status')).toHaveTextContent(
      'Loading request detail'
    );
    await screen.findByText('Not settled');
    expect(screen.getByText('Request state').parentElement).toHaveTextContent(
      'FAILED'
    );
    expect(
      within(screen.getAllByRole('row')[1]).getByText('FAILED')
    ).toBeInTheDocument();
    expect(screen.getByText('Execution review required')).toBeInTheDocument();
    expect(screen.getByText('No active request owner')).toBeInTheDocument();
    expect(screen.queryByText(/Proof selected/)).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole('button', { name: 'View outputs attempt-one' })
    );
    expect(
      screen.getByText('Proof selected: work-one / attempt-one')
    ).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole('button', { name: 'Inspect work provenance' })
    );
    expect(onInspectWork).toHaveBeenCalledWith('work-one');
    fireEvent.click(screen.getByRole('button', { name: 'Close request' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('pages rendered rows without claiming the backend response was bounded', async () => {
    vi.mocked(getAtomicRequestDetail).mockResolvedValue({
      ...detail,
      members: Array.from({ length: 51 }, (_, index) => ({
        ...member,
        atomicAttemptId: `attempt-${index}`,
        atomicWorkId: `work-${index}`,
        isTarget: index === 0,
      })),
    });
    render(<RequestDetail requestId="request-one" onClose={vi.fn()} />);
    await screen.findByRole('button', { name: 'View outputs attempt-0' });
    expect(
      screen.getAllByRole('button', { name: /^View outputs / })
    ).toHaveLength(50);
    expect(
      screen.getByText(/Frontend pages limit rendered rows only/)
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next members' }));
    expect(
      screen.getAllByRole('button', { name: /^View outputs / })
    ).toHaveLength(1);
    fireEvent.change(screen.getByLabelText('Member role'), {
      target: { value: 'target' },
    });
    expect(
      screen.getByRole('button', { name: 'View outputs attempt-0' })
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Find member'), {
      target: { value: 'not-present' },
    });
    expect(
      screen.getByText('No members match these filters.')
    ).toBeInTheDocument();
    expect(getAtomicRequestDetail).toHaveBeenCalledTimes(1);
  });

  it.each([401, 403, 404, 503])(
    'reports HTTP %s without presenting empty members as success',
    async (status) => {
      vi.mocked(getAtomicRequestDetail).mockRejectedValue(
        new ApiError('failure', status)
      );
      render(<RequestDetail requestId="request-one" onClose={vi.fn()} />);
      expect(await screen.findByRole('alert')).toBeInTheDocument();
      expect(
        screen.queryByText(/No members are recorded/)
      ).not.toBeInTheDocument();
    }
  );

  it('clears the old identity and ignores stale results on request change', async () => {
    let resolveOld!: (result: AtomicRequestDetail) => void;
    vi.mocked(getAtomicRequestDetail).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      })
    );
    const { rerender } = render(
      <RequestDetail requestId="request-one" onClose={vi.fn()} />
    );
    const signal = vi.mocked(getAtomicRequestDetail).mock.calls[0][1];
    vi.mocked(getAtomicRequestDetail).mockResolvedValue({
      ...detail,
      request: { ...detail.request, requestId: 'request-two' },
      members: [],
    });
    rerender(<RequestDetail requestId="request-two" onClose={vi.fn()} />);
    await screen.findByText(/No members are recorded/);
    expect(signal?.aborted).toBe(true);
    await act(async () => {
      resolveOld(detail);
    });
    expect(
      screen.queryByRole('button', { name: 'View outputs attempt-one' })
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh detail' }));
    await waitFor(() =>
      expect(getAtomicRequestDetail).toHaveBeenCalledTimes(3)
    );
  });
});

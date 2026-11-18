import { beforeEach, describe, expect, it, vi } from 'vitest';
import { platformRequest } from '../api';
import { getAtomicRequestDetail, getAttemptReadiness } from './detailApi';

vi.mock('../api', () => ({ platformRequest: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

const detail = {
  request: {
    id: 'request-1',
    state: 'FAILED',
    execution_id: 'root-1',
    target_definition_id: 'definition-1',
    created_at: '2026-11-15T00:00:00Z',
    graph_json: { internal: 'not exposed' },
  },
  members: [
    {
      work_id: 'work-1',
      attempt_id: 'attempt-old',
      job_run_id: 'run-1',
      execution_id: 'execution-old',
      is_target: true,
      status: 'FAILED',
      executionStatus: 'FAILED',
      started_at: null,
      finished_at: null,
      outbox_status: 'PUBLISHED',
      outputReady: true,
      readyOutputs: [
        {
          dataset: 'signals',
          partition: { model_version: 'v1' },
          dataVersion: 'version-new',
          executionId: 'execution-new',
          attemptId: 'attempt-new',
          businessDate: '2026-11-14',
        },
      ],
      reasonCode: 'ATOMIC_UPSTREAM_FAILED',
      orphaned: false,
      requiresExecutionReview: true,
    },
  ],
  memberCounts: { FAILED: 1 },
  unfinishedMembers: 0,
  isSettled: true,
  hasFailure: true,
};

const proof = {
  atomicWorkId: 'work-1',
  atomicAttemptId: 'attempt-old',
  executionId: 'execution-old',
  businessDate: '2026-11-14',
  dataVersion: 'version-old',
  output: {
    dataset: 'signals',
    partition: { model_version: 'v1' },
    rowCount: 0,
    schemaHash: 'schema-1',
    storageVersionId: 'private-coordinate',
    snapshot: 'private-path',
  },
  approvedInputs: [
    {
      dataset: 'indicators',
      partition: { date: '2026-11-14' },
      dataVersion: 'frozen-v1',
    },
  ],
  upstreamWorks: [
    { atomicWorkId: 'upstream-work', atomicAttemptId: 'upstream-attempt' },
  ],
};

describe('request detail and attempt evidence API', () => {
  it('normalizes mixed backend fields without confusing current READY and failed execution', async () => {
    vi.mocked(platformRequest).mockResolvedValue(detail);
    const result = await getAtomicRequestDetail('request-1');
    expect(result.request).toEqual({
      requestId: 'request-1',
      state: 'FAILED',
      executionId: 'root-1',
      targetDefinitionId: 'definition-1',
      createdAt: '2026-11-15T00:00:00Z',
    });
    expect(result.members[0]).toMatchObject({
      atomicWorkId: 'work-1',
      atomicAttemptId: 'attempt-old',
      executionStatus: 'FAILED',
      outputReady: true,
      requiresExecutionReview: true,
      readyOutputs: [{ attemptId: 'attempt-new' }],
    });
    expect(result.request).not.toHaveProperty('graph_json');
    expect(platformRequest).toHaveBeenCalledWith(
      '/api/v1/jobs/atomic-work/requests/request-1',
      { signal: undefined }
    );
  });

  it('preserves unknown current coverage and rejects missing required review flags', async () => {
    vi.mocked(platformRequest).mockResolvedValue({
      ...detail,
      members: [{ ...detail.members[0], outputReady: null }],
    });
    expect(
      (await getAtomicRequestDetail('request-1')).members[0].outputReady
    ).toBeNull();
    vi.mocked(platformRequest).mockResolvedValue({
      ...detail,
      members: [{ ...detail.members[0], orphaned: undefined }],
    });
    await expect(getAtomicRequestDetail('request-1')).rejects.toThrow(
      'contract is unavailable'
    );
  });

  it('rejects the wrong request rather than showing it under the selected identity', async () => {
    vi.mocked(platformRequest).mockResolvedValue(detail);
    await expect(getAtomicRequestDetail('another-request')).rejects.toThrow(
      'contract is unavailable'
    );
  });

  it('requests exact attempt proofs and exposes logical evidence without physical paths', async () => {
    vi.mocked(platformRequest).mockResolvedValue({
      atomicWorkId: 'work-1',
      items: [proof],
    });
    const result = await getAttemptReadiness('work-1', 'attempt-old');
    const [path] = vi.mocked(platformRequest).mock.calls[0];
    const url = new URL(path, 'http://console.test');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      atomicWorkId: 'work-1',
      atomicAttemptId: 'attempt-old',
    });
    expect(result[0]).toMatchObject({
      atomicAttemptId: 'attempt-old',
      dataVersion: 'version-old',
      output: { rowCount: 0 },
      approvedInputs: [{ dataVersion: 'frozen-v1' }],
    });
    expect(result[0].output).not.toHaveProperty('snapshot');
    expect(result[0].output).not.toHaveProperty('storageVersionId');
  });

  it('keeps absent selected proof absent, without falling back to current work', async () => {
    vi.mocked(platformRequest).mockResolvedValue({
      atomicWorkId: 'work-1',
      items: [],
    });
    await expect(getAttemptReadiness('work-1', 'attempt-old')).resolves.toEqual(
      []
    );
    expect(platformRequest).toHaveBeenCalledTimes(1);
  });

  it.each(['work', 'attempt'])(
    'rejects mismatched %s evidence',
    async (mismatch) => {
      vi.mocked(platformRequest).mockResolvedValue({
        atomicWorkId: 'work-1',
        items: [
          {
            ...proof,
            ...(mismatch === 'work'
              ? { atomicWorkId: 'other-work' }
              : { atomicAttemptId: 'attempt-new' }),
          },
        ],
      });
      await expect(
        getAttemptReadiness('work-1', 'attempt-old')
      ).rejects.toThrow('contract is unavailable');
    }
  );

  it.each(['detail', 'proof'])(
    'rejects with the original Error and calls platformRequest exactly once on network failure (%s)',
    async (variant) => {
      const sentinel = new Error('network failure sentinel');
      vi.mocked(platformRequest).mockRejectedValue(sentinel);

      if (variant === 'detail') {
        await expect(getAtomicRequestDetail('request-1')).rejects.toBe(
          sentinel
        );
      } else {
        await expect(getAttemptReadiness('work-1', 'attempt-old')).rejects.toBe(
          sentinel
        );
      }

      expect(platformRequest).toHaveBeenCalledTimes(1);
    }
  );

  it.each(['detail', 'proof'])(
    'forwards the AbortSignal to platformRequest unchanged (%s)',
    async (variant) => {
      const controller = new AbortController();
      const { signal } = controller;

      if (variant === 'detail') {
        vi.mocked(platformRequest).mockResolvedValue(detail);
        await getAtomicRequestDetail('request-1', signal);
        expect(vi.mocked(platformRequest).mock.calls[0][1]).toEqual({ signal });
        expect(
          (
            vi.mocked(platformRequest).mock.calls[0][1] as {
              signal: AbortSignal;
            }
          ).signal
        ).toBe(signal);
      } else {
        vi.mocked(platformRequest).mockResolvedValue({
          atomicWorkId: 'work-1',
          items: [proof],
        });
        await getAttemptReadiness('work-1', 'attempt-old', signal);
        expect(vi.mocked(platformRequest).mock.calls[0][1]).toEqual({ signal });
        expect(
          (
            vi.mocked(platformRequest).mock.calls[0][1] as {
              signal: AbortSignal;
            }
          ).signal
        ).toBe(signal);
      }
    }
  );

  it('rejects a proof whose output.partition contains a nested object as unavailable', async () => {
    const nestedPartitionProof = {
      ...proof,
      output: { ...proof.output, partition: { nested: { unsafe: 'value' } } },
    };
    vi.mocked(platformRequest).mockResolvedValue({
      atomicWorkId: 'work-1',
      items: [nestedPartitionProof],
    });
    await expect(getAttemptReadiness('work-1', 'attempt-old')).rejects.toThrow(
      'contract is unavailable'
    );
  });
});

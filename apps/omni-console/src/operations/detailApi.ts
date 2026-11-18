import { platformRequest } from '../api';
import type {
  AtomicRequestDetail,
  AtomicRequestMember,
  WorkReadinessProof,
} from './types';

function unavailable(): never {
  throw new Error('The request evidence contract is unavailable.');
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    unavailable();
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== 'string' || !value) unavailable();
  return value;
}

function optionalText(value: unknown): string | null {
  return value == null ? null : text(value);
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') unavailable();
  return value;
}

function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0)
    unavailable();
  return value;
}

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) unavailable();
  return value;
}

function partition(value: unknown): Record<string, string | number | boolean> {
  const fields = object(value);
  if (
    Object.values(fields).some(
      (item) => !['string', 'number', 'boolean'].includes(typeof item)
    )
  )
    unavailable();
  return fields as Record<string, string | number | boolean>;
}

function member(value: unknown): AtomicRequestMember {
  const row = object(value);
  return {
    atomicWorkId: text(row.work_id),
    atomicAttemptId: text(row.attempt_id),
    jobRunId: optionalText(row.job_run_id),
    executionId: text(row.execution_id),
    isTarget: flag(row.is_target),
    executionStatus: text(row.executionStatus),
    startedAt: optionalText(row.started_at),
    finishedAt: optionalText(row.finished_at),
    outboxStatus: optionalText(row.outbox_status),
    outputReady: row.outputReady == null ? null : flag(row.outputReady),
    readyOutputs: array(row.readyOutputs).map((value) => {
      const proof = object(value);
      return {
        dataset: text(proof.dataset),
        partition: partition(proof.partition),
        dataVersion: text(proof.dataVersion),
        executionId: text(proof.executionId),
        attemptId: text(proof.attemptId),
        businessDate: optionalText(proof.businessDate),
      };
    }),
    reasonCode: optionalText(row.reasonCode),
    orphaned: flag(row.orphaned),
    requiresExecutionReview: flag(row.requiresExecutionReview),
  };
}

export async function getAtomicRequestDetail(
  requestId: string,
  signal?: AbortSignal
): Promise<AtomicRequestDetail> {
  const raw = object(
    await platformRequest<unknown>(
      `/api/v1/jobs/atomic-work/requests/${encodeURIComponent(requestId)}`,
      { signal }
    )
  );
  const request = object(raw.request);
  if (request.id !== requestId) unavailable();
  const counts = object(raw.memberCounts);
  Object.values(counts).forEach(number);
  return {
    request: {
      requestId: text(request.id),
      state: text(request.state),
      executionId: text(request.execution_id),
      targetDefinitionId: text(request.target_definition_id),
      createdAt: text(request.created_at),
    },
    members: array(raw.members).map(member),
    memberCounts: counts as Record<string, number>,
    unfinishedMembers: number(raw.unfinishedMembers),
    isSettled: flag(raw.isSettled),
    hasFailure: flag(raw.hasFailure),
  };
}

export async function getAttemptReadiness(
  atomicWorkId: string,
  atomicAttemptId: string,
  signal?: AbortSignal
): Promise<WorkReadinessProof[]> {
  const params = new URLSearchParams({ atomicWorkId, atomicAttemptId });
  const raw = object(
    await platformRequest<unknown>(
      `/api/v1/jobs/output-readiness/work?${params}`,
      { signal }
    )
  );
  if (raw.atomicWorkId !== atomicWorkId) unavailable();
  return array(raw.items).map((value) => {
    const proof = object(value);
    if (
      proof.atomicWorkId !== atomicWorkId ||
      proof.atomicAttemptId !== atomicAttemptId
    ) {
      unavailable();
    }
    const output = object(proof.output);
    return {
      atomicWorkId,
      atomicAttemptId,
      executionId: text(proof.executionId),
      businessDate: text(proof.businessDate),
      dataVersion: text(proof.dataVersion),
      output: {
        dataset: text(output.dataset),
        partition: partition(output.partition),
        rowCount: output.rowCount == null ? null : number(output.rowCount),
        schemaHash: optionalText(output.schemaHash),
      },
      approvedInputs: array(proof.approvedInputs).map((value) => {
        const input = object(value);
        return {
          dataset: text(input.dataset),
          partition: partition(input.partition),
          dataVersion: text(input.dataVersion),
        };
      }),
      upstreamWorks: array(proof.upstreamWorks).map((value) => {
        const upstream = object(value);
        return {
          atomicWorkId: text(upstream.atomicWorkId),
          atomicAttemptId: text(upstream.atomicAttemptId),
        };
      }),
    };
  });
}

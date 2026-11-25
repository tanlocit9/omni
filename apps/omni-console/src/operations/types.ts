export interface BackfillGoal {
  goal: string;
  targets: Array<{ targetDefinitionId: string; title: string | null }>;
}

export interface BackfillRequest {
  goal: string;
  targetDefinitionId: string;
  sectorCodes?: string[];
  sectorLevel?: number;
  dateFrom?: string;
  dateTo?: string;
  exchange?: string;
  dataPolicy: 'PREFER_EXISTING' | 'REFRESH';
}

export interface BackfillAccepted {
  requestId: string;
  executionId: string;
  status: 'ACCEPTED';
  goal: string;
  dataPolicy: 'PREFER_EXISTING' | 'REFRESH';
  membershipPolicy: string;
}

export type AtomicRequestFilter = 'active' | 'history' | 'all';

export interface AtomicRequestCursor {
  beforeCreatedAt: string;
  beforeId: string;
}

export interface AtomicRequestQuery {
  status: AtomicRequestFilter;
  limit?: number;
  observedAt?: string;
  beforeCreatedAt?: string;
  beforeId?: string;
}

export interface AtomicRequestSummary {
  requestId: string;
  executionId: string;
  targetDefinitionId: string;
  goal: string;
  mode: string;
  state: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  elapsedMs: number | null;
  totalMembers: number;
  memberCounts: Record<string, number>;
  unfinishedMembers: number;
  isSettled: boolean;
  hasFailure: boolean;
  jobs: Array<{
    jobType: string;
    totalMembers: number;
    memberCounts: Record<string, number>;
  }>;
  recordsSynced: number | null;
  recordsSkipped: number | null;
}

export interface AtomicRequestPage {
  observedAt: string;
  items: AtomicRequestSummary[];
  hasMore: boolean;
  nextCursor: AtomicRequestCursor | null;
}

export interface OutputReadyEvidence {
  dataset: string;
  partition: Record<string, string | number | boolean>;
  dataVersion: string;
  executionId: string;
  attemptId: string;
  businessDate: string | null;
}

export interface AtomicRequestMember {
  atomicWorkId: string;
  atomicAttemptId: string;
  jobRunId: string | null;
  executionId: string;
  isTarget: boolean;
  executionStatus: string;
  startedAt: string | null;
  finishedAt: string | null;
  outboxStatus: string | null;
  outputReady: boolean | null;
  readyOutputs: OutputReadyEvidence[];
  reasonCode: string | null;
  orphaned: boolean;
  requiresExecutionReview: boolean;
}

export interface AtomicRequestDetail {
  request: {
    requestId: string;
    state: string;
    executionId: string;
    targetDefinitionId: string;
    createdAt: string;
  };
  members: AtomicRequestMember[];
  memberCounts: Record<string, number>;
  unfinishedMembers: number;
  isSettled: boolean;
  hasFailure: boolean;
}

export interface ApprovedInputEvidence {
  dataset: string;
  partition: Record<string, string | number | boolean>;
  dataVersion: string;
}

export interface WorkReadinessProof {
  atomicWorkId: string;
  atomicAttemptId: string;
  executionId: string;
  businessDate: string;
  dataVersion: string;
  output: {
    dataset: string;
    partition: Record<string, string | number | boolean>;
    rowCount: number | null;
    schemaHash: string | null;
  };
  approvedInputs: ApprovedInputEvidence[];
  upstreamWorks: Array<{ atomicWorkId: string; atomicAttemptId: string }>;
}

export interface AtomicAttempt {
  atomicAttemptId: string;
  atomicWorkId: string;
  executionId: string;
  parentExecutionId: string;
  jobRunId: string;
  jobType: string;
  workType: string | null;
  workKey: string | null;
  businessDate: string | null;
  status: string;
  observation: string;
  ownerCount: number;
  activeOwnerCount: number;
  upstreamCount: number;
  upstreamFailed: number;
  upstreamUnfinished: number;
  publishedMessages: number;
  pendingMessages: number;
  blockedMessages: number;
  liveLeases: number;
  expiredTokenMessages: number;
  retryDelayedMessages: number;
  firstPublishedAt: string | null;
  publishAgeSeconds: number | null;
  publishedUnfinishedOver30m: boolean;
  identityMatches: boolean;
  statusIdentityMatches: boolean | null;
}

export interface AtomicSnapshot {
  version: number;
  capturedAt: string;
  observedAt: string;
  since: string;
  until: string;
  validWindow: boolean;
  hasMoreRequests: boolean;
  nextCursor: string | null;
  attemptsTruncated: boolean;
  edgesTruncated: boolean;
  requests: Array<{
    graphRequestId: string;
    state: string;
    createdAt: string;
    memberCount: number;
    targetCount: number;
    sampledMemberCount: number;
  }>;
  attempts: AtomicAttempt[];
  definitions: Array<{
    jobType: string;
    stored: number;
    scheduleEnabled: number;
    retired: number;
  }>;
}

export interface PublishedPage {
  version: number;
  observedAt: string;
  hasMore: boolean;
  nextCursor: string | null;
  rows: Array<{
    messageId: string;
    executionId: string;
    atomicAttemptId: string | null;
    atomicWorkId: string | null;
    jobType: string;
    workKey: string | null;
    status: string;
    publishedAt: string;
    publishAgeSeconds: number;
  }>;
}

export interface WorkInspection {
  atomicWorkId: string;
  attemptsTruncated: boolean;
  ownersTruncated: boolean;
  upstreamsTruncated: boolean;
  attempts: Array<{
    atomicAttemptId: string;
    executionId: string;
    status: string;
    identityMatches: boolean;
    statusAtomicWorkId: string | null;
    approvedInputs: Array<{
      dataset: string;
      partition: Record<string, string>;
      dataVersion: string;
    }> | null;
    traceContextPresent: boolean;
    traceContextType: string | null;
  }>;
  owners: Array<{
    atomicAttemptId: string;
    graphRequestId: string;
    state: string;
    isTarget: boolean;
  }>;
  upstreams: Array<{
    atomicAttemptId: string;
    upstreamAtomicAttemptId: string;
    upstreamAtomicWorkId: string;
    upstreamExecutionId: string;
    status: string;
  }>;
}
export interface BackfillSector {
  code: string;
  name: string;
}

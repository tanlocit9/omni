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

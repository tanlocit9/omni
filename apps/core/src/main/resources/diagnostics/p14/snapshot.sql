-- PostgreSQL 16 / V18. SELECT only; psql quoted-value bindings, never interpolate SQL.
-- after_id='' or last request UUID; row_limit=1..100; since/until/observed_at UTC.
-- max 100 requests, 1000 attempts, 2000 internal edges; explicit truncation flags.
-- Request keyset is UUID order, NOT creation order. Totals are page/sample only.
WITH args AS MATERIALIZED (
  SELECT nullif(:'after_id','')::uuid AS after_id,
    greatest(1,least(100,:'row_limit'::integer)) AS row_limit,
    :'since'::timestamptz AS since, :'until'::timestamptz AS until,
    :'observed_at'::timestamptz AS observed_at
), request_probe AS MATERIALIZED (
  SELECT r.* FROM atomic_work_requests r, args p
  WHERE r.created_at>=p.since AND r.created_at<p.until AND p.since<p.until AND p.until<=p.observed_at
    AND (p.after_id IS NULL OR r.id>p.after_id)
  ORDER BY r.id LIMIT (SELECT row_limit+1 FROM args)
), requests AS MATERIALIZED (
  SELECT * FROM request_probe ORDER BY id LIMIT (SELECT row_limit FROM args)
), members AS MATERIALIZED (
  SELECT m.* FROM atomic_work_request_members m JOIN requests r ON r.id=m.request_id
), attempt_probe AS MATERIALIZED (
  SELECT a.* FROM atomic_work_attempts a
  WHERE EXISTS (SELECT 1 FROM members m WHERE m.attempt_id=a.id)
  ORDER BY a.id LIMIT 1001
), attempts AS MATERIALIZED (
  SELECT * FROM attempt_probe ORDER BY id LIMIT 1000
), edge_probe AS MATERIALIZED (
  SELECT d.* FROM atomic_work_dependencies d JOIN attempts a ON a.id=d.attempt_id
  ORDER BY d.attempt_id,d.upstream_attempt_id LIMIT 2001
), request_rows AS MATERIALIZED (
  SELECT r.id, jsonb_build_object(
    'graphRequestId',r.id,'state',r.state,'createdAt',r.created_at,
    'executionId',r.execution_id,'targetDefinitionId',r.target_definition_id,
    'memberCount',(SELECT count(*) FROM members m WHERE m.request_id=r.id),
    'targetCount',(SELECT count(*) FROM members m WHERE m.request_id=r.id AND m.is_target),
    'sampledMemberCount',(SELECT count(*) FROM members m JOIN attempts a ON a.id=m.attempt_id WHERE m.request_id=r.id)
  ) AS body FROM requests r
), attempt_rows AS MATERIALIZED (
  SELECT a.id, jsonb_build_object(
    'atomicAttemptId',a.id,'atomicWorkId',a.work_id,'jobRunId',a.job_run_id,
    'executionId',a.execution_id,'parentExecutionId',a.parent_execution_id,
    'jobType',j.job_type,'workType',e.meta_json->>'workType',
    'workKey',e.meta_json->>'workKey','businessDate',e.meta_json->>'workBusinessDate',
    'createdAt',a.created_at,'status',e.status,'parentStatus',parent.status,
    'runState',run.state,'sealed',a.sealed,
    'identityMatches',coalesce(e.meta_json->>'workIdentity'=a.work_id,false),
    'statusIdentityMatches',CASE WHEN jsonb_exists(e.meta_json, 'atomicWorkId')
      THEN e.meta_json->>'atomicWorkId'=a.work_id ELSE NULL END,
    'ownerCount',owners.total,'activeOwnerCount',owners.active,
    'requestIdsOnPage',(SELECT coalesce(jsonb_agg(m.request_id ORDER BY m.request_id),'[]'::jsonb)
      FROM members m WHERE m.attempt_id=a.id),
    'upstreamCount',dep.total,'upstreamFailed',dep.failed,'upstreamUnfinished',dep.unfinished,
    'messages',outbox.total,'publishedMessages',outbox.published,'pendingMessages',outbox.pending,
    'blockedMessages',outbox.blocked,'liveLeases',outbox.live_leases,
    'expiredTokenMessages',outbox.expired_tokens,'retryDelayedMessages',outbox.retry_delayed,
    'firstPublishedAt',outbox.first_published,
    'publishAgeSeconds',extract(epoch FROM p.observed_at-outbox.first_published),
    'publishedUnfinishedOver30m',coalesce(e.status IN ('PENDING','RUNNING')
      AND outbox.first_published < p.observed_at-interval '30 minutes',false),
    'observation',CASE
      WHEN NOT a.sealed OR e.id IS NULL OR e.meta_json->>'workIdentity' IS DISTINCT FROM a.work_id THEN 'UNKNOWN_MEMBERSHIP'
      WHEN e.status IN ('FAILED','ERROR','BLOCKED') THEN 'FAILED_OR_BLOCKED'
      WHEN outbox.blocked>0 THEN 'OUTBOX_BLOCKED'
      WHEN e.status='SUCCESS' THEN 'EXECUTION_SUCCESS'
      WHEN e.status IS NULL OR e.status NOT IN ('PENDING','RUNNING') THEN 'UNKNOWN_STATUS'
      WHEN outbox.published>0 THEN 'PUBLISHED_BUT_UNFINISHED'
      WHEN owners.active=0 THEN 'NO_ACTIVE_REQUEST_OWNER'
      WHEN dep.failed>0 THEN 'UPSTREAM_FAILED'
      WHEN dep.unfinished>0 THEN 'UPSTREAM_UNFINISHED'
      WHEN outbox.live_leases>0 THEN 'LIVE_LEASE'
      WHEN outbox.retry_delayed>0 THEN 'RETRY_DELAYED'
      WHEN outbox.pending>0 THEN 'PENDING_GUARDS_NOT_EVALUATED'
      ELSE 'NO_OUTBOX_EVIDENCE' END,
    'workerProcessing',NULL,'datasetReady',NULL
  ) AS body
  FROM attempts a CROSS JOIN args p
  LEFT JOIN job_execution_histories e ON e.id=a.execution_id
  LEFT JOIN job_execution_histories parent ON parent.id=a.parent_execution_id
  LEFT JOIN job_runs run ON run.id=a.job_run_id
  LEFT JOIN job_definitions j ON j.id=e.job_id
  CROSS JOIN LATERAL (
    SELECT count(*) AS total,count(*) FILTER (WHERE r.state='PREPARED') AS active
    FROM atomic_work_request_members m JOIN atomic_work_requests r ON r.id=m.request_id
    WHERE m.attempt_id=a.id
  ) owners
  CROSS JOIN LATERAL (
    SELECT count(*) AS total,
      count(*) FILTER (WHERE h.status IN ('FAILED','ERROR','BLOCKED')) AS failed,
      count(*) FILTER (WHERE h.status IS DISTINCT FROM 'SUCCESS') AS unfinished
    FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id
    LEFT JOIN job_execution_histories h ON h.id=u.execution_id WHERE d.attempt_id=a.id
  ) dep
  CROSS JOIN LATERAL (
    SELECT count(*) AS total,
      count(*) FILTER (WHERE o.status='PUBLISHED') AS published,
      count(*) FILTER (WHERE o.status='PENDING') AS pending,
      count(*) FILTER (WHERE o.status='BLOCKED') AS blocked,
      count(*) FILTER (WHERE o.status='PENDING' AND o.claim_until>p.observed_at) AS live_leases,
      count(*) FILTER (WHERE o.status='PENDING' AND o.claim_token IS NOT NULL AND o.claim_until<=p.observed_at) AS expired_tokens,
      count(*) FILTER (WHERE o.status='PENDING' AND o.available_at>p.observed_at
        AND (o.claim_until IS NULL OR o.claim_until<=p.observed_at)) AS retry_delayed,
      min(o.published_at) FILTER (WHERE o.status='PUBLISHED') AS first_published
    FROM scheduler_outbox_messages o WHERE o.execution_id=a.execution_id
  ) outbox
), definitions AS MATERIALIZED (
  SELECT j.job_type,count(*) AS stored,
    count(*) FILTER (WHERE j.is_active AND j.schedule_enabled AND j.canonical_definition_id IS NULL) AS schedule_enabled,
    count(*) FILTER (WHERE j.canonical_definition_id IS NOT NULL) AS retired
  FROM job_definitions j GROUP BY j.job_type
)
SELECT jsonb_build_object(
  'version',2,'dataSource','DATABASE_EXPORT','capturedAt',statement_timestamp(),
  'observedAt',(SELECT observed_at FROM args),'scope','REQUEST_PAGE_WITH_ATTEMPT_SAMPLE',
  'since',(SELECT since FROM args),'until',(SELECT until FROM args),
  'validWindow',(SELECT since<until AND until<=observed_at FROM args),
  'hasMoreRequests',(SELECT count(*)>(SELECT row_limit FROM args) FROM request_probe),
  'nextCursor',(SELECT id FROM requests ORDER BY id DESC LIMIT 1),
  'attemptsTruncated',(SELECT count(*)>1000 FROM attempt_probe),
  'edgesTruncated',(SELECT count(*)>2000 FROM edge_probe),
  'requests',(SELECT coalesce(jsonb_agg(body ORDER BY id),'[]'::jsonb) FROM request_rows),
  'attempts',(SELECT coalesce(jsonb_agg(body ORDER BY id),'[]'::jsonb) FROM attempt_rows),
  'edges',(SELECT coalesce(jsonb_agg(jsonb_build_object('atomicAttemptId',attempt_id,
    'upstreamAtomicAttemptId',upstream_attempt_id) ORDER BY attempt_id,upstream_attempt_id),'[]'::jsonb)
    FROM (SELECT * FROM edge_probe ORDER BY attempt_id,upstream_attempt_id LIMIT 2000) bounded),
  'definitions',(SELECT coalesce(jsonb_agg(jsonb_build_object('jobType',job_type,
    'stored',stored,'scheduleEnabled',schedule_enabled,'retired',retired) ORDER BY job_type),'[]'::jsonb) FROM definitions),
  'capabilities',jsonb_build_object('workerProcessing','UNKNOWN_NO_WORKER_TIMING',
    'datasetReady','UNKNOWN_MINIO_AUTHORITY_NOT_QUERIED','watchdog','PLANNED_I10',
    'metadataRegistration','PLANNED_I10','queryTrace','RESERVED_NOT_IMPLEMENTED'),
  'warnings',jsonb_build_array('Counts describe sampled distinct attempts, not global backlog or work-per-request sum.',
    'Published >30m is diagnostic only; no timeout mutation or proof of a dead worker.',
    'RUNNING/started_at can mean preparation; no worker heartbeat evidence.',
    'Definition scheduleEnabled counts are not GraphScheduledEntries terminal selection.',
    'Do not sum pages: shared attempts may occur on several request pages.')
) AS diagnostic;

-- SELECT only / V18. atomic_work_id='work:<64 lowercase hex>', observed_at UTC.
-- Up to 100 attempts, 400 owners, 1000 upstream links. No payloads/actor/raw errors.
WITH args AS (SELECT :'atomic_work_id'::text AS work_id, :'observed_at'::timestamptz AS observed_at),
probe AS MATERIALIZED (
  SELECT a.* FROM atomic_work_attempts a,args p WHERE a.work_id=p.work_id
    AND p.work_id ~ '^work:[0-9a-f]{64}$' ORDER BY a.created_at DESC,a.id DESC LIMIT 101
), attempts AS MATERIALIZED (SELECT * FROM probe ORDER BY created_at DESC,id DESC LIMIT 100),
owner_probe AS MATERIALIZED (
  SELECT m.attempt_id,m.request_id,m.is_target,r.state,r.execution_id
  FROM atomic_work_request_members m JOIN attempts a ON a.id=m.attempt_id
  JOIN atomic_work_requests r ON r.id=m.request_id
  ORDER BY m.attempt_id,m.request_id LIMIT 401
), edge_probe AS MATERIALIZED (
  SELECT d.attempt_id,d.upstream_attempt_id,u.work_id,u.execution_id,e.status
  FROM atomic_work_dependencies d JOIN attempts a ON a.id=d.attempt_id
  JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id
  LEFT JOIN job_execution_histories e ON e.id=u.execution_id
  ORDER BY d.attempt_id,d.upstream_attempt_id LIMIT 1001
)
SELECT jsonb_build_object('version',2,'observedAt',(SELECT observed_at FROM args),
  'atomicWorkId',(SELECT work_id FROM args),
  'validIdentity',(SELECT work_id ~ '^work:[0-9a-f]{64}$' FROM args),
  'attemptsTruncated',(SELECT count(*)>100 FROM probe),
  'ownersTruncated',(SELECT count(*)>400 FROM owner_probe),
  'upstreamsTruncated',(SELECT count(*)>1000 FROM edge_probe),
  'attempts',(SELECT coalesce(jsonb_agg(jsonb_build_object('atomicAttemptId',a.id,
    'executionId',a.execution_id,'parentExecutionId',a.parent_execution_id,'jobRunId',a.job_run_id,
    'createdAt',a.created_at,'status',e.status,'workIdentity',e.meta_json->>'workIdentity',
    'statusAtomicWorkId',e.meta_json->>'atomicWorkId',
    'identityMatches',coalesce(e.meta_json->>'workIdentity'=a.work_id,false),
    'resultReason',e.meta_json->>'resultReason',
    'approvedInputs',CASE WHEN jsonb_typeof(e.meta_json->'approvedInputs')='array'
      THEN e.meta_json->'approvedInputs' ELSE NULL END,
    'traceContextPresent',jsonb_exists(e.meta_json, 'traceContext'),
    'traceContextType',jsonb_typeof(e.meta_json->'traceContext')) ORDER BY a.created_at DESC,a.id DESC),'[]'::jsonb)
    FROM attempts a LEFT JOIN job_execution_histories e ON e.id=a.execution_id),
  'owners',(SELECT coalesce(jsonb_agg(jsonb_build_object('atomicAttemptId',attempt_id,'graphRequestId',request_id,
      'isTarget',is_target,'state',state,'executionId',execution_id) ORDER BY attempt_id,request_id),'[]'::jsonb)
    FROM (SELECT * FROM owner_probe ORDER BY attempt_id,request_id LIMIT 400) x),
  'upstreams',(SELECT coalesce(jsonb_agg(jsonb_build_object('atomicAttemptId',attempt_id,
      'upstreamAtomicAttemptId',upstream_attempt_id,'upstreamAtomicWorkId',work_id,
      'upstreamExecutionId',execution_id,'status',status) ORDER BY attempt_id,upstream_attempt_id),'[]'::jsonb)
    FROM (SELECT * FROM edge_probe ORDER BY attempt_id,upstream_attempt_id LIMIT 1000) x),
  'queryProvenance','RESERVED_I10_NO_QUERY_TRACE_STORE',
  'note','Trace presence/type is not proof of propagation. Only one upstream hop is returned; inspect another work explicitly.'
) AS diagnostic;

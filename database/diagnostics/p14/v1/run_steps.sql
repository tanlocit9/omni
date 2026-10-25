-- Version 1. Bounded explicit run IDs, no discovery or admission side effects.
-- psql parameters: run_ids (JSON array of 1..32 UUID strings), observed_at (UTC).
-- A window exceeding 1024 steps is reported incomplete, never partially ready.
-- The additive readModel v1 is the P14-I6 handoff; observedAt is not capture freshness.
WITH RECURSIVE args AS (
    SELECT :'run_ids'::jsonb AS ids, :'observed_at'::timestamptz AS observed_at
), requested AS MATERIALIZED (
    SELECT value::uuid AS id FROM args,
      jsonb_array_elements_text(CASE WHEN jsonb_typeof(ids)='array' THEN ids ELSE '[]'::jsonb END)
    LIMIT 33
), headers AS MATERIALIZED (
    SELECT r.* FROM job_runs r JOIN requested q ON q.id=r.id
), limits AS (
    SELECT jsonb_typeof(ids)='array' AND (SELECT count(*) FROM requested) BETWEEN 1 AND 32
       AND (SELECT count(*) FROM requested)=(SELECT count(DISTINCT id) FROM requested)
       AND (SELECT count(*) FROM requested)=(SELECT count(*) FROM headers)
       AND coalesce((SELECT sum(step_count) FROM headers),0)<=1024 AS complete
    FROM args
), steps AS MATERIALIZED (
    SELECT s.* FROM job_run_steps s JOIN headers r ON r.id=s.job_run_id
    WHERE (SELECT complete FROM limits) ORDER BY s.job_run_id,s.step_index LIMIT 1025
), progress AS MATERIALIZED (
    SELECT s.*, r.state AS run_state, r.step_count, p.closed_at IS NOT NULL AS closed,
      p.child_count, p.message_count,
      e.roots,e.executions,e.successful,e.failed,e.unknown_executions,e.identities_match,
      o.messages,o.eligible,o.leased,o.retry_delayed,o.published,o.blocked
    FROM steps s JOIN headers r ON r.id=s.job_run_id
    LEFT JOIN job_run_step_preparations p ON p.job_run_id=s.job_run_id AND p.step_index=s.step_index
    CROSS JOIN LATERAL (
      SELECT count(*) roots_and_children,
        count(*) FILTER (WHERE h.parent_log_id IS NULL) roots,
        count(*) executions,
        count(*) FILTER (WHERE h.status='SUCCESS') successful,
        count(*) FILTER (WHERE h.status IN ('FAILED','ERROR','BLOCKED')) failed,
        count(*) FILTER (WHERE h.status IS NULL OR h.status NOT IN ('PENDING','RUNNING','SUCCESS','FAILED','ERROR','BLOCKED')) unknown_executions,
        coalesce(bool_and(coalesce(h.job_id=s.job_definition_id AND h.meta_json->>'runPreparationVersion'='1'
          AND h.meta_json->>'businessDate'=s.business_date::text
          AND (h.id=p.parent_execution_id AND h.parent_log_id IS NULL OR h.parent_log_id=p.parent_execution_id),false)),false) identities_match
      FROM job_execution_histories h WHERE h.job_run_id=s.job_run_id AND h.job_run_step_index=s.step_index
    ) e
    CROSS JOIN LATERAL (
      SELECT count(*) messages,
        count(*) FILTER (WHERE m.status='PENDING' AND m.available_at <= (SELECT observed_at FROM args)
          AND (m.claim_until IS NULL OR m.claim_until <= (SELECT observed_at FROM args))) eligible,
        count(*) FILTER (WHERE m.status='PENDING' AND m.claim_until > (SELECT observed_at FROM args)) leased,
        count(*) FILTER (WHERE m.status='PENDING' AND m.available_at > (SELECT observed_at FROM args)
          AND (m.claim_until IS NULL OR m.claim_until <= (SELECT observed_at FROM args))) retry_delayed,
        count(*) FILTER (WHERE m.status='PUBLISHED') published,
        count(*) FILTER (WHERE m.status='BLOCKED') blocked
      FROM job_execution_histories h JOIN scheduler_outbox_messages m ON m.execution_id=h.id
      WHERE h.job_run_id=s.job_run_id AND h.job_run_step_index=s.step_index
    ) o
), states AS MATERIALIZED (
    SELECT p.*, CASE
      WHEN run_state NOT IN ('PREPARED','RUNNING') THEN 'TERMINAL_OR_UNSUPPORTED_RUN'
      WHEN NOT closed OR roots<>1 OR executions<>child_count+1 OR messages<>message_count
        OR NOT identities_match OR unknown_executions>0 THEN 'UNKNOWN_MEMBERSHIP'
      WHEN failed>0 OR blocked>0 THEN 'FAILED_OR_BLOCKED'
      WHEN successful=executions THEN 'SUCCESS'
      ELSE 'UNFINISHED' END AS progress
    FROM progress p
), edges AS MATERIALIZED (
    SELECT e.* FROM job_run_step_predecessors e JOIN steps s
      ON e.job_run_id=s.job_run_id AND e.step_index=s.step_index LIMIT 8193
), ancestors(job_run_id,step_index,ancestor_index) AS (
    SELECT job_run_id,step_index,predecessor_index FROM edges
    UNION
    SELECT a.job_run_id,a.step_index,e.predecessor_index FROM ancestors a JOIN edges e
      ON e.job_run_id=a.job_run_id AND e.step_index=a.ancestor_index
), rows AS MATERIALIZED (
    SELECT s.*, (SELECT count(*) FROM ancestors a WHERE a.job_run_id=s.job_run_id AND a.step_index=s.step_index) ancestor_count,
      EXISTS (SELECT 1 FROM ancestors a LEFT JOIN states parent
        ON parent.job_run_id=a.job_run_id AND parent.step_index=a.ancestor_index
        WHERE a.job_run_id=s.job_run_id AND a.step_index=s.step_index
          AND parent.progress IS DISTINCT FROM 'SUCCESS') AS predecessor_wait,
      s.published>0 AND s.progress='UNFINISHED' AS published_but_unfinished
    FROM states s
), window_state AS MATERIALIZED (
    SELECT (SELECT complete FROM limits)
      AND (SELECT count(*) FROM steps)=coalesce((SELECT sum(step_count) FROM headers),0)
      AND (SELECT count(*) FROM edges)<=8192 AS complete,
      CASE WHEN NOT (SELECT complete FROM limits) THEN 'INVALID_MISSING_OR_OVERSIZED_WINDOW'
        WHEN (SELECT count(*) FROM steps)<>coalesce((SELECT sum(step_count) FROM headers),0) THEN 'MISSING_STEPS'
        WHEN (SELECT count(*) FROM edges)>8192 THEN 'EDGE_BOUND' ELSE 'OBSERVED' END AS reason
), operator_steps AS MATERIALIZED (
    SELECT s.*,
      (SELECT coalesce(jsonb_agg(e.predecessor_index ORDER BY e.predecessor_index),'[]'::jsonb)
        FROM edges e WHERE e.job_run_id=s.job_run_id AND e.step_index=s.step_index) AS predecessor_step_indices,
      CASE WHEN progress IN ('UNKNOWN_MEMBERSHIP','TERMINAL_OR_UNSUPPORTED_RUN','FAILED_OR_BLOCKED') THEN progress
        WHEN predecessor_wait THEN 'PREDECESSOR_WAIT'
        WHEN progress='SUCCESS' THEN 'EXECUTION_SUCCESS'
        WHEN leased>0 THEN 'LIVE_LEASE'
        WHEN retry_delayed>0 THEN 'RETRY_DELAYED'
        WHEN published_but_unfinished THEN 'PUBLISHED_BUT_UNFINISHED'
        WHEN eligible>0 THEN 'ELIGIBLE_CANDIDATES'
        ELSE 'EXECUTION_UNFINISHED' END AS observation_reason,
      NULL::boolean AS worker_processing, NULL::boolean AS dataset_ready
    FROM rows s
), operator_runs AS MATERIALIZED (
    SELECT job_run_id, min(run_state) AS run_state, max(step_count) AS expected_steps,
      count(*) AS observed_steps, count(*) FILTER (WHERE progress='SUCCESS') AS completed_steps,
      count(*) FILTER (WHERE progress='FAILED_OR_BLOCKED') AS failed_steps,
      count(*) FILTER (WHERE progress IN ('UNKNOWN_MEMBERSHIP','TERMINAL_OR_UNSUPPORTED_RUN')) AS unknown_steps,
      sum(eligible) AS eligible, sum(leased) AS leased, sum(retry_delayed) AS retry_delayed,
      sum(published) AS published, sum(blocked) AS blocked,
      jsonb_agg(to_jsonb(s) ORDER BY step_index) AS steps
    FROM operator_steps s GROUP BY job_run_id
), capture AS MATERIALIZED (
    -- Force row materialization before the finish clock; this excludes client transport time.
    SELECT statement_timestamp() AS started_at, clock_timestamp() AS finished_at
    FROM (SELECT count(*) AS row_count FROM operator_steps) collected WHERE row_count>=0
)
SELECT jsonb_build_object(
  'version',1,'observedAt',(SELECT observed_at FROM args),
  'complete',(SELECT complete FROM window_state),'reason',(SELECT reason FROM window_state),
  'rows',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY job_run_id,step_index),'[]'::jsonb) FROM rows r),
  'readModel',jsonb_build_object(
    'version',1,'complete',(SELECT complete FROM window_state),'reason',(SELECT reason FROM window_state),
    'captureStartedAt',(SELECT started_at FROM capture),'captureFinishedAt',(SELECT finished_at FROM capture),
    'captureDurationMs',(SELECT extract(epoch FROM finished_at-started_at)*1000 FROM capture),
    'runs',CASE WHEN (SELECT complete FROM window_state) THEN
      (SELECT coalesce(jsonb_agg(to_jsonb(r) || jsonb_build_object(
        'path_fingerprint',h.path_fingerprint,'path_contract_version',h.path_contract_version,
        'target_definition_id',h.target_definition_id,'counts_trusted',unknown_steps=0,
        'progress',CASE WHEN unknown_steps>0 THEN 'UNKNOWN' WHEN failed_steps>0 THEN 'FAILED_OR_BLOCKED'
          WHEN completed_steps=expected_steps THEN 'SUCCESS' ELSE 'UNFINISHED' END,
        'completion_fraction',CASE WHEN unknown_steps=0 THEN completed_steps::numeric/nullif(expected_steps,0) END
      ) ORDER BY job_run_id),'[]'::jsonb) FROM operator_runs r JOIN headers h ON h.id=r.job_run_id) ELSE '[]'::jsonb END,
    'workerProcessingReason','NO_AUTHORITATIVE_WORKER_TIMING',
    'datasetReadyReason','DEPENDENCY_REGISTRY_AUTHORITY',
    'throughput',jsonb_build_object('publishedPerSecond',NULL,'completedPerSecond',NULL,
      'unit','events/second','reason','NO_AUTHORITATIVE_COUNTER_WINDOW')
  )
) AS diagnostic;

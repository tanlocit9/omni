-- SELECT only / V18. Covers legacy + I8 + I9; independent of request creation page.
-- Parameters: observed_at UTC, published_before UTC, after_id empty/message UUID,
-- row_limit 1..400. Older publication has no lower time bound; UUID pagination.
WITH args AS (
  SELECT :'observed_at'::timestamptz AS observed_at,
    :'published_before'::timestamptz AS published_before,
    nullif(:'after_id','')::uuid AS after_id,
    greatest(1,least(400,:'row_limit'::integer)) AS row_limit
), probe AS MATERIALIZED (
  SELECT o.id,o.execution_id,o.topic,o.published_at,e.status,e.updated_at,
    e.job_run_id,e.meta_json,j.job_type,a.id AS attempt_id,a.work_id
  FROM scheduler_outbox_messages o JOIN job_execution_histories e ON e.id=o.execution_id
  JOIN job_definitions j ON j.id=e.job_id
  LEFT JOIN atomic_work_attempts a ON a.execution_id=e.id CROSS JOIN args p
  WHERE o.status='PUBLISHED' AND e.status IN ('PENDING','RUNNING')
    AND o.published_at<p.published_before AND o.published_at<=p.observed_at
    AND (p.after_id IS NULL OR o.id>p.after_id)
  ORDER BY o.id LIMIT (SELECT row_limit+1 FROM args)
), page AS MATERIALIZED (SELECT * FROM probe ORDER BY id LIMIT (SELECT row_limit FROM args))
SELECT jsonb_build_object('version',2,'scope','PUBLISHED_MESSAGE_PAGE',
  'observedAt',(SELECT observed_at FROM args),
  'hasMore',(SELECT count(*)>(SELECT row_limit FROM args) FROM probe),
  'nextCursor',(SELECT id FROM page ORDER BY id DESC LIMIT 1),
  'rows',(SELECT coalesce(jsonb_agg(jsonb_build_object('messageId',id,
    'executionId',execution_id,'jobRunId',job_run_id,'atomicAttemptId',attempt_id,
    'atomicWorkId',coalesce(work_id,meta_json->>'workIdentity',meta_json->>'atomicWorkId'),
    'jobType',job_type,'workKey',meta_json->>'workKey','status',status,'topic',topic,
    'publishedAt',published_at,'lastDbUpdateAt',updated_at,
    'publishAgeSeconds',extract(epoch FROM (SELECT observed_at FROM args)-published_at)) ORDER BY id),'[]'::jsonb) FROM page),
  'interpretation','Publication acknowledged; no terminal execution recorded. Not proof of worker start/death; no DB mutation.'
) AS diagnostic;

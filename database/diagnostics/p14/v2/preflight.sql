-- SELECT only. Run before the V17/V18 pack. Missing prerequisites are NOT zero activity.
SELECT jsonb_build_object(
  'version', 2,
  'capturedAt', statement_timestamp(),
  'postgresVersion', current_setting('server_version'),
  'tables', (SELECT jsonb_object_agg(name, to_regclass(name) IS NOT NULL)
    FROM unnest(ARRAY['job_runs','job_run_steps','job_run_step_preparations',
      'job_execution_histories','scheduler_outbox_messages','atomic_work_requests',
      'atomic_work_attempts','atomic_work_request_members','atomic_work_dependencies',
      'job_definition_consolidations']) AS names(name)),
  'definitionColumns', (SELECT jsonb_object_agg(name, EXISTS (
      SELECT 1 FROM pg_attribute a WHERE a.attrelid=to_regclass('job_definitions')
        AND a.attname=name AND NOT a.attisdropped))
    FROM unnest(ARRAY['schedule_enabled','canonical_definition_id']) AS field_names(name)),
  'note', 'Require all tables plus V18 schedule_enabled/canonical_definition_id before snapshot.sql. Does not prove application flags or I10 readiness.'
) AS diagnostic;

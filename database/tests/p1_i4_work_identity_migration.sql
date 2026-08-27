\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS dblink;

\echo 'P1-I4 empty-history schema cutover and idempotency fixture'
CREATE SCHEMA p1_i4_empty;
SET search_path TO p1_i4_empty, public;
\ir ../migrations/V1__create_job_definitions_table.sql
\ir ../migrations/V2__create_job_execution_histories_table.sql
\ir ../migrations/V6__create_scheduler_outbox.sql

BEGIN;
\ir ../migrations/V9__backfill_execution_work_identity.sql
COMMIT;
BEGIN;
\ir ../migrations/V9__backfill_execution_work_identity.sql
COMMIT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'p1_i4_empty'
          AND indexname = 'idx_job_execution_history_work_identity'
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'p1_i4_empty'
          AND indexname = 'idx_job_execution_history_symbol_offset'
    ) THEN
        RAISE EXCEPTION 'Expected canonical work identity indexes were not created';
    END IF;

    RAISE NOTICE 'P1-I4 schema-only cutover evidence: zero execution history rows';
END $$;

RESET search_path;

\echo 'P1-I4 expected rejection fixtures'
CREATE OR REPLACE FUNCTION public.p1_i4_expect_schema_cutover_failure(
    schema_name text,
    insert_history boolean,
    insert_pending_outbox boolean,
    expected_message text
) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    connection_name text := 'p1_i4_' || schema_name;
    command text;
    error_message text;
BEGIN
    PERFORM dblink_connect(connection_name, 'dbname=' || current_database());
    command := format(
        $sql$
        CREATE SCHEMA %1$I;
        SET search_path TO %1$I, public;
        CREATE TABLE job_definitions (
            id uuid PRIMARY KEY,
            job_type varchar(50) NOT NULL
        );
        CREATE TABLE job_execution_histories (
            id uuid PRIMARY KEY,
            job_id uuid NOT NULL REFERENCES job_definitions(id),
            parent_log_id uuid,
            status varchar(255) NOT NULL,
            finished_at timestamptz,
            new_offset varchar(255),
            meta_json jsonb
        );
        CREATE INDEX idx_job_execution_history_symbol
            ON job_execution_histories ((meta_json ->> 'symbolKey'));
        CREATE INDEX idx_job_execution_history_symbol_offset
            ON job_execution_histories (job_id, (meta_json ->> 'symbolKey'), finished_at DESC);
        CREATE TABLE scheduler_outbox_messages (status varchar(32) NOT NULL);
        %2$s
        %3$s
        $sql$,
        schema_name,
        CASE WHEN insert_history THEN
            'INSERT INTO job_definitions VALUES (''10000000-0000-0000-0000-000000000001'', ''SYNC_STOCK_PRICE'');
             INSERT INTO job_execution_histories VALUES (
                 ''10000000-0000-0000-0000-000000000002'',
                 ''10000000-0000-0000-0000-000000000001'',
                 NULL,
                 ''SUCCESS'',
                 NULL,
                 NULL,
                 ''{}''::jsonb
             );'
        ELSE '' END,
        CASE WHEN insert_pending_outbox THEN
            'INSERT INTO scheduler_outbox_messages VALUES (''PENDING'');'
        ELSE '' END
    );
    PERFORM dblink_exec(connection_name, command);

    BEGIN
        PERFORM dblink_exec(
            connection_name,
            format('SET search_path TO %I, public;', schema_name)
            || pg_read_file('/workspace/database/migrations/V9__backfill_execution_work_identity.sql')
        );
        RAISE EXCEPTION 'Expected migration failure containing: %', expected_message;
    EXCEPTION WHEN OTHERS THEN
        error_message := SQLERRM;
        IF position(expected_message IN error_message) = 0 THEN
            RAISE EXCEPTION 'Unexpected migration error: %', error_message;
        END IF;
    END;

    PERFORM dblink_disconnect(connection_name);
END $$;

SELECT public.p1_i4_expect_schema_cutover_failure(
    'p1_i4_history_present', true, false,
    'P1-I4 requires job execution history to be cleared manually before cutover'
);
SELECT public.p1_i4_expect_schema_cutover_failure(
    'p1_i4_pending_outbox', false, true,
    'P1-I4 requires a drained scheduler outbox'
);

DROP FUNCTION public.p1_i4_expect_schema_cutover_failure(text, boolean, boolean, text);
\echo 'P1-I4 schema-only migration fixtures passed'

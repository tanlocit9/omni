-- I9: each atomic attempt retains a one-step V1 JobRun; requests reference attempts.
-- Existing V1--V16 paths, fingerprints, outbox identity and history are untouched.
CREATE TABLE atomic_work_slots (
    work_id TEXT PRIMARY KEY CHECK (work_id ~ '^work:[0-9a-f]{64}$')
);
CREATE TABLE atomic_work_attempts (
    id UUID PRIMARY KEY,
    work_id TEXT NOT NULL REFERENCES atomic_work_slots(work_id),
    job_run_id UUID NOT NULL UNIQUE REFERENCES job_runs(id),
    parent_execution_id UUID NOT NULL UNIQUE REFERENCES job_execution_histories(id),
    execution_id UUID NOT NULL UNIQUE REFERENCES job_execution_histories(id),
    upstream_attempt_ids JSONB NOT NULL CHECK (jsonb_typeof(upstream_attempt_ids)='array'),
    reuse_policy TEXT NOT NULL DEFAULT 'IN_FLIGHT_ONLY' CHECK (reuse_policy='IN_FLIGHT_ONLY'),
    created_at TIMESTAMPTZ NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (id,work_id)
);
CREATE INDEX atomic_work_attempts_lookup ON atomic_work_attempts(work_id,created_at DESC,id);
CREATE TABLE atomic_work_dependencies (
    attempt_id UUID NOT NULL REFERENCES atomic_work_attempts(id),
    upstream_attempt_id UUID NOT NULL REFERENCES atomic_work_attempts(id),
    PRIMARY KEY(attempt_id,upstream_attempt_id),
    CHECK(attempt_id<>upstream_attempt_id)
);
CREATE INDEX atomic_work_dependencies_upstream ON atomic_work_dependencies(upstream_attempt_id);
CREATE TABLE atomic_work_requests (
    id UUID PRIMARY KEY,
    mode TEXT NOT NULL CHECK(mode IN ('SCHEDULED','MANUAL')),
    actor VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    target_definition_id UUID NOT NULL REFERENCES job_definitions(id),
    execution_id UUID NOT NULL UNIQUE REFERENCES job_execution_histories(id),
    request_json JSONB NOT NULL CHECK(jsonb_typeof(request_json)='object'),
    graph_json JSONB NOT NULL CHECK(jsonb_typeof(graph_json)='object'),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL DEFAULT 'PREPARING'
        CHECK(state IN ('PREPARING','PREPARED','SUCCESS','FAILED','CANCELLED')),
    UNIQUE(mode,actor,idempotency_key)
);
CREATE TABLE atomic_work_request_members (
    request_id UUID NOT NULL REFERENCES atomic_work_requests(id),
    work_id TEXT NOT NULL,
    attempt_id UUID NOT NULL,
    is_target BOOLEAN NOT NULL,
    PRIMARY KEY(request_id,work_id),
    FOREIGN KEY(attempt_id,work_id) REFERENCES atomic_work_attempts(id,work_id)
);
CREATE INDEX atomic_work_members_attempt ON atomic_work_request_members(attempt_id,request_id);
CREATE INDEX atomic_work_requests_pending ON atomic_work_requests(created_at,id) WHERE state='PREPARED';

CREATE FUNCTION guard_atomic_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Atomic attempt history is immutable'; END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.sealed OR NOT NEW.sealed OR (to_jsonb(NEW)-'sealed') IS DISTINCT FROM (to_jsonb(OLD)-'sealed') THEN
            RAISE EXCEPTION 'Atomic attempt binding is immutable';
        END IF;
        IF (SELECT count(*) FROM atomic_work_dependencies WHERE attempt_id=NEW.id)
                <> jsonb_array_length(NEW.upstream_attempt_ids) THEN
            RAISE EXCEPTION 'Incomplete atomic dependency closure';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM job_run_step_preparations p
            JOIN job_execution_histories e ON e.id=NEW.execution_id
            WHERE p.job_run_id=NEW.job_run_id AND p.parent_execution_id=NEW.parent_execution_id
              AND p.child_count=1 AND p.message_count=1 AND e.parent_log_id=NEW.parent_execution_id
              AND e.meta_json->>'workIdentity'=NEW.work_id) THEN
            RAISE EXCEPTION 'Atomic execution closure mismatch';
        END IF;
    ELSIF NEW.sealed THEN RAISE EXCEPTION 'Atomic attempt must seal after membership';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_atomic_attempt BEFORE INSERT OR UPDATE OR DELETE ON atomic_work_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_atomic_attempt();
CREATE FUNCTION guard_atomic_dependency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Atomic dependencies are immutable'; END IF;
    IF NOT EXISTS (SELECT 1 FROM atomic_work_attempts a JOIN atomic_work_attempts u
            ON u.id=NEW.upstream_attempt_id AND u.sealed
        WHERE a.id=NEW.attempt_id AND NOT a.sealed
          AND a.upstream_attempt_ids ? NEW.upstream_attempt_id::text) THEN
        RAISE EXCEPTION 'Atomic dependency outside accepted attempts';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_atomic_dependency BEFORE INSERT OR UPDATE OR DELETE ON atomic_work_dependencies
    FOR EACH ROW EXECUTE FUNCTION guard_atomic_dependency();
CREATE FUNCTION guard_atomic_request_member() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Atomic request membership is immutable'; END IF;
    PERFORM 1 FROM atomic_work_requests WHERE id=NEW.request_id AND state='PREPARING' FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM atomic_work_attempts WHERE id=NEW.attempt_id AND sealed) THEN
        RAISE EXCEPTION 'Atomic request membership is sealed or attempt incomplete';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_atomic_request_member BEFORE INSERT OR UPDATE OR DELETE ON atomic_work_request_members
    FOR EACH ROW EXECUTE FUNCTION guard_atomic_request_member();
CREATE FUNCTION guard_atomic_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Atomic request history is immutable'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PREPARING' THEN RAISE EXCEPTION 'Atomic request must start preparing'; END IF;
        IF NOT EXISTS(SELECT 1 FROM job_execution_histories e WHERE e.id=NEW.execution_id
            AND e.job_id=NEW.target_definition_id AND e.parent_log_id IS NULL AND e.job_run_id IS NULL
            AND e.meta_json->>'graphRequestId'=NEW.id::text) THEN
            RAISE EXCEPTION 'Atomic requester execution mismatch';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR OLD.state NOT IN ('PREPARING','PREPARED') THEN
            RAISE EXCEPTION 'Atomic request identity or terminal state is immutable';
        END IF;
        IF OLD.state='PREPARING' THEN
            IF NEW.state<>'PREPARED' OR
                (SELECT count(*) FROM atomic_work_request_members WHERE request_id=NEW.id)
                    <> jsonb_array_length(NEW.graph_json->'works') OR
                (SELECT count(*) FROM atomic_work_request_members WHERE request_id=NEW.id AND is_target)
                    <> jsonb_array_length(NEW.graph_json->'targets') OR EXISTS (
                    SELECT 1 FROM atomic_work_request_members m WHERE m.request_id=NEW.id AND
                        (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.graph_json->'works') w WHERE w->>'id'=m.work_id)
                        OR m.is_target IS DISTINCT FROM (NEW.graph_json->'targets' ? m.work_id))) THEN
                RAISE EXCEPTION 'Incomplete atomic request membership';
            END IF;
        ELSIF NEW.state NOT IN ('SUCCESS','FAILED','CANCELLED') THEN
            RAISE EXCEPTION 'Invalid atomic request transition';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_atomic_request BEFORE INSERT OR UPDATE OR DELETE ON atomic_work_requests
    FOR EACH ROW EXECUTE FUNCTION guard_atomic_request();

-- Safe for every old execution: no attempt means no additional barrier.
-- Completion is separate from dataset READY/version checks in the existing guard.
CREATE FUNCTION atomic_work_admissible(work_execution UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT NOT EXISTS(SELECT 1 FROM atomic_work_attempts WHERE execution_id=work_execution)
        OR EXISTS (
            SELECT 1 FROM atomic_work_attempts a WHERE a.execution_id=work_execution AND a.sealed
            AND EXISTS (SELECT 1 FROM atomic_work_request_members m JOIN atomic_work_requests r ON r.id=m.request_id
                WHERE m.attempt_id=a.id AND r.state='PREPARED')
            AND NOT EXISTS (SELECT 1 FROM atomic_work_dependencies d
                JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id
                JOIN job_execution_histories e ON e.id=u.execution_id
                WHERE d.attempt_id=a.id AND e.status<>'SUCCESS')
        );
$$;
-- No caller can commit an incomplete graph or bind a requester to an unclosed attempt.
CREATE FUNCTION require_atomic_graph_closure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='atomic_work_requests' THEN
        IF EXISTS(SELECT 1 FROM atomic_work_requests WHERE id=NEW.id AND state='PREPARING') THEN
            RAISE EXCEPTION 'Atomic request must close before commit';
        END IF;
    ELSE
        IF EXISTS(SELECT 1 FROM atomic_work_attempts WHERE id=NEW.id AND NOT sealed) THEN
            RAISE EXCEPTION 'Atomic attempt must close before commit';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER require_atomic_request_closure AFTER INSERT OR UPDATE ON atomic_work_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_atomic_graph_closure();
CREATE CONSTRAINT TRIGGER require_atomic_attempt_closure AFTER INSERT OR UPDATE ON atomic_work_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_atomic_graph_closure();

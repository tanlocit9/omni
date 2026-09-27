-- P4-I3: additive dependency-aware scheduler outbox state.
ALTER TABLE scheduler_outbox_messages
    ADD COLUMN dependency_reason VARCHAR(500);

-- BLOCKED is terminal and excluded from the existing partial PENDING dispatch index.
-- Existing PENDING/PUBLISHED rows and execution audit history remain unchanged.
CREATE INDEX idx_scheduler_outbox_waiting_visibility
    ON scheduler_outbox_messages (available_at, created_at, id)
    WHERE status = 'PENDING' AND dependency_reason IS NOT NULL;

CREATE INDEX idx_scheduler_outbox_blocked_visibility
    ON scheduler_outbox_messages (updated_at, id)
    WHERE status = 'BLOCKED';

-- Spring Modulith 2.0 JPA entities are scanned by PlatformApplication.
-- Manage both active and archive tables through Flyway, never Hibernate DDL updates.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMPTZ NOT NULL,
    listener_id VARCHAR(255) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    completion_date TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(255) NOT NULL
);

CREATE INDEX event_publication_incomplete_date_idx
    ON event_publication (publication_date) WHERE completion_date IS NULL;

CREATE TABLE event_publication_archive (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMPTZ NOT NULL,
    listener_id VARCHAR(255) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    completion_date TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(255) NOT NULL
);

CREATE INDEX event_publication_archive_completion_date_idx
    ON event_publication_archive (completion_date);

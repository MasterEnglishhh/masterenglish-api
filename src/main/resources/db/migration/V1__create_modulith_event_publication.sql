-- Spring Modulith 2.1 JPA event publication registry, completion mode UPDATE.
-- Business tables are introduced by subsequent, separately reviewed migrations.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    completion_date TIMESTAMP(6) WITH TIME ZONE,
    last_resubmission_date TIMESTAMP(6) WITH TIME ZONE,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(255)
);

CREATE INDEX idx_event_publication_completion_date
    ON event_publication (completion_date);

CREATE INDEX idx_event_publication_status_publication_date
    ON event_publication (status, publication_date);

CREATE TABLE media_verification_slots (
    slot SMALLINT PRIMARY KEY CHECK (slot BETWEEN 1 AND 2),
    worker_token UUID UNIQUE
);

INSERT INTO media_verification_slots (slot) VALUES (1), (2);

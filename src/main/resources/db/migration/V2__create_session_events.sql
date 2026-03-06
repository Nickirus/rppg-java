CREATE TABLE IF NOT EXISTS session_events (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    event_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type VARCHAR(64) NOT NULL,
    bpm DOUBLE PRECISION NULL,
    quality DOUBLE PRECISION NULL,
    payload_json JSONB NULL
);

CREATE INDEX IF NOT EXISTS idx_session_events_session_time
    ON session_events (session_id, event_time);

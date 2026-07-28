-- Esquema inicial: sesiones de onboarding y capturas de documento

CREATE TABLE onboarding_session (
    id                  UUID PRIMARY KEY,
    document_type       VARCHAR(32)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    geolocation_lat     DOUBLE PRECISION,
    geolocation_lng     DOUBLE PRECISION,
    user_agent          VARCHAR(512),
    decision_outcome    VARCHAR(16),
    decision_reasons    TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE document_capture (
    id                          UUID PRIMARY KEY,
    session_id                  UUID NOT NULL REFERENCES onboarding_session(id) ON DELETE CASCADE,
    side                        VARCHAR(8)   NOT NULL,
    image_path                  VARCHAR(512) NOT NULL,
    image_sha256                VARCHAR(64)  NOT NULL,
    detected_type               VARCHAR(32),
    classification_confidence   DOUBLE PRECISION,
    ocr_json                    TEXT,
    authenticity_json           TEXT,
    authenticity_score          DOUBLE PRECISION,
    veto                        BOOLEAN,
    created_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_capture_session_side UNIQUE (session_id, side)
);

CREATE INDEX idx_capture_session ON document_capture(session_id);

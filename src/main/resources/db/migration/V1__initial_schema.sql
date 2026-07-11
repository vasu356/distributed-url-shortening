-- ============================================================
-- V1: Core schema
-- Creates all primary tables with indexes, constraints, and
-- partitioned click_events table.
-- ============================================================

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'))
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_active ON users (is_active) WHERE is_active = TRUE;

-- ============================================================
-- API KEYS
-- ============================================================
CREATE TABLE api_keys (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_hash     VARCHAR(64) NOT NULL,
    name         VARCHAR(100),
    rate_limit   INT         NOT NULL DEFAULT 1000,
    expires_at   TIMESTAMPTZ,
    revoked      BOOLEAN     NOT NULL DEFAULT FALSE,
    last_used_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT api_keys_key_hash_unique UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_hash   ON api_keys (key_hash);
CREATE INDEX idx_api_keys_user   ON api_keys (user_id);
CREATE INDEX idx_api_keys_active ON api_keys (user_id, revoked) WHERE revoked = FALSE;

-- API key scopes (normalised 1:N)
CREATE TABLE api_key_scopes (
    api_key_id UUID        NOT NULL REFERENCES api_keys (id) ON DELETE CASCADE,
    scope      VARCHAR(50) NOT NULL,
    PRIMARY KEY (api_key_id, scope)
);

-- ============================================================
-- SHORT URLS
-- ============================================================
CREATE TABLE short_urls (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users (id),
    short_code    VARCHAR(20)  NOT NULL,
    long_url      TEXT         NOT NULL,
    title         VARCHAR(500),
    description   TEXT,
    favicon_url   VARCHAR(500),
    is_custom     BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted_at    TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    password_hash VARCHAR(255),
    click_count   BIGINT       NOT NULL DEFAULT 0,
    max_clicks    BIGINT,
    redirect_type SMALLINT     NOT NULL DEFAULT 302,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT short_urls_short_code_unique UNIQUE (short_code),
    CONSTRAINT short_urls_redirect_type_check CHECK (redirect_type IN (301, 302))
);

-- Primary lookup: short_code → long_url (redirect hot path)
CREATE UNIQUE INDEX idx_short_code        ON short_urls (short_code);

-- User URL list with descending date ordering
CREATE INDEX idx_short_urls_user_date     ON short_urls (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Scheduler: find URLs that have passed their expiry timestamp
CREATE INDEX idx_short_urls_expiry        ON short_urls (expires_at)
    WHERE expires_at IS NOT NULL AND is_active = TRUE;

-- Full-text search over long_url and title
CREATE INDEX idx_short_urls_fts           ON short_urls
    USING gin (to_tsvector('english', long_url || ' ' || COALESCE(title, '')));

-- ============================================================
-- CLICK EVENTS — partitioned by month
-- No FK constraint on partitioned tables in PG without extra config.
-- Referential integrity enforced at application layer.
-- ============================================================
CREATE TABLE click_events (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    short_url_id UUID        NOT NULL,
    ip_hash      VARCHAR(64),
    country_code VARCHAR(2),
    city         VARCHAR(100),
    user_agent   TEXT,
    referrer     VARCHAR(2000),
    device_type  VARCHAR(20),
    browser      VARCHAR(50),
    os           VARCHAR(50),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT click_events_device_check
        CHECK (device_type IN ('MOBILE','DESKTOP','TABLET','BOT','UNKNOWN') OR device_type IS NULL)
) PARTITION BY RANGE (created_at);

-- Create initial partitions covering the first two years
DO $$
DECLARE
    y INT;
    m INT;
    start_date DATE;
    end_date   DATE;
    tbl_name   TEXT;
BEGIN
    FOR y IN 2025..2028 LOOP
        FOR m IN 1..12 LOOP
            start_date := make_date(y, m, 1);
            end_date   := start_date + INTERVAL '1 month';
            tbl_name   := format('click_events_%s_%s',
                                 to_char(start_date, 'YYYY'),
                                 to_char(start_date, 'MM'));
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF click_events
                 FOR VALUES FROM (%L) TO (%L)',
                tbl_name, start_date, end_date
            );
        END LOOP;
    END LOOP;
END $$;

CREATE INDEX idx_click_url_date ON click_events (short_url_id, created_at DESC);
CREATE INDEX idx_click_date     ON click_events (created_at DESC);
CREATE INDEX idx_click_country  ON click_events (country_code, created_at DESC)
    WHERE country_code IS NOT NULL;

-- ============================================================
-- AUDIT LOGS — append-only, never updated
-- ============================================================
CREATE TABLE audit_logs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID,
    actor_type   VARCHAR(20),
    action       VARCHAR(100) NOT NULL,
    entity_type  VARCHAR(50),
    entity_id    UUID,
    old_value    JSONB,
    new_value    JSONB,
    ip_address   VARCHAR(45),
    user_agent   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT audit_logs_actor_type_check
        CHECK (actor_type IN ('USER','ADMIN','SYSTEM','API_KEY') OR actor_type IS NULL)
);

CREATE INDEX idx_audit_actor    ON audit_logs (actor_id, created_at DESC)
    WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_entity   ON audit_logs (entity_type, entity_id, created_at DESC)
    WHERE entity_id IS NOT NULL;
CREATE INDEX idx_audit_action   ON audit_logs (action, created_at DESC);
CREATE INDEX idx_audit_date     ON audit_logs (created_at DESC);

-- ============================================================
-- UPDATED_AT auto-update trigger
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_short_urls_updated_at
    BEFORE UPDATE ON short_urls
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

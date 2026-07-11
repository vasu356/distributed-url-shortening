-- ============================================================
-- V3: Additional analytics indexes
-- Added after profiling slow queries in staging.
-- Covers the most common analytics aggregation patterns.
-- ============================================================

-- Covering index for user dashboard: count active URLs per user
CREATE INDEX idx_short_urls_user_active
    ON short_urls (user_id, is_active)
    WHERE is_active = TRUE AND deleted_at IS NULL;

-- Partial index: find custom alias URLs (small subset)
CREATE INDEX idx_short_urls_custom
    ON short_urls (user_id, created_at DESC)
    WHERE is_custom = TRUE;

-- Index to support "find expired but still active" scheduler query efficiently
CREATE INDEX idx_short_urls_expire_cleanup
    ON short_urls (expires_at ASC, is_active)
    WHERE is_active = TRUE AND expires_at IS NOT NULL;

-- Index to support tombstone cleanup (find old soft-deleted)
CREATE INDEX idx_short_urls_tombstone
    ON short_urls (deleted_at ASC)
    WHERE deleted_at IS NOT NULL;

-- Composite click_events index for geographic analytics
CREATE INDEX idx_click_url_country
    ON click_events (short_url_id, country_code, created_at DESC);

-- Composite click_events index for device analytics
CREATE INDEX idx_click_url_device
    ON click_events (short_url_id, device_type, created_at DESC);

-- Composite click_events index for referrer analytics
CREATE INDEX idx_click_url_referrer
    ON click_events (short_url_id, referrer, created_at DESC)
    WHERE referrer IS NOT NULL;

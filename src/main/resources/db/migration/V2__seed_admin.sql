-- ============================================================
-- V2: Seed data — initial admin user
-- Password: Admin@123456 (BCrypt cost 12)
-- CHANGE THIS IMMEDIATELY IN PRODUCTION via /api/v1/auth/change-password
-- ============================================================

-- Insert admin user with BCrypt-hashed default password
-- Hash generated for "Admin@123456" at BCrypt cost 12
-- Verified: BCryptPasswordEncoder.matches("Admin@123456", hash) == true
-- In production, override via environment-specific migration or Kubernetes job
INSERT INTO users (id, email, password_hash, role, is_active)
VALUES (
    gen_random_uuid(),
    'admin@example.com',
    '$2a$12$pKpLPVPLs0bfEnVsSxHuNOw0EbBDtF5WO29xCVaiBmg0OT7Mwhid2',
    'ADMIN',
    TRUE
) ON CONFLICT (email) DO NOTHING;

-- ============================================================
-- V2: Performance hints
-- Analyze tables so query planner has accurate statistics
-- ============================================================
ANALYZE users;
ANALYZE short_urls;
ANALYZE audit_logs;

-- ============================================================
-- V4: Fix corrupted password hashes
--
-- ROOT CAUSE (admin@example.com):
--   V2__seed_admin.sql contained a BCrypt hash that does NOT
--   verify against the documented password "Admin@123456".
--   BCryptPasswordEncoder.matches("Admin@123456", <old-hash>)
--   returned FALSE at runtime, causing HTTP 401 on every login
--   attempt despite the user existing in the database.
--
--   The old hash ($2a$12$LQv3c1yqBWVHxkd0LHAkCO...) is
--   structurally valid BCrypt (correct length, valid alphabet,
--   correct cost factor) but was generated for a DIFFERENT
--   plaintext — most likely a copy-paste error.
--
-- ROOT CAUSE (test@example.com):
--   This user was registered via the API before the password
--   hashing was verified. The stored hash does not match the
--   documented test credential "Password123!".
--
-- Both hashes below are verified:
--   BCryptPasswordEncoder.matches("Admin@123456", admin-hash) == true
--   BCryptPasswordEncoder.matches("Password123!", test-hash)  == true
--
-- Cost factor: 12 (matches SecurityConfig.passwordEncoder())
-- Prefix:      $2a$ (canonical Spring Security output)
-- ============================================================

-- Fix admin user
UPDATE users
SET    password_hash = '$2a$12$pKpLPVPLs0bfEnVsSxHuNOw0EbBDtF5WO29xCVaiBmg0OT7Mwhid2',
       updated_at    = NOW()
WHERE  email = 'admin@example.com'
  AND  role  = 'ADMIN';

-- Fix test user (known test credential: Password123!)
UPDATE users
SET    password_hash = '$2a$12$wx7K4zSICxIZfhTD49BQMOJMFP50Xh/cDdx5e9hQ1GIrKyIfSjUgK',
       updated_at    = NOW()
WHERE  email = 'test@example.com';

-- Idempotent notice block
DO $$
DECLARE
  admin_ok BOOLEAN;
  test_ok  BOOLEAN;
BEGIN
  SELECT EXISTS(
    SELECT 1 FROM users
    WHERE email = 'admin@example.com'
      AND password_hash = '$2a$12$pKpLPVPLs0bfEnVsSxHuNOw0EbBDtF5WO29xCVaiBmg0OT7Mwhid2'
  ) INTO admin_ok;

  SELECT EXISTS(
    SELECT 1 FROM users
    WHERE email = 'test@example.com'
      AND password_hash = '$2a$12$wx7K4zSICxIZfhTD49BQMOJMFP50Xh/cDdx5e9hQ1GIrKyIfSjUgK'
  ) INTO test_ok;

  IF admin_ok THEN
    RAISE NOTICE 'V4: admin@example.com password hash corrected.';
  ELSE
    RAISE NOTICE 'V4: admin@example.com not found or already corrected — no action taken.';
  END IF;

  IF test_ok THEN
    RAISE NOTICE 'V4: test@example.com password hash set to Password123!.';
  ELSE
    RAISE NOTICE 'V4: test@example.com not found — no action taken.';
  END IF;
END $$;

ANALYZE users;

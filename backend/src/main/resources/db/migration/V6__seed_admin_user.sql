-- Seed the single admin account. Admins are never created through public signup
-- (signup always assigns USER) and never log in through OAuth.
--
-- DEV-ONLY CREDENTIALS: admin@eventbooking.dev / Admin@12345 (BCrypt cost 10).
-- Documented in the root README. Change this password (or replace the hash) before any real deployment.
INSERT INTO users (id, name, email, password_hash, role, email_verified, auth_provider, created_at)
VALUES ('00000000-0000-0000-0000-00000000a001',
        'Platform Admin',
        'admin@eventbooking.dev',
        '$2a$10$e1jS62BbNWjjRigmzDswou1AQqHt4gUv0fP2gDVfKpVezmu5SUJL2',
        'ADMIN',
        TRUE,
        'LOCAL',
        now())
ON CONFLICT (email) DO NOTHING;

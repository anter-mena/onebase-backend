-- Two roles only: ADMIN (everything; several allowed, never zero) and COMMERCIAL
-- (Clients, Renewals, WhatsApp and the email Inbox). Decided 2026-10-02.
-- OWNER becomes ADMIN, MANAGER becomes COMMERCIAL.

ALTER TABLE users DROP CONSTRAINT chk_users_role;

UPDATE users SET role = 'ADMIN'      WHERE role IN ('OWNER', 'ADMIN');
UPDATE users SET role = 'COMMERCIAL' WHERE role = 'MANAGER';

ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'COMMERCIAL'));

-- The Action log (table 12 of Database.md), plus when each password was last changed
-- (shown in Account settings → Security). Never edit this file once deployed; add V4… instead.

CREATE TABLE action_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       REFERENCES users(id) ON DELETE SET NULL,  -- who did it
    actor_name  VARCHAR(120) NOT NULL,                                 -- their name at the time, kept as written
    action      VARCHAR(20)  NOT NULL,
    target_type VARCHAR(30)  NOT NULL,
    target_id   BIGINT,
    target_name VARCHAR(150) NOT NULL,                                 -- kept even if the target is deleted
    detail      VARCHAR(500),
    source      VARCHAR(10)  NOT NULL DEFAULT 'WEB',
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_action_logs_action CHECK (action IN
        ('CREATED', 'UPDATED', 'ACTIVATED', 'DEACTIVATED', 'DELETED', 'EXPORTED', 'SIGNED_IN')),
    CONSTRAINT chk_action_logs_target CHECK (target_type IN
        ('CLIENT', 'BRAND', 'PAYMENT_METHOD', 'SUBSCRIPTION', 'WORKSPACE', 'USER')),
    CONSTRAINT chk_action_logs_source CHECK (source IN ('WEB', 'MOBILE', 'API'))
);
CREATE INDEX idx_action_logs_created ON action_logs (created_at DESC);

ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
-- Accounts that already have a password: count from when they were made.
UPDATE users SET password_changed_at = created_at WHERE password_hash IS NOT NULL;

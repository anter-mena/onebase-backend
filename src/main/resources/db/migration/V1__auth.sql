-- Auth: who can sign in, their one-time links, and their open sessions.
-- Mirrors tables 1–3 of Database.md. Never edit this file once deployed; add V2, V3… instead.

CREATE TABLE users (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name              VARCHAR(120) NOT NULL,
    email                  VARCHAR(255) NOT NULL UNIQUE,          -- always stored lowercase
    password_hash          VARCHAR(255),                          -- null until an invite is accepted
    role                   VARCHAR(20)  NOT NULL,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,    -- false = cannot sign in
    invite_pending         BOOLEAN      NOT NULL DEFAULT FALSE,
    language               VARCHAR(10)  NOT NULL DEFAULT 'en',
    time_zone              VARCHAR(50)  NOT NULL DEFAULT 'UTC',
    date_format            VARCHAR(20)  NOT NULL DEFAULT 'dd MMM yyyy',
    notify_renewals        BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_failed_payments BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_weekly_digest   BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,       -- reset on every successful sign-in
    locked_until           TIMESTAMPTZ,                           -- set after too many wrong passwords
    last_active_at         TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_role CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER'))
);

-- One-time links: password reset now, user invitations later.
-- Only a SHA-256 of the token is stored, so a copy of the database cannot be used to reset anyone.
CREATE TABLE auth_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(20)  NOT NULL,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_auth_tokens_type CHECK (type IN ('PASSWORD_RESET', 'INVITE'))
);
CREATE INDEX idx_auth_tokens_user ON auth_tokens (user_id);

-- One row per sign-in. The access token carries this id, and every request checks the row
-- is still open — which is what makes "log out" and "change password" take effect at once.
CREATE TABLE sessions (
    id         UUID         PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ip         VARCHAR(45),
    user_agent VARCHAR(255),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sessions_user ON sessions (user_id);

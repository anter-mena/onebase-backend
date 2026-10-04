-- Expenses (Configuration → Expenses), decided 2026-10-04. USD only.
-- 1. The real cost and panel credit of each plan, on the 16 rows V6 created.
-- 2. Perks: extras that cost you money (no client price — the plan price stays the same).
-- 3. Panel credit top-ups: credits bought, and what they cost. Credit left = top-ups − credits used.
-- Written once: editing these later changes the rows; a redeploy never puts them back.
-- Never edit this file once deployed; add V9… instead.

-- 1. Cost and credits per plan -------------------------------------------------------
UPDATE plans AS p SET cost = v.cost, credits = v.credits
FROM (VALUES
    (1, 1, 0.83, 1),  (1, 3, 2.50, 3),  (1, 6, 5.00, 6),   (1, 12, 10.00, 12),
    (2, 1, 0.83, 1),  (2, 3, 2.50, 3),  (2, 6, 5.00, 6),   (2, 12, 10.00, 12),
    (3, 1, 1.66, 2),  (3, 3, 5.00, 6),  (3, 6, 10.00, 12), (3, 12, 20.00, 24),
    (4, 1, 1.66, 2),  (4, 3, 5.00, 6),  (4, 6, 10.00, 12), (4, 12, 20.00, 24)
) AS v(devices, months, cost, credits)
WHERE p.devices = v.devices AND p.months = v.months;

ALTER TABLE plans ADD CONSTRAINT chk_plans_cost CHECK (cost IS NULL OR cost >= 0);
ALTER TABLE plans ADD CONSTRAINT chk_plans_credits CHECK (credits IS NULL OR credits >= 0);

-- 2. Perks ---------------------------------------------------------------------------
CREATE TABLE perks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,          -- unique without regard to capitals (index below)
    description VARCHAR(255),
    cost        NUMERIC(12,2) NOT NULL CHECK (cost >= 0),  -- what it costs you, in USD
    active      BOOLEAN       NOT NULL DEFAULT TRUE,       -- false = no longer offered. No delete.
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_perks_name ON perks (lower(name));

INSERT INTO perks (name, description, cost) VALUES
    ('IBO Player', 'Player licence for the client''s devices.', 2.00);

-- 3. Panel credit top-ups (replaces the sketch in Database.md: it now records what was paid)
CREATE TABLE credit_topups (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    credits    INT           NOT NULL CHECK (credits > 0),
    amount     NUMERIC(12,2) NOT NULL CHECK (amount >= 0),  -- what the credits cost, in USD
    note       VARCHAR(255),
    created_by BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The Action log can now say "Perk" and "Panel credit".
ALTER TABLE action_logs DROP CONSTRAINT chk_action_logs_target;
ALTER TABLE action_logs ADD CONSTRAINT chk_action_logs_target CHECK (target_type IN
    ('CLIENT', 'BRAND', 'PAYMENT_METHOD', 'SUBSCRIPTION', 'WORKSPACE', 'USER', 'PERK', 'PANEL_CREDIT'));

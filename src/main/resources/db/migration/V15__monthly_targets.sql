-- The Dashboard's monthly target (2026-10-06): what the month's earnings are measured
-- against, set by an Admin with the pencil. One row per month (its first day). USD.
CREATE TABLE monthly_targets (
    month       DATE          PRIMARY KEY,
    target      NUMERIC(12,2) NOT NULL,
    updated_by  BIGINT        REFERENCES users (id),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_monthly_targets_first_day CHECK (EXTRACT(DAY FROM month) = 1),
    CONSTRAINT chk_monthly_targets_target CHECK (target > 0)
);

-- Subscription plans (table 7 of Database.md): one row per devices × months.
-- USD only (decided 2026-10-04): every amount in One Base is in US dollars.
-- The grid is fixed — no plan is ever added or removed — so the 16 rows are written here,
-- once, with the real prices. Editing a price later changes the row; a redeploy never
-- puts these values back (a migration only ever runs once).
-- Never edit this file once deployed; add V7… instead.

CREATE TABLE plans (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    devices    SMALLINT      NOT NULL CHECK (devices BETWEEN 1 AND 4),
    months     SMALLINT      NOT NULL CHECK (months IN (1, 3, 6, 12)),
    price      NUMERIC(12,2) NOT NULL CHECK (price > 0),  -- sale price in USD (Configuration → Subscriptions)
    cost       NUMERIC(12,2),                             -- what it costs you, in USD (Expenses, filled later)
    credits    INT,                                       -- panel credit it uses up (Expenses, filled later)
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_plans_devices_months UNIQUE (devices, months)
);

INSERT INTO plans (devices, months, price) VALUES
    (1, 1, 14.99), (1, 3, 21.99), (1, 6, 35.99), (1, 12, 57.99),
    (2, 1, 21.99), (2, 3, 36.99), (2, 6, 65.99), (2, 12, 94.99),
    (3, 1, 28.99), (3, 3, 49.99), (3, 6, 86.99), (3, 12, 130.99),
    (4, 1, 36.99), (4, 3, 65.99), (4, 6, 94.99), (4, 12, 144.99);

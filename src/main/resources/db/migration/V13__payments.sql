-- Payments (built 2026-10-06). A payment is recorded once the money has arrived, so
-- created_at is the payment date. Every figure is copied in at that moment — the
-- plan's price, cost and panel credits, each perk's cost — so a later change in
-- Configuration never rewrites an old payment. USD.

CREATE TABLE payments (
    id                 BIGSERIAL PRIMARY KEY,                -- also the receipt reference
    client_id          BIGINT        NOT NULL REFERENCES clients (id),
    brand_id           BIGINT        NOT NULL REFERENCES brands (id),
    payment_method_id  BIGINT        NOT NULL REFERENCES payment_methods (id),
    kind               VARCHAR(20)   NOT NULL,
    devices            SMALLINT      NOT NULL,
    months             SMALLINT      NOT NULL,
    plan_price         NUMERIC(12,2) NOT NULL,               -- the plan's list price at the time
    amount             NUMERIC(12,2) NOT NULL,               -- what the client paid (a private price can differ)
    plan_cost          NUMERIC(12,2),                        -- null when the plan had no cost on file
    perks_cost         NUMERIC(12,2) NOT NULL DEFAULT 0,
    credits_used       INT           NOT NULL DEFAULT 0,     -- panel credit this sale spent
    starts_on          DATE          NOT NULL,               -- a renewal starts where the running term ends
    ends_on            DATE          NOT NULL,
    created_by         BIGINT        REFERENCES users (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,                          -- Admin delete: soft, logged, left out of every total
    CONSTRAINT chk_payments_kind CHECK (kind IN ('NEW_PLAN', 'RENEWAL')),
    CONSTRAINT chk_payments_devices CHECK (devices BETWEEN 1 AND 10),
    CONSTRAINT chk_payments_months CHECK (months BETWEEN 1 AND 36),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_costs CHECK (plan_price >= 0 AND (plan_cost IS NULL OR plan_cost >= 0) AND perks_cost >= 0),
    CONSTRAINT chk_payments_credits CHECK (credits_used >= 0),
    CONSTRAINT chk_payments_dates CHECK (ends_on > starts_on)
);

CREATE INDEX idx_payments_client ON payments (client_id, created_at DESC);
CREATE INDEX idx_payments_method ON payments (payment_method_id);

-- The perks a payment included, with how many and what each cost then.
CREATE TABLE payment_perks (
    payment_id  BIGINT        NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    perk_id     BIGINT        NOT NULL REFERENCES perks (id),
    name        VARCHAR(100)  NOT NULL,
    quantity    INT           NOT NULL,
    unit_cost   NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (payment_id, perk_id),
    CONSTRAINT chk_payment_perks_quantity CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT chk_payment_perks_cost CHECK (unit_cost >= 0)
);

-- Payments appear in the Action log.
ALTER TABLE action_logs DROP CONSTRAINT chk_action_logs_target;
ALTER TABLE action_logs ADD CONSTRAINT chk_action_logs_target CHECK (target_type IN
    ('CLIENT', 'BRAND', 'PAYMENT_METHOD', 'SUBSCRIPTION', 'WORKSPACE', 'USER', 'PERK', 'PANEL_CREDIT', 'PAYMENT'));

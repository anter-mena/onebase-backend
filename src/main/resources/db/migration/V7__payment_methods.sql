-- Payment methods (table 10 of Database.md): the accounts you receive money on.
-- No delete: a method is switched off instead, so old payments keep pointing at it.
-- No balance and no currency columns (decided 2026-10-04): a method starts at $0, its
-- total will come from the payments recorded on it, and every amount is USD.
-- Never edit this file once deployed; add V8… instead.

CREATE TABLE payment_methods (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider     VARCHAR(20)   NOT NULL,
    name         VARCHAR(100)  NOT NULL,   -- spaces tidied before saving; unique without regard to capitals (index below)
    holder       VARCHAR(120)  NOT NULL,
    card_network VARCHAR(20)   NOT NULL DEFAULT 'BOTH',  -- only decides the logos drawn on the card
    instructions VARCHAR(1000),            -- for clients, e.g. "Add your client number as the reference"
    active       BOOLEAN       NOT NULL DEFAULT TRUE,    -- false = can't be chosen for new payments
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_methods_provider CHECK (provider IN ('PAYPAL', 'BINANCE', 'INTERAC', 'OTHER')),
    CONSTRAINT chk_payment_methods_network CHECK (card_network IN ('VISA', 'MASTERCARD', 'BOTH'))
);

-- "Main PayPal" and "main paypal" are the same name.
CREATE UNIQUE INDEX uq_payment_methods_name ON payment_methods (lower(name));

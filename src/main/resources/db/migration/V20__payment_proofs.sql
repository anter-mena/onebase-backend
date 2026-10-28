-- The proof of a payment (2026-10-28): the invoice, the transfer screenshot, the
-- receipt email. Added with the payment, in the same step, so a payment saved from
-- the window always has one. Kept in the database, so it is backed up with it.

CREATE TABLE payment_proofs (
    id          BIGSERIAL    PRIMARY KEY,
    payment_id  BIGINT       NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    data        BYTEA        NOT NULL,
    mime        VARCHAR(100) NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    size_bytes  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX payment_proofs_payment_id ON payment_proofs (payment_id);

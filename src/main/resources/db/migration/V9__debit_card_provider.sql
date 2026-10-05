-- Payment methods: the type "OTHER" is renamed "DEBIT_CARD" (decided 2026-10-04).
-- The constraint is dropped first, so the rows can take the new value.
ALTER TABLE payment_methods DROP CONSTRAINT chk_payment_methods_provider;

UPDATE payment_methods SET provider = 'DEBIT_CARD' WHERE provider = 'OTHER';

ALTER TABLE payment_methods
    ADD CONSTRAINT chk_payment_methods_provider CHECK (provider IN ('PAYPAL', 'BINANCE', 'INTERAC', 'DEBIT_CARD'));

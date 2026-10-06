-- When a client's status last changed (2026-10-06). Callback is "Trial for more than 24
-- hours", so the trial's start has to be known. Clients already on a status count from
-- the day this ran.
ALTER TABLE clients ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_clients_status ON clients (status, status_changed_at) WHERE deleted_at IS NULL;

-- Clients (built 2026-10-05). The plan, devices, end date, orders, payment method and
-- revenue are NOT stored here: they are worked out from the payments (next module),
-- so they can never disagree with the money.

CREATE TABLE clients (
    id            BIGSERIAL PRIMARY KEY,
    full_name     VARCHAR(120),                      -- typed by the team
    username      VARCHAR(120),                      -- the WhatsApp profile name, kept up to date
    email         VARCHAR(255),
    phone         VARCHAR(20),                       -- E.164: +212612345678
    country       VARCHAR(2),                        -- ISO code worked out from the phone when saved
    brand_id      BIGINT       REFERENCES brands (id),
    status        VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    source        VARCHAR(20)  NOT NULL,
    note          TEXT,
    created_by    BIGINT       REFERENCES users (id), -- null when WhatsApp made the row
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,                       -- soft delete: past money stays in the totals
    CONSTRAINT chk_clients_status CHECK (status IN ('NEW', 'CALLBACK', 'TRIAL', 'PENDING', 'ACTIVE', 'INACTIVE', 'DROP')),
    CONSTRAINT chk_clients_source CHECK (source IN ('MANUAL', 'WHATSAPP')),
    CONSTRAINT chk_clients_phone CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT chk_clients_country CHECK (country IS NULL OR country ~ '^[A-Z]{2}$'),
    -- A row must say who it is about.
    CONSTRAINT chk_clients_identity CHECK (full_name IS NOT NULL OR username IS NOT NULL OR phone IS NOT NULL)
);

-- One client per number, deleted ones included: a deleted client who writes again comes back.
CREATE UNIQUE INDEX uq_clients_phone ON clients (phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_clients_brand ON clients (brand_id);

-- The WhatsApp conversation of a client (the column was left loose in V11).
ALTER TABLE whatsapp_conversations
    ADD CONSTRAINT fk_whatsapp_conversations_client FOREIGN KEY (client_id) REFERENCES clients (id);
CREATE INDEX idx_whatsapp_conversations_client ON whatsapp_conversations (client_id);

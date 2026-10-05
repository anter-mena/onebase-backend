-- WhatsApp Inbox (built 2026-10-05). WhatsApp keeps no history the API can read back,
-- so every message is saved here as it arrives (webhook) or is sent.

-- One conversation per WhatsApp number (wa_id = the number in digits, as Meta sends it).
CREATE TABLE whatsapp_conversations (
    id                BIGSERIAL PRIMARY KEY,
    wa_id             VARCHAR(20)  NOT NULL,
    contact_name      VARCHAR(120),
    unread_count      INT          NOT NULL DEFAULT 0,
    last_message_at   TIMESTAMPTZ,
    last_preview      VARCHAR(200),
    -- The 24-hour window: free text is allowed until 24h after the client's last message.
    last_inbound_at   TIMESTAMPTZ,
    client_id         BIGINT,                        -- linked when the Clients module exists
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_conversations_wa_id UNIQUE (wa_id),
    CONSTRAINT chk_whatsapp_conversations_unread CHECK (unread_count >= 0)
);

CREATE INDEX idx_whatsapp_conversations_last ON whatsapp_conversations (last_message_at DESC NULLS LAST);

CREATE TABLE whatsapp_messages (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT       NOT NULL REFERENCES whatsapp_conversations (id) ON DELETE CASCADE,
    wa_message_id    VARCHAR(128),                   -- Meta's id (wamid.…); null until Meta accepts a send
    direction        VARCHAR(3)   NOT NULL,
    type             VARCHAR(12)  NOT NULL,
    body             TEXT,
    media_id         VARCHAR(64),                    -- Meta's media id, to download the file
    media_mime       VARCHAR(100),
    media_filename   VARCHAR(255),
    status           VARCHAR(10)  NOT NULL,
    error            VARCHAR(300),
    sent_by          BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_messages_wa_id UNIQUE (wa_message_id),
    CONSTRAINT chk_whatsapp_messages_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT chk_whatsapp_messages_type CHECK (type IN ('TEXT', 'IMAGE', 'DOCUMENT', 'AUDIO', 'VIDEO', 'STICKER', 'TEMPLATE', 'OTHER')),
    CONSTRAINT chk_whatsapp_messages_status CHECK (status IN ('RECEIVED', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED'))
);

CREATE INDEX idx_whatsapp_messages_conversation ON whatsapp_messages (conversation_id, created_at, id);

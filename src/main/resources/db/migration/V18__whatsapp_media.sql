-- WhatsApp files kept by OneBase (2026-10-16). Meta deletes a file after a while
-- (those of 2026-10-05 were already gone), so every photo, audio, PDF, video and
-- sticker is copied here, forever, and backed up with the database:
-- what we send, when we send it; what arrives, shortly after (WhatsAppMediaSweeper).

CREATE TABLE whatsapp_media (
    message_id  BIGINT       PRIMARY KEY REFERENCES whatsapp_messages (id) ON DELETE CASCADE,
    data        BYTEA        NOT NULL,
    mime        VARCHAR(100) NOT NULL,
    size_bytes  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Fetching a received file from Meta: how many tries so far, and not before when.
-- media_fetch_attempts = 1000 means given up (the file is gone, too large, or a day of failures).
ALTER TABLE whatsapp_messages ADD COLUMN media_fetch_attempts SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE whatsapp_messages ADD COLUMN media_fetch_next_at TIMESTAMPTZ;

-- WhatsApp message templates (built 2026-10-15). Not Meta-approved templates: a saved
-- message an Admin builds once and anyone sends from the WhatsApp Inbox, as normal
-- messages (texts, files, buttons), so only inside the 24-hour window.
-- No delete: a template is switched off instead.

CREATE TABLE message_templates (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,                 -- "Trial ending – EN"
    language    VARCHAR(2)   NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,    -- false = not offered in the Inbox
    footer      VARCHAR(60)  NOT NULL DEFAULT '',
    created_by  BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    updated_by  BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- content changes only, not the on/off switch
    CONSTRAINT chk_message_templates_language CHECK (language IN ('EN', 'FR', 'AR'))
);

-- One name per language, whatever the capitals.
CREATE UNIQUE INDEX uq_message_templates_name ON message_templates (lower(name), language);

-- The photos, PDFs and audios, kept in the database (like brand logos) so they are backed up with it.
-- A file nothing uses any more is deleted after the next template save.
CREATE TABLE message_template_files (
    id          BIGSERIAL    PRIMARY KEY,
    data        BYTEA        NOT NULL,
    mime        VARCHAR(100) NOT NULL,                 -- read from the bytes, never from the name
    filename    VARCHAR(255) NOT NULL,
    size_bytes  INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_message_template_files_size CHECK (size_bytes > 0)
);

-- The message, in order (0 = the first thing the client receives). Rewritten as a whole on each save.
CREATE TABLE message_template_blocks (
    template_id   BIGINT        NOT NULL REFERENCES message_templates (id) ON DELETE CASCADE,
    position      INTEGER       NOT NULL,
    type          VARCHAR(10)   NOT NULL,
    text          TEXT,                                -- TEXT
    file_id       BIGINT        REFERENCES message_template_files (id),   -- IMAGE / DOCUMENT / AUDIO
    button_type   VARCHAR(12),                         -- BUTTON
    button_label  VARCHAR(20),
    button_value  VARCHAR(2000),                       -- URL: the link; PHONE: the number; QUICK_REPLY: null
    PRIMARY KEY (template_id, position),
    CONSTRAINT chk_message_template_blocks_position CHECK (position >= 0),
    CONSTRAINT chk_message_template_blocks_type CHECK (type IN ('TEXT', 'IMAGE', 'DOCUMENT', 'AUDIO', 'BUTTON')),
    CONSTRAINT chk_message_template_blocks_button_type CHECK (button_type IS NULL OR button_type IN ('QUICK_REPLY', 'URL', 'PHONE')),
    CONSTRAINT chk_message_template_blocks_shape CHECK (
        (type = 'TEXT' AND text IS NOT NULL AND file_id IS NULL AND button_type IS NULL AND button_label IS NULL AND button_value IS NULL)
        OR (type IN ('IMAGE', 'DOCUMENT', 'AUDIO') AND file_id IS NOT NULL AND text IS NULL AND button_type IS NULL AND button_label IS NULL AND button_value IS NULL)
        OR (type = 'BUTTON' AND button_type IS NOT NULL AND button_label IS NOT NULL AND text IS NULL AND file_id IS NULL))
);

CREATE INDEX idx_message_template_blocks_file ON message_template_blocks (file_id) WHERE file_id IS NOT NULL;

-- Templates appear in the Action log.
ALTER TABLE action_logs DROP CONSTRAINT chk_action_logs_target;
ALTER TABLE action_logs ADD CONSTRAINT chk_action_logs_target CHECK (target_type IN
    ('CLIENT', 'BRAND', 'PAYMENT_METHOD', 'SUBSCRIPTION', 'WORKSPACE', 'USER', 'PERK', 'PANEL_CREDIT', 'PAYMENT', 'TEMPLATE'));

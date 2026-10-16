-- Saved templates in the Inbox (2026-10-16): a message sent from a template keeps its
-- footer and its buttons apart from the text, so the conversation shows them as the
-- client saw them (grey footer, buttons under the bubble). Null for every other message.

ALTER TABLE whatsapp_messages ADD COLUMN footer VARCHAR(60);
ALTER TABLE whatsapp_messages ADD COLUMN buttons TEXT;   -- JSON: [{"type":"QUICK_REPLY"|"URL","label":"…","value":null|"https://…"}]

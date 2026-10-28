-- When each person last opened the email Inbox (2026-10-28). The sidebar counts the
-- brand emails that arrived after it: read or not, since a website form email is
-- filed by Gmail as already read (it is "from" the mailbox itself).
-- Null until their first visit; until then the count is the unread brand mail.

ALTER TABLE users ADD COLUMN inbox_seen_at TIMESTAMPTZ;

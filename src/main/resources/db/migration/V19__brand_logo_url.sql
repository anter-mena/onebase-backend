-- Brand logos are no longer stored (decided 2026-10-16): we keep only the logo's web
-- address, and every screen and email loads it from the brand's own website.
-- The PNGs kept so far have no address to copy, so those brands start with none
-- (the initials show) until an Admin presses "Fetch again" or pastes one.

ALTER TABLE brands ADD COLUMN logo_url VARCHAR(2000);  -- absolute http(s) address on the brand's site; NULL = none

ALTER TABLE brands DROP COLUMN logo;
ALTER TABLE brands DROP COLUMN logo_updated_at;

-- The language setting is removed (decided 2026-10-04): One Base is English only,
-- and a saved choice the app never used was a promise it did not keep.
-- Never edit this file once deployed; add V5… instead.

ALTER TABLE users DROP COLUMN language;

-- SEO Overview: each brand's Google Analytics 4 property (decided 2026-10-05).
-- The column was prepared in V5; it now holds only the number (e.g. 412305881).
-- Empty = the brand is not on the SEO page.
ALTER TABLE brands
    ADD CONSTRAINT chk_brands_ga4_property_id CHECK (ga4_property_id IS NULL OR ga4_property_id ~ '^[0-9]{6,15}$');

-- Brands (table 4 of Database.md): the websites clients subscribe to.
-- No delete: a brand is switched off instead, so clients and payments keep pointing at it.
-- Never edit this file once deployed; add V6… instead.

CREATE TABLE brands (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    domain          VARCHAR(255) NOT NULL UNIQUE,   -- "nike.com": lowercase, no "www." — what makes two links the same brand
    website_url     VARCHAR(255) NOT NULL,          -- "https://www.nike.com"
    instagram_url   VARCHAR(255),
    facebook_url    VARCHAR(255),
    x_url           VARCHAR(255),
    tiktok_url      VARCHAR(255),
    logo            BYTEA,                          -- a small PNG we made ourselves (never the file as downloaded)
    logo_updated_at TIMESTAMPTZ,                    -- changes the logo's address, so browsers fetch the new one
    ga4_property_id VARCHAR(50),                    -- for SEO Overview, filled in with that module
    active          BOOLEAN      NOT NULL DEFAULT TRUE,  -- false = can't be chosen for new clients or payments
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

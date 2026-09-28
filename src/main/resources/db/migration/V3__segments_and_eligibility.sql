-- =====================================================================
-- V3: customer segments, account types, card product eligibility,
--     operator-driven issuance (CMS creates card, Dexxis fetches by PAN)
-- =====================================================================

CREATE TABLE customer_segment (
    code    VARCHAR(16)  PRIMARY KEY,          -- MASS, PREMIUM, PAYROLL, STAFF, CORPORATE ...
    name    VARCHAR(64)  NOT NULL,
    active  BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE account_type (
    code        VARCHAR(16) PRIMARY KEY,       -- CURRENT, SAVINGS, PREPAID, PAYROLL ...
    name        VARCHAR(64) NOT NULL,
    -- Where the balance lives. CMS_LEDGER: CMS authorises against its own ledger.
    -- CORE_BANKING: CMS authorises the card, funds check goes to core banking (later phase).
    ledger_mode VARCHAR(16) NOT NULL DEFAULT 'CMS_LEDGER'
                CHECK (ledger_mode IN ('CMS_LEDGER','CORE_BANKING')),
    active      BOOLEAN     NOT NULL DEFAULT TRUE
);

-- ---------- customer ----------
ALTER TABLE customer
    ADD COLUMN customer_type  VARCHAR(12) NOT NULL DEFAULT 'INDIVIDUAL'
               CHECK (customer_type IN ('INDIVIDUAL','CORPORATE')),
    ADD COLUMN segment_code   VARCHAR(16) REFERENCES customer_segment(code),
    ADD COLUMN national_id    VARCHAR(32),
    ADD COLUMN date_of_birth  DATE,
    ADD COLUMN email          VARCHAR(128),
    ADD COLUMN address        VARCHAR(256),
    ADD COLUMN created_by     VARCHAR(64);
CREATE UNIQUE INDEX ux_customer_national_id ON customer(national_id) WHERE national_id IS NOT NULL;

-- ---------- account ----------
ALTER TABLE account
    ADD COLUMN account_type_code VARCHAR(16) REFERENCES account_type(code),
    ADD COLUMN created_by        VARCHAR(64);

-- ---------- card product ----------
ALTER TABLE card_product
    ADD COLUMN card_type  VARCHAR(12) NOT NULL DEFAULT 'DEBIT'
               CHECK (card_type IN ('DEBIT','PREPAID','CREDIT')),
    ADD COLUMN card_tier  VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',   -- CLASSIC, GOLD, PLATINUM, PAYROLL ...
    ADD COLUMN scheme     VARCHAR(12) NOT NULL DEFAULT 'MEEZA'
               CHECK (scheme IN ('VISA','MASTERCARD','MEEZA','PRIVATE')),
    ADD COLUMN max_cards_per_account SMALLINT NOT NULL DEFAULT 1;

-- Which product may be issued for which (account type, customer segment).
CREATE TABLE product_eligibility (
    product_id         BIGINT      NOT NULL REFERENCES card_product(id),
    account_type_code  VARCHAR(16) NOT NULL REFERENCES account_type(code),
    segment_code       VARCHAR(16) NOT NULL REFERENCES customer_segment(code),
    PRIMARY KEY (product_id, account_type_code, segment_code)
);

-- ---------- card ----------
ALTER TABLE card
    ADD COLUMN created_by          VARCHAR(64),
    ADD COLUMN perso_fetch_count   INT NOT NULL DEFAULT 0,
    ADD COLUMN last_perso_fetch_at TIMESTAMPTZ;

-- ---------- seed (test) ----------
INSERT INTO customer_segment (code, name) VALUES
    ('MASS',      'Mass retail'),
    ('PREMIUM',   'Premium / priority'),
    ('PAYROLL',   'Payroll customers'),
    ('STAFF',     'Bank staff'),
    ('CORPORATE', 'Corporate');

INSERT INTO account_type (code, name, ledger_mode) VALUES
    ('PREPAID', 'Prepaid account',  'CMS_LEDGER'),
    ('PAYROLL', 'Payroll account',  'CMS_LEDGER'),
    ('CURRENT', 'Current account',  'CMS_LEDGER'),
    ('SAVINGS', 'Savings account',  'CMS_LEDGER');

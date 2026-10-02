-- CMS-100: fee plans and foreign-currency transactions.
-- A product points to a fee plan; a plan has one rule per event (and region). Transaction fees are charged by the
-- authorization engine, card event fees at issue / replacement / renewal, periodic fees by the FEE_PERIODIC job.
-- Foreign-currency transactions are converted to the account currency with fx_rate (when the product allows it);
-- the FX_MARKUP rule adds a percentage fee on the converted amount.

CREATE TABLE fee_plan (
    code         VARCHAR(32)  PRIMARY KEY,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(256),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64)
);

-- amounts in minor units of the account currency; fee = fixed + percent of the (converted) amount, then min / max
CREATE TABLE fee_rule (
    id              BIGSERIAL    PRIMARY KEY,
    plan_code       VARCHAR(32)  NOT NULL REFERENCES fee_plan(code),
    event           VARCHAR(24)  NOT NULL CHECK (event IN ('ISSUANCE','REPLACEMENT','RENEWAL','ANNUAL','MONTHLY',
                        'ATM_WITHDRAWAL','ATM_BALANCE_INQUIRY','POS_PURCHASE','ECOM_PURCHASE','PIN_CHANGE','FX_MARKUP')),
    region          VARCHAR(16)  NOT NULL DEFAULT 'ANY' CHECK (region IN ('ANY','DOMESTIC','INTERNATIONAL')),
    fixed_amount    BIGINT       NOT NULL DEFAULT 0 CHECK (fixed_amount >= 0),
    percent         NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (percent >= 0 AND percent <= 100),
    min_amount      BIGINT       CHECK (min_amount >= 0),
    max_amount      BIGINT       CHECK (max_amount >= 0),
    free_per_month  INT          NOT NULL DEFAULT 0 CHECK (free_per_month >= 0),
    UNIQUE (plan_code, event, region)
);

ALTER TABLE card_product
    ADD COLUMN fee_plan_code VARCHAR(32) REFERENCES fee_plan(code),
    ADD COLUMN fx_allowed    BOOLEAN NOT NULL DEFAULT FALSE;     -- accept transactions in other currencies

COMMENT ON COLUMN card_product.wd_fee IS 'Deprecated by fee plans (V11); kept for rollback only';
COMMENT ON COLUMN card_product.bi_fee IS 'Deprecated by fee plans (V11); kept for rollback only';

-- 1 unit of base_ccy = rate units of quote_ccy (major units); base = transaction currency, quote = account currency
CREATE TABLE fx_rate (
    base_ccy    CHAR(3)       NOT NULL REFERENCES currency(code),
    quote_ccy   CHAR(3)       NOT NULL REFERENCES currency(code),
    rate        NUMERIC(18,8) NOT NULL CHECK (rate > 0),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by  VARCHAR(64),
    PRIMARY KEY (base_ccy, quote_ccy),
    CHECK (base_ccy <> quote_ccy)
);

ALTER TABLE iso_transaction
    ADD COLUMN billing_amount   BIGINT,          -- amount in the account currency (FX transactions)
    ADD COLUMN billing_currency CHAR(3),
    ADD COLUMN fx_rate          NUMERIC(18,8),
    ADD COLUMN fx_fee           BIGINT;          -- FX markup, part of fee_amount

-- card event and periodic fees, one row per card / event / period (idempotent charging)
CREATE TABLE card_fee_charge (
    id          BIGSERIAL    PRIMARY KEY,
    card_id     BIGINT       NOT NULL REFERENCES card(id),
    account_id  BIGINT       NOT NULL REFERENCES account(id),
    event       VARCHAR(24)  NOT NULL,
    period      VARCHAR(16)  NOT NULL,          -- ONCE, Y1, Y2 ... (annual), 2026-10 (monthly)
    amount      BIGINT       NOT NULL,
    journal_id  UUID,
    core_ref    VARCHAR(64),
    queued      BOOLEAN      NOT NULL DEFAULT FALSE,   -- core banking account, sent through store-and-forward
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(64),
    UNIQUE (card_id, event, period)
);
CREATE INDEX ix_card_fee_charge_card ON card_fee_charge (card_id);

-- existing fixed ATM fees move to one plan per product, so behaviour does not change
INSERT INTO fee_plan (code, name, description, updated_by)
SELECT 'FP_' || code, name || ' fees', 'Created from the product''s ATM fees by V11', 'MIGRATION'
  FROM card_product WHERE wd_fee > 0 OR bi_fee > 0;
INSERT INTO fee_rule (plan_code, event, fixed_amount)
SELECT 'FP_' || code, 'ATM_WITHDRAWAL', wd_fee FROM card_product WHERE wd_fee > 0;
INSERT INTO fee_rule (plan_code, event, fixed_amount)
SELECT 'FP_' || code, 'ATM_BALANCE_INQUIRY', bi_fee FROM card_product WHERE bi_fee > 0;
UPDATE card_product SET fee_plan_code = 'FP_' || code WHERE wd_fee > 0 OR bi_fee > 0;

INSERT INTO batch_job (code, name, description, cron) VALUES
    ('FEE_PERIODIC', 'Periodic card fees', 'Charges monthly fees (once per month) and annual fees (on each activation anniversary)', '0 0 2 * * *');

INSERT INTO approval_policy (action, description, required) VALUES
    ('FEE_PLAN_SAVE', 'Create or change a fee plan and its rules', TRUE),
    ('FX_RATE_SAVE',  'Create or change a foreign exchange rate',  TRUE);

-- FX markup is its own journal type (reversed together with the transaction fee)
ALTER TABLE journal DROP CONSTRAINT journal_entry_type_check;
ALTER TABLE journal ADD CONSTRAINT journal_entry_type_check CHECK (entry_type IN
    ('WITHDRAWAL','PURCHASE','COMPLETION','REFUND','FEE','FX_FEE','REVERSAL',
     'FUNDING','DEBIT_ADJUSTMENT','CREDIT_ADJUSTMENT'));

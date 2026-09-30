-- =====================================================================
-- V5: authorization engine and ledger (CMS-040 .. CMS-050)
--   - transaction types for ATM / POS / e-commerce, advices, pre-auth and completion
--   - journals (one row per business entry) over the existing double-entry postings
--   - holds for pre-authorisations, fees, channel controls, per-card limit overrides
-- Money stays BIGINT minor units. Existing behaviour is unchanged: channels default on,
-- fees default to zero, POS amount limits fall back to the ATM limits while NULL, so
-- existing product INSERT scripts keep working.
-- =====================================================================

-- ---------- card product: channels, POS limits, fees, CVV check ----------
ALTER TABLE card_product
    ADD COLUMN atm_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN pos_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN ecom_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN daily_pos_count  INT     NOT NULL DEFAULT 20,
    ADD COLUMN daily_pos_amount BIGINT,                                           -- NULL = same as daily_wd_amount
    ADD COLUMN per_txn_pos_max  BIGINT,                                           -- NULL = same as per_txn_wd_max
    ADD COLUMN wd_fee           BIGINT  NOT NULL DEFAULT 0 CHECK (wd_fee >= 0),   -- minor units, per ATM withdrawal
    ADD COLUMN bi_fee           BIGINT  NOT NULL DEFAULT 0 CHECK (bi_fee >= 0),   -- per balance inquiry
    ADD COLUMN verify_cvv       BOOLEAN NOT NULL DEFAULT FALSE,                   -- CVV1/iCVV from track 2 (needs IN-04)
    ADD COLUMN preauth_hold_days SMALLINT NOT NULL DEFAULT 7 CHECK (preauth_hold_days BETWEEN 1 AND 45);

-- ---------- card: controls and limit overrides (NULL = product value) ----------
ALTER TABLE card
    ADD COLUMN atm_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN pos_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN ecom_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN daily_wd_count_limit  INT,
    ADD COLUMN daily_wd_amount_limit BIGINT,
    ADD COLUMN per_txn_wd_limit      BIGINT,
    ADD COLUMN daily_pos_count_limit  INT,
    ADD COLUMN daily_pos_amount_limit BIGINT,
    ADD COLUMN per_txn_pos_limit      BIGINT,
    ADD COLUMN last_txn_at        TIMESTAMPTZ;

ALTER TABLE card_daily_usage
    ADD COLUMN pos_count  INT    NOT NULL DEFAULT 0,
    ADD COLUMN pos_amount BIGINT NOT NULL DEFAULT 0;

-- ---------- transactions ----------
ALTER TABLE iso_transaction DROP CONSTRAINT iso_transaction_txn_type_check;
ALTER TABLE iso_transaction ADD CONSTRAINT iso_transaction_txn_type_check CHECK (txn_type IN
    ('BALANCE_INQUIRY','WITHDRAWAL','PURCHASE','PREAUTH','COMPLETION','REFUND',
     'PIN_CHANGE','REVERSAL','NETWORK'));
ALTER TABLE iso_transaction
    ADD COLUMN channel          VARCHAR(8) CHECK (channel IN ('ATM','POS','ECOM','OTHER')),
    ADD COLUMN is_advice        BOOLEAN NOT NULL DEFAULT FALSE,     -- stand-in advice: post, never decline
    ADD COLUMN fee_amount       BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN ledger_after     BIGINT,
    ADD COLUMN available_after  BIGINT,
    ADD COLUMN merchant_type    CHAR(4),                            -- field 26 / MCC
    ADD COLUMN card_acceptor    VARCHAR(40),                        -- field 43 name/location
    ADD COLUMN original_key     VARCHAR(64),                        -- reversal/completion: original MTI+STAN+time+acquirer
    ADD COLUMN journal_id       UUID;
ALTER TABLE iso_transaction ALTER COLUMN decline_reason TYPE VARCHAR(160);
CREATE INDEX ix_txn_account_time ON iso_transaction(account_id, received_at);
CREATE INDEX ix_txn_time         ON iso_transaction(received_at);

-- ---------- journals ----------
CREATE TABLE journal (
    id            UUID        PRIMARY KEY,
    entry_type    VARCHAR(20) NOT NULL CHECK (entry_type IN
                   ('WITHDRAWAL','PURCHASE','COMPLETION','REFUND','FEE','REVERSAL',
                    'FUNDING','DEBIT_ADJUSTMENT','CREDIT_ADJUSTMENT')),
    account_id    BIGINT      REFERENCES account(id),
    amount        BIGINT      NOT NULL,        -- signed effect on the account, minor units
    currency_code CHAR(3)     NOT NULL REFERENCES currency(code),
    narrative     VARCHAR(128),
    iso_txn_id    BIGINT      REFERENCES iso_transaction(id),
    reverses      UUID        REFERENCES journal(id),
    balance_after BIGINT,                      -- account ledger balance after this entry (statements)
    created_by    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_journal_account ON journal(account_id, created_at);
ALTER TABLE posting ADD CONSTRAINT fk_posting_journal FOREIGN KEY (journal_id) REFERENCES journal(id);

-- ---------- holds (pre-authorisations) ----------
ALTER TABLE hold DROP CONSTRAINT hold_status_check;
ALTER TABLE hold ADD CONSTRAINT hold_status_check CHECK (status IN ('OPEN','RELEASED','CAPTURED','EXPIRED'));
ALTER TABLE hold
    ADD COLUMN card_id      BIGINT REFERENCES card(id),
    ADD COLUMN auth_id      CHAR(6),
    ADD COLUMN captured     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN closed_at    TIMESTAMPTZ,
    ADD COLUMN closed_by    VARCHAR(64),
    ADD COLUMN close_reason VARCHAR(64);

-- ---------- GL ----------
ALTER TABLE gl_account
    ADD COLUMN gl_type VARCHAR(12) NOT NULL DEFAULT 'ASSET'
               CHECK (gl_type IN ('ASSET','LIABILITY','INCOME','EXPENSE','SUSPENSE'));
UPDATE gl_account SET gl_type = 'SUSPENSE' WHERE code LIKE 'TOPUP_SUSPENSE_%';
-- standard GLs for every existing currency; new currencies get theirs on first use
INSERT INTO gl_account (code, name, currency_code, gl_type)
SELECT p.purpose || '_' || c.code, p.name || ' ' || c.code, c.code, p.gl_type
  FROM currency c
 CROSS JOIN (VALUES ('ATM_CASH',       'ATM cash dispensed (own network)', 'ASSET'),
                    ('POS_SETTLEMENT', 'Merchant settlement (on-us POS)',  'LIABILITY'),
                    ('FEE_INCOME',     'Card fee income',                  'INCOME'),
                    ('TOPUP_SUSPENSE', 'Account funding suspense',         'SUSPENSE'),
                    ('ADJUSTMENT',     'Manual adjustments',               'SUSPENSE')) AS p(purpose, name, gl_type)
ON CONFLICT (code) DO NOTHING;

-- sequence for 6-digit authorisation ids (field 38)
CREATE SEQUENCE auth_id_seq START WITH 1 MAXVALUE 999999 CYCLE;

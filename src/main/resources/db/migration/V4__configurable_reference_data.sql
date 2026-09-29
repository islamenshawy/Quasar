-- =====================================================================
-- V4: configurable reference data and maintenance (CMS-070)
--   - currencies, segments, account types editable from the admin console
--   - allowed currencies per account type
--   - number sequences for CIF and account numbers (CMS generated or core banking)
--   - status reasons / updated_by on customer and account, full account block
-- Existing behaviour is preserved: every account type stays CMS_GENERATED and
-- allows every existing currency; CIF may be typed or generated (EITHER).
-- =====================================================================

-- ---------- currency ----------
ALTER TABLE currency
    ADD COLUMN name   VARCHAR(64),
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE currency SET name = CASE code
    WHEN 'EGP' THEN 'Egyptian pound'
    WHEN 'USD' THEN 'US dollar'
    WHEN 'AED' THEN 'UAE dirham'
    ELSE code END;
ALTER TABLE currency ALTER COLUMN name SET NOT NULL;

-- ---------- customer segment ----------
ALTER TABLE customer_segment
    ADD COLUMN description VARCHAR(256);

-- ---------- number sequences ----------
-- number = prefix + zero-padded next_value (body_length digits) [+ Luhn digit over prefix+body]
CREATE TABLE number_sequence (
    code         VARCHAR(32)  PRIMARY KEY,         -- CIF, ACCOUNT, ACCOUNT_PREPAID ...
    name         VARCHAR(64)  NOT NULL,
    prefix       VARCHAR(10)  NOT NULL DEFAULT '' CHECK (prefix ~ '^[A-Z0-9]*$'),
    body_length  SMALLINT     NOT NULL CHECK (body_length BETWEEN 4 AND 18),
    next_value   BIGINT       NOT NULL DEFAULT 1 CHECK (next_value >= 0),
    check_digit  VARCHAR(8)   NOT NULL DEFAULT 'NONE' CHECK (check_digit IN ('NONE','LUHN')),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64),
    CHECK (check_digit = 'NONE' OR prefix ~ '^[0-9]*$')
);

INSERT INTO number_sequence (code, name, prefix, body_length, next_value)
VALUES ('CIF', 'Customer CIF', '', 8, 1);

-- continue account numbering from the V2 sequence so existing numbers are never reused
INSERT INTO number_sequence (code, name, prefix, body_length, next_value)
SELECT 'ACCOUNT', 'Account numbers (default)', '', 10,
       last_value + CASE WHEN is_called THEN 1 ELSE 0 END
  FROM account_number_seq;

-- ---------- settings ----------
CREATE TABLE cms_setting (
    key          VARCHAR(64)  PRIMARY KEY,
    value        VARCHAR(256) NOT NULL,
    description  VARCHAR(256),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64)
);
INSERT INTO cms_setting (key, value, description) VALUES
    ('cif.source', 'EITHER',
     'CMS_GENERATED: CMS assigns the CIF. CORE_BANKING: operator enters it. EITHER: entered, or generated when blank.');

-- ---------- account type ----------
ALTER TABLE account_type
    ADD COLUMN description       VARCHAR(256),
    ADD COLUMN number_source     VARCHAR(16) NOT NULL DEFAULT 'CMS_GENERATED'
               CHECK (number_source IN ('CMS_GENERATED','CORE_BANKING')),
    ADD COLUMN sequence_code     VARCHAR(32) NOT NULL DEFAULT 'ACCOUNT' REFERENCES number_sequence(code),
    ADD COLUMN max_per_customer  SMALLINT CHECK (max_per_customer > 0);   -- NULL = no limit

CREATE TABLE account_type_currency (
    account_type_code  VARCHAR(16) NOT NULL REFERENCES account_type(code),
    currency_code      CHAR(3)     NOT NULL REFERENCES currency(code),
    PRIMARY KEY (account_type_code, currency_code)
);
INSERT INTO account_type_currency (account_type_code, currency_code)
SELECT t.code, c.code FROM account_type t CROSS JOIN currency c;

-- ---------- customer ----------
ALTER TABLE customer
    ADD COLUMN status_reason VARCHAR(128),
    ADD COLUMN updated_at    TIMESTAMPTZ,
    ADD COLUMN updated_by    VARCHAR(64);

-- ---------- account ----------
ALTER TABLE account DROP CONSTRAINT account_status_check;
ALTER TABLE account ADD CONSTRAINT account_status_check
    CHECK (status IN ('ACTIVE','DEBIT_BLOCKED','BLOCKED','CLOSED'));
ALTER TABLE account
    ADD COLUMN status_reason VARCHAR(128),
    ADD COLUMN closed_at     TIMESTAMPTZ,
    ADD COLUMN updated_at    TIMESTAMPTZ,
    ADD COLUMN updated_by    VARCHAR(64);
CREATE INDEX ix_account_customer ON account(customer_id);

-- ---------- card product ----------
ALTER TABLE card_product
    ADD COLUMN description VARCHAR(256),
    ADD COLUMN updated_at  TIMESTAMPTZ,
    ADD COLUMN updated_by  VARCHAR(64);

-- ---------- audit ----------
CREATE INDEX ix_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX ix_audit_time   ON audit_log(created_at);

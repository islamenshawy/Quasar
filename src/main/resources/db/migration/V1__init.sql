-- =====================================================================
-- CMS core schema (PostgreSQL 15+)  -- V1
-- Money is stored as BIGINT minor units (e.g. piastres/fils). Never float.
-- PAN is never stored in clear: pan_enc (AES-GCM, app key) + pan_hash (HMAC-SHA256) for lookup.
-- PINs are never stored: only PVV + PVKI.
-- =====================================================================

-- ---------- Reference / configuration ----------

CREATE TABLE currency (
    code        CHAR(3)  PRIMARY KEY,          -- ISO 4217 alpha, e.g. EGP
    numeric_code CHAR(3) NOT NULL UNIQUE,      -- e.g. 818
    exponent    SMALLINT NOT NULL CHECK (exponent BETWEEN 0 AND 3)
);

CREATE TABLE hsm_key (
    id            BIGSERIAL PRIMARY KEY,
    key_name      VARCHAR(64)  NOT NULL,       -- e.g. ZPK_COREHOST, PVK_PRODUCT_01
    key_type      VARCHAR(16)  NOT NULL CHECK (key_type IN
                   ('ZMK','ZPK','TPK','PVK','CVK','IMK_AC','IMK_SMI','IMK_SMC')),
    key_scheme    CHAR(1)      NOT NULL,       -- payShield scheme tag: U, T, S (key block) ...
    key_under_lmk TEXT         NOT NULL,       -- cryptogram under LMK, never clear
    kcv           VARCHAR(16)  NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at    TIMESTAMPTZ,
    UNIQUE (key_name, version)
);
-- only one active version per key name
CREATE UNIQUE INDEX ux_hsm_key_active ON hsm_key (key_name) WHERE active;

CREATE TABLE card_product (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(32)  NOT NULL UNIQUE,
    name             VARCHAR(128) NOT NULL,
    bin              VARCHAR(8)   NOT NULL,     -- 6 or 8 digit BIN
    pan_length       SMALLINT     NOT NULL DEFAULT 16 CHECK (pan_length BETWEEN 13 AND 19),
    range_start      BIGINT       NOT NULL,     -- account-range portion (without BIN, without Luhn)
    range_end        BIGINT       NOT NULL,
    next_sequence    BIGINT       NOT NULL,     -- next account-range number to allocate
    service_code     CHAR(3)      NOT NULL DEFAULT '221',  -- chip, international; verify per scheme
    validity_months  SMALLINT     NOT NULL DEFAULT 36,
    chip_profile     VARCHAR(32),               -- Dexxis profile id (VSDC/M-Chip/Meeza)
    currency_code    CHAR(3)      NOT NULL REFERENCES currency(code),
    pvki             CHAR(1)      NOT NULL DEFAULT '1',
    pvk_key_name     VARCHAR(64)  NOT NULL,
    cvk_key_name     VARCHAR(64)  NOT NULL,
    imk_ac_key_name  VARCHAR(64),
    pin_try_limit    SMALLINT     NOT NULL DEFAULT 3,
    daily_wd_count   INT          NOT NULL DEFAULT 10,
    daily_wd_amount  BIGINT       NOT NULL,     -- minor units
    per_txn_wd_max   BIGINT       NOT NULL,     -- minor units
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    CHECK (next_sequence BETWEEN range_start AND range_end + 1)
);

-- ---------- Customers, accounts, cards ----------

CREATE TABLE customer (
    id            BIGSERIAL PRIMARY KEY,
    external_ref  VARCHAR(64)  UNIQUE,          -- bank CIF / national ID reference
    full_name     VARCHAR(128) NOT NULL,
    embossing_name VARCHAR(26) NOT NULL,        -- max 26 chars on card
    mobile        VARCHAR(20),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE account (
    id              BIGSERIAL PRIMARY KEY,
    account_number  VARCHAR(34) NOT NULL UNIQUE,
    customer_id     BIGINT      NOT NULL REFERENCES customer(id),
    currency_code   CHAR(3)     NOT NULL REFERENCES currency(code),
    ledger_balance  BIGINT      NOT NULL DEFAULT 0,  -- posted balance (minor units)
    held_amount     BIGINT      NOT NULL DEFAULT 0,  -- sum of open holds
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEBIT_BLOCKED','CLOSED')),
    version         BIGINT      NOT NULL DEFAULT 0,  -- optimistic locking
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (held_amount >= 0)
);
-- available = ledger_balance - held_amount (computed in app / view)

CREATE TABLE card (
    id              BIGSERIAL PRIMARY KEY,
    pan_hash        BYTEA       NOT NULL UNIQUE,     -- HMAC-SHA256(pan), lookup key
    pan_enc         BYTEA       NOT NULL,            -- AES-GCM(pan)
    pan_first6      CHAR(6)     NOT NULL,
    pan_last4       CHAR(4)     NOT NULL,
    psn             CHAR(2)     NOT NULL DEFAULT '00',
    expiry_yymm     CHAR(4)     NOT NULL,
    service_code    CHAR(3)     NOT NULL,
    product_id      BIGINT      NOT NULL REFERENCES card_product(id),
    customer_id     BIGINT      NOT NULL REFERENCES customer(id),
    account_id      BIGINT      NOT NULL REFERENCES account(id),
    embossing_name  VARCHAR(26) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_PRINT' CHECK (status IN
                     ('PENDING_PRINT','PRINTED','ACTIVE','BLOCKED','PIN_BLOCKED',
                      'LOST','STOLEN','EXPIRED','CANCELLED')),
    pvv             CHAR(4),                          -- NULL until PIN set
    pvki            CHAR(1),
    pin_tries       SMALLINT    NOT NULL DEFAULT 0,
    issue_channel   VARCHAR(16) NOT NULL DEFAULT 'KIOSK',
    issue_location  VARCHAR(32),                      -- branch / kiosk id
    dexxis_request_id VARCHAR(64) UNIQUE,             -- idempotency for CreateCard
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    printed_at      TIMESTAMPTZ,
    activated_at    TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_card_account ON card(account_id);
CREATE INDEX ix_card_customer ON card(customer_id);

CREATE TABLE card_status_history (
    id          BIGSERIAL PRIMARY KEY,
    card_id     BIGINT      NOT NULL REFERENCES card(id),
    old_status  VARCHAR(20),
    new_status  VARCHAR(20) NOT NULL,
    reason      VARCHAR(128),
    changed_by  VARCHAR(64) NOT NULL,     -- user id or SYSTEM / channel
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Transactions (ISO 8583 / BASE24) ----------

CREATE TABLE iso_transaction (
    id                 BIGSERIAL PRIMARY KEY,
    mti                CHAR(4)     NOT NULL,
    processing_code    CHAR(6)     NOT NULL,
    function_code      CHAR(3),                  -- 1993 field 24
    stan               CHAR(6)     NOT NULL,     -- field 11
    rrn                VARCHAR(12),              -- field 37
    transmission_dt    CHAR(10)    NOT NULL,     -- field 7 MMDDhhmmss
    local_dt           VARCHAR(12),              -- field 12
    acquirer_id        VARCHAR(11) NOT NULL,     -- field 32
    terminal_id        VARCHAR(16) NOT NULL,     -- field 41
    card_id            BIGINT      REFERENCES card(id),
    pan_last4          CHAR(4),
    account_id         BIGINT      REFERENCES account(id),
    txn_type           VARCHAR(20) NOT NULL CHECK (txn_type IN
                        ('BALANCE_INQUIRY','WITHDRAWAL','PIN_CHANGE','REVERSAL','NETWORK')),
    amount             BIGINT,                   -- requested, minor units
    amount_completed   BIGINT,                   -- actual dispensed (partial reversal)
    currency_code      CHAR(3),
    action_code        CHAR(3),                  -- 1993 field 39
    auth_id            CHAR(6),                  -- field 38
    decline_reason     VARCHAR(64),
    original_txn_id    BIGINT      REFERENCES iso_transaction(id), -- for reversals
    reversed           BOOLEAN     NOT NULL DEFAULT FALSE,
    emv_arqc_ok        BOOLEAN,
    raw_request_masked TEXT,                     -- PAN masked, PIN block/field 52 removed
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at       TIMESTAMPTZ,
    -- duplicate detection: same acquirer + terminal + STAN + transmission time + MTI
    UNIQUE (acquirer_id, terminal_id, stan, transmission_dt, mti)
);
CREATE INDEX ix_txn_card_time ON iso_transaction(card_id, received_at);
CREATE INDEX ix_txn_rrn ON iso_transaction(rrn);

-- ---------- Ledger (double entry) ----------

CREATE TABLE gl_account (
    code        VARCHAR(32) PRIMARY KEY,        -- e.g. ATM_CASH_EGP, TOPUP_SUSPENSE_EGP
    name        VARCHAR(128) NOT NULL,
    currency_code CHAR(3) NOT NULL REFERENCES currency(code),
    balance     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE posting (
    id              BIGSERIAL PRIMARY KEY,
    journal_id      UUID        NOT NULL,       -- groups the legs of one entry; legs must net to zero
    iso_txn_id      BIGINT      REFERENCES iso_transaction(id),
    account_id      BIGINT      REFERENCES account(id),
    gl_code         VARCHAR(32) REFERENCES gl_account(code),
    amount          BIGINT      NOT NULL,       -- signed: +credit / -debit, minor units
    currency_code   CHAR(3)     NOT NULL REFERENCES currency(code),
    narrative       VARCHAR(128),
    value_date      DATE        NOT NULL DEFAULT CURRENT_DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((account_id IS NOT NULL) <> (gl_code IS NOT NULL))   -- exactly one side
);
CREATE INDEX ix_posting_journal ON posting(journal_id);
CREATE INDEX ix_posting_account ON posting(account_id, created_at);

CREATE TABLE hold (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account(id),
    iso_txn_id  BIGINT      REFERENCES iso_transaction(id),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    status      VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RELEASED','CAPTURED')),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_hold_open ON hold(account_id) WHERE status = 'OPEN';

-- ---------- Limits usage ----------

CREATE TABLE card_daily_usage (
    card_id     BIGINT  NOT NULL REFERENCES card(id),
    usage_date  DATE    NOT NULL,
    wd_count    INT     NOT NULL DEFAULT 0,
    wd_amount   BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (card_id, usage_date)
);

-- ---------- Audit ----------

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(64) NOT NULL,           -- user, SYSTEM, DEXXIS, COREHOST
    action      VARCHAR(64) NOT NULL,           -- CREATE_CARD, ACTIVATE, BLOCK, TOPUP, KEY_CHANGE...
    entity_type VARCHAR(32),
    entity_id   BIGINT,
    details     JSONB,                          -- never PAN/PIN/keys in clear
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Seed (test) ----------

INSERT INTO currency (code, numeric_code, exponent) VALUES
    ('EGP','818',2), ('USD','840',2), ('AED','784',2);

INSERT INTO gl_account (code, name, currency_code) VALUES
    ('ATM_CASH_EGP',       'ATM cash dispensed (own network)', 'EGP'),
    ('TOPUP_SUSPENSE_EGP', 'Account funding suspense',         'EGP');

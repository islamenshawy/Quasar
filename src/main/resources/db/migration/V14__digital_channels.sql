-- CMS-115: digital channels.
--   * Tokenisation: the issuer side of a token service provider (Visa VTS / Mastercard MDES style). The TSP asks the
--     CMS to approve a token for a card (green / yellow = verify the cardholder by OTP / red), tells it when the token
--     is created and when the wallet removes it; the CMS keeps a token vault, suspends / resumes / deletes tokens
--     itself (card events, operators, the cardholder) and tells the TSP through an outbox. Token payments carry the
--     token in field 48 (provisional) and are checked against the vault.
--   * 3-D Secure: decisions for the bank's ACS (frictionless / challenge by OTP / reject) and the authentication value
--     (CAVV) the ACS returns to the merchant, generated and later verified with the product's CAVV key by the HSM.
--   * Cardholder app API: cards, freeze, controls (incl. international use), transactions, card details and PIN
--     behind a one-time password, activation, lost / stolen, tokens.

ALTER TABLE card
    ADD COLUMN frozen                BOOLEAN NOT NULL DEFAULT FALSE,   -- temporary lock set by the cardholder
    ADD COLUMN international_enabled BOOLEAN NOT NULL DEFAULT TRUE;    -- transactions outside the institution country

ALTER TABLE card_product
    ADD COLUMN token_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN token_max_per_card   INT     NOT NULL DEFAULT 5 CHECK (token_max_per_card BETWEEN 1 AND 20),
    ADD COLUMN tds_enabled          BOOLEAN NOT NULL DEFAULT FALSE,    -- the bank's ACS authenticates this product
    ADD COLUMN tds_required         BOOLEAN NOT NULL DEFAULT FALSE,    -- e-commerce without a valid CAVV is declined
    ADD COLUMN tds_frictionless_max BIGINT CHECK (tds_frictionless_max >= 0),  -- low risk up to this amount: no challenge
    ADD COLUMN cavv_key_name        VARCHAR(64);                       -- CVK-type key that makes the CAVV

CREATE TABLE card_token (
    id                 BIGSERIAL    PRIMARY KEY,
    token_ref          VARCHAR(64)  NOT NULL UNIQUE,                  -- the TSP's token reference (not the token number)
    card_id            BIGINT       NOT NULL REFERENCES card(id),
    token_hash         BYTEA        UNIQUE,                           -- HMAC of the token number, once the TSP sends it
    token_last4        CHAR(4),
    token_expiry       CHAR(4),                                       -- YYMM
    token_requestor_id VARCHAR(11)  NOT NULL,                         -- wallet / merchant that holds the token
    wallet             VARCHAR(32)  NOT NULL,
    device_type        VARCHAR(16),
    device_name        VARCHAR(64),
    decision           VARCHAR(8)   NOT NULL CHECK (decision IN ('GREEN','YELLOW','RED')),
    decision_reasons   VARCHAR(200),
    otp_id             UUID         REFERENCES otp(id),               -- yellow path: the cardholder's code
    status             VARCHAR(10)  NOT NULL
                       CHECK (status IN ('REQUESTED','INACTIVE','ACTIVE','SUSPENDED','DELETED','DECLINED')),
    status_reason      VARCHAR(64),                                   -- e.g. CARD_BLOCKED: resumed when the card is
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         VARCHAR(64),
    last_used_at       TIMESTAMPTZ
);
CREATE INDEX ix_card_token_card ON card_token (card_id);

-- CMS -> TSP lifecycle messages (outbox; sent by the dispatcher, retried with backoff)
CREATE TABLE token_event (
    id              BIGSERIAL    PRIMARY KEY,
    token_id        BIGINT       NOT NULL REFERENCES card_token(id),
    action          VARCHAR(12)  NOT NULL CHECK (action IN ('SUSPEND','RESUME','DELETE','UPDATE_CARD')),
    reason          VARCHAR(64),
    status          VARCHAR(8)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(200),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_token_event_due ON token_event (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE tds_authentication (
    id               UUID         PRIMARY KEY,
    card_id          BIGINT       NOT NULL REFERENCES card(id),
    acs_trans_id     VARCHAR(64)  NOT NULL,
    amount           BIGINT       NOT NULL,
    currency_code    CHAR(3)      NOT NULL,
    merchant         VARCHAR(64),
    mcc              CHAR(4),
    merchant_country CHAR(3),
    device_channel   VARCHAR(8),                                      -- BROWSER, APP
    risk_score       INT          NOT NULL,
    risk_reasons     VARCHAR(200),
    trans_status     CHAR(1)      NOT NULL CHECK (trans_status IN ('Y','N','C','R')),  -- EMV 3DS transStatus
    outcome          VARCHAR(16)  NOT NULL
                     CHECK (outcome IN ('FRICTIONLESS','CHALLENGE','AUTHENTICATED','FAILED','REJECTED')),
    eci              CHAR(2),
    cavv_ref         CHAR(26)     UNIQUE,                             -- authentication id inside the CAVV
    otp_id           UUID         REFERENCES otp(id),
    used_txn_id      BIGINT       REFERENCES iso_transaction(id),     -- a CAVV pays once
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);
CREATE INDEX ix_tds_card ON tds_authentication (card_id, created_at DESC);

ALTER TABLE iso_transaction
    ADD COLUMN token_id    BIGINT REFERENCES card_token(id),
    ADD COLUMN eci         CHAR(2),
    ADD COLUMN tds_auth_id UUID REFERENCES tds_authentication(id);

-- a verified code is consumed by the action it unlocks (card details, PIN, activation)
ALTER TABLE otp DROP CONSTRAINT otp_status_check;
ALTER TABLE otp ADD CONSTRAINT otp_status_check CHECK (status IN ('ACTIVE','VERIFIED','EXPIRED','LOCKED','USED'));

ALTER TABLE notification_template DROP CONSTRAINT notification_template_event_check;
ALTER TABLE notification_template ADD CONSTRAINT notification_template_event_check
    CHECK (event IN ('TXN_APPROVED','TXN_DECLINED','CARD_ISSUED','CARD_ACTIVATED','CARD_STATUS','FRAUD_ALERT','OTP',
                     'TOKEN_ADDED'));
INSERT INTO notification_template (event, channel, language, subject, body) VALUES
 ('TOKEN_ADDED', 'SMS', 'EN', NULL, 'Your card {{pan}} was added to {{wallet}} on {{device}}. Not you? Call us now.'),
 ('TOKEN_ADDED', 'SMS', 'AR', NULL, 'تمت إضافة بطاقتك {{pan}} إلى {{wallet}} على {{device}}. إن لم تكن أنت فاتصل بنا فوراً.');

INSERT INTO approval_policy (action, description, required) VALUES
    ('TOKEN_LIFECYCLE', 'Suspend, resume or delete a card''s wallet token', FALSE);

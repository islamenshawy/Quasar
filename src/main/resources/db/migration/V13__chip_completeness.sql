-- CMS-110: chip completeness.
--   * Issuer scripts: commands for the card's chip (PIN unblock, application block / unblock, offline limit),
--     MAC'd by the HSM with the issuer IMK-SMI and delivered in field 55 (tag 72) on the card's next online chip
--     transaction; the card reports the result in tag 9F5B on a later one.
--   * Contactless: switch, per-transaction limit, no-PIN (CVM) limit and cumulative no-PIN limit per product,
--     switch per card. Visa CVN17 (qVSDC) cryptogram version.
--   * CVV2 for card-not-present; CVV1 accepted for magstripe reads only, iCVV for chip reads only.

ALTER TABLE card_product
    ADD COLUMN imk_smi_key_name             VARCHAR(64),
    ADD COLUMN contactless_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN contactless_txn_limit        BIGINT CHECK (contactless_txn_limit >= 0),        -- NULL = no per-tap limit
    ADD COLUMN contactless_cvm_limit        BIGINT CHECK (contactless_cvm_limit >= 0),        -- above it a PIN is needed
    ADD COLUMN contactless_cumulative_limit BIGINT CHECK (contactless_cumulative_limit >= 0), -- no-PIN total before a PIN
    ADD COLUMN verify_cvv2                  BOOLEAN NOT NULL DEFAULT FALSE;                  -- e-commerce must carry CVV2

ALTER TABLE card_product DROP CONSTRAINT card_product_emv_scheme_check;
ALTER TABLE card_product ADD CONSTRAINT card_product_emv_scheme_check
    CHECK (emv_scheme IN ('VISA_CVN10','VISA_CVN17','EMV_CSK'));

ALTER TABLE card
    ADD COLUMN contactless_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN contactless_no_cvm_total BIGINT  NOT NULL DEFAULT 0;   -- no-PIN contactless spend since the last PIN

ALTER TABLE iso_transaction
    ADD COLUMN entry_mode VARCHAR(12);                                 -- CHIP, CONTACTLESS, MAGSTRIPE, MANUAL, ECOM

CREATE TABLE issuer_script (
    id            BIGSERIAL    PRIMARY KEY,
    card_id       BIGINT       NOT NULL REFERENCES card(id),
    command       VARCHAR(24)  NOT NULL CHECK (command IN ('PIN_UNBLOCK','APPLICATION_BLOCK','APPLICATION_UNBLOCK','UPDATE_OFFLINE_LIMIT')),
    value         VARCHAR(16),                                         -- UPDATE_OFFLINE_LIMIT: new limit (count, 0-255)
    script_id     CHAR(8)      NOT NULL,                               -- tag 9F18, hex
    status        VARCHAR(10)  NOT NULL DEFAULT 'QUEUED'
                  CHECK (status IN ('QUEUED','SENT','APPLIED','FAILED','CANCELLED')),
    apdu          VARCHAR(80),                                         -- the MAC'd command, once sent (no secrets)
    reason        VARCHAR(200),
    sent_txn_id   BIGINT       REFERENCES iso_transaction(id),
    sent_at       TIMESTAMPTZ,
    result_txn_id BIGINT       REFERENCES iso_transaction(id),
    result_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)
);
CREATE INDEX ix_issuer_script_card ON issuer_script (card_id, id);
CREATE INDEX ix_issuer_script_queued ON issuer_script (card_id) WHERE status = 'QUEUED';

INSERT INTO approval_policy (action, description, required) VALUES
    ('CHIP_SCRIPT', 'Queue a command for a card''s chip (PIN unblock, block / unblock, offline limit)', FALSE);

-- CMS-090: core banking funds interface.
-- Accounts whose type has ledger_mode CORE_BANKING keep their money in core banking: the CMS asks core
-- for funds on every transaction (debit, hold, capture, credit, reverse) instead of posting its own ledger.
-- When core does not answer, the product's stand-in limit decides; postings made in stand-in (and
-- reversals that could not be delivered) wait in core_saf and are replayed by the CORE_SAF_REPLAY job.

-- stand-in (STIP) limit per transaction when core banking is unreachable; 0 = decline (911)
ALTER TABLE card_product
    ADD COLUMN core_stip_limit BIGINT NOT NULL DEFAULT 0 CHECK (core_stip_limit >= 0);

ALTER TABLE iso_transaction
    ADD COLUMN core_ref  VARCHAR(64),                       -- core banking posting / hold reference
    ADD COLUMN stand_in  BOOLEAN NOT NULL DEFAULT FALSE;    -- approved by the CMS while core was down

-- pre-authorisation holds on core accounts: the hold lives in core; the CMS keeps a shadow row
ALTER TABLE hold
    ADD COLUMN core_hold_ref VARCHAR(64);

-- store-and-forward queue to core banking (at-least-once; core de-duplicates on reference)
CREATE TABLE core_saf (
    id              BIGSERIAL    PRIMARY KEY,
    iso_txn_id      BIGINT       REFERENCES iso_transaction(id),
    account_id      BIGINT       NOT NULL REFERENCES account(id),
    operation       VARCHAR(10)  NOT NULL CHECK (operation IN ('DEBIT','CREDIT','REVERSAL','CAPTURE','RELEASE','HOLD')),
    reference       VARCHAR(64)  NOT NULL UNIQUE,
    payload         JSONB        NOT NULL,                  -- never PAN / PIN
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','SENT','FAILED','CANCELLED')),
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(200),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    closed_by       VARCHAR(64)
);
CREATE INDEX ix_core_saf_due ON core_saf (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_core_saf_account ON core_saf (account_id);

INSERT INTO batch_job (code, name, description, cron) VALUES
    ('CORE_SAF_REPLAY', 'Core banking store-and-forward',
     'Sends postings queued while core banking was unavailable (stand-in approvals, reversals, advices)', '0 * * * * *');

INSERT INTO approval_policy (action, description, required) VALUES
    ('CORE_SAF_CANCEL', 'Cancel a queued core banking posting (it will never reach core)', TRUE);

-- =====================================================================
-- V7: card replacement / renewal and the batch scheduler (CMS-080 .. CMS-083)
-- =====================================================================

-- ---------- card: replacement chain; the same PAN may now exist with different PSNs ----------
ALTER TABLE card
    ADD COLUMN replaces_card_id   BIGINT REFERENCES card(id),
    ADD COLUMN replacement_reason VARCHAR(16)
               CHECK (replacement_reason IN ('RENEWAL','DAMAGED','LOST','STOLEN','NOT_RECEIVED','OTHER'));
ALTER TABLE card DROP CONSTRAINT card_pan_hash_key;
ALTER TABLE card ADD CONSTRAINT card_pan_psn_key UNIQUE (pan_hash, psn);
CREATE INDEX ix_card_pan_hash ON card(pan_hash);
CREATE INDEX ix_card_replaces ON card(replaces_card_id);
CREATE INDEX ix_card_expiry_live ON card(expiry_yymm)
    WHERE status IN ('PENDING_PRINT','PRINTED','ACTIVE','BLOCKED','PIN_BLOCKED');

-- ---------- product renewal settings ----------
ALTER TABLE card_product
    ADD COLUMN auto_renew             BOOLEAN  NOT NULL DEFAULT TRUE,
    ADD COLUMN renewal_lead_days      SMALLINT NOT NULL DEFAULT 30 CHECK (renewal_lead_days BETWEEN 1 AND 180),
    ADD COLUMN renew_same_pan         BOOLEAN  NOT NULL DEFAULT TRUE,
    ADD COLUMN pending_print_max_days SMALLINT NOT NULL DEFAULT 30 CHECK (pending_print_max_days BETWEEN 1 AND 365);

-- ---------- maker-checker ----------
INSERT INTO approval_policy (action, description, required) VALUES
    ('CARD_REPLACE', 'Replace or renew a card', FALSE),
    ('BATCH_JOB_UPDATE', 'Change a batch job schedule or switch it on/off', TRUE);

-- ---------- batch scheduler ----------
CREATE TABLE batch_job (
    code         VARCHAR(32)  PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(256) NOT NULL,
    cron         VARCHAR(64)  NOT NULL,                 -- Spring cron: sec min hour day month weekday
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    running_since TIMESTAMPTZ,                          -- set while a run holds the job (single run at a time)
    last_run_id  BIGINT,
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(64)
);

CREATE TABLE batch_run (
    id           BIGSERIAL    PRIMARY KEY,
    job_code     VARCHAR(32)  NOT NULL REFERENCES batch_job(code),
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    status       VARCHAR(12)  NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    items        INT          NOT NULL DEFAULT 0,
    message      VARCHAR(512),
    triggered_by VARCHAR(64)  NOT NULL                  -- SCHEDULER or a username
);
CREATE INDEX ix_batch_run_job ON batch_run(job_code, id DESC);

INSERT INTO batch_job (code, name, description, cron) VALUES
    ('CARD_EXPIRY',        'Card expiry',          'Cards past their expiry month become EXPIRED',                                   '0 30 0 * * *'),
    ('CARD_RENEWAL',       'Card renewal',         'Creates renewal cards for live cards expiring within the product''s lead days', '0 0 1 * * *'),
    ('HOLD_EXPIRY',        'Hold expiry',          'Releases pre-authorisation holds past their expiry',                            '0 15 * * * *'),
    ('STALE_PENDING_PRINT','Uncollected prints',   'Cancels cards left PENDING_PRINT longer than the product allows',               '0 45 1 * * *'),
    ('USAGE_CLEANUP',      'Usage counter cleanup','Deletes daily usage counters older than 90 days',                               '0 0 3 * * SUN');

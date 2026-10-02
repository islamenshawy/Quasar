-- =====================================================================
-- V6: operator authentication, roles and maker-checker (CMS-060)
-- Users are created at start-up (bootstrap admin), never by migration: no credentials in SQL.
-- =====================================================================

CREATE TABLE app_user (
    id                   BIGSERIAL    PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    full_name            VARCHAR(128) NOT NULL,
    email                VARCHAR(128),
    password_hash        VARCHAR(100) NOT NULL,          -- BCrypt
    status               VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins        INT          NOT NULL DEFAULT 0,
    last_login_at        TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)  NOT NULL,
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(64)
);

-- ADMIN users and approval policy | SUPERVISOR setup and approvals | OPERATOR day-to-day | VIEWER read only
CREATE TABLE user_role (
    user_id  BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role     VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','SUPERVISOR','OPERATOR','VIEWER')),
    PRIMARY KEY (user_id, role)
);

-- ---------- maker-checker ----------
CREATE TABLE approval_policy (
    action       VARCHAR(40)  PRIMARY KEY,
    description  VARCHAR(160) NOT NULL,
    required     BOOLEAN      NOT NULL,
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(64)
);

INSERT INTO approval_policy (action, description, required) VALUES
    ('CURRENCY_SAVE',          'Create or change a currency',                        TRUE),
    ('SEGMENT_SAVE',           'Create or change a customer segment',                TRUE),
    ('ACCOUNT_TYPE_SAVE',      'Create or change an account type',                   TRUE),
    ('NUMBER_SEQUENCE_SAVE',   'Create or change a number sequence',                 TRUE),
    ('SETTING_UPDATE',         'Change a CMS setting (e.g. CIF source)',             TRUE),
    ('PRODUCT_CREATE',         'Create a card product',                              TRUE),
    ('PRODUCT_UPDATE',         'Change a card product',                              TRUE),
    ('ELIGIBILITY_UPDATE',     'Change who may receive a card product',              TRUE),
    ('LEDGER_ENTRY',           'Funding, credit or debit adjustment on an account',  TRUE),
    ('HOLD_RELEASE',           'Release a pre-authorisation hold',                   TRUE),
    ('CARD_LIMITS',            'Change card channel controls or limit overrides',    TRUE),
    ('CUSTOMER_STATUS',        'Suspend, reactivate or close a customer',            FALSE),
    ('ACCOUNT_STATUS',         'Block, reactivate or close an account',              FALSE),
    ('CARD_STATUS',            'Block, unblock, report lost/stolen or cancel a card', FALSE),
    ('RESET_PIN_TRIES',        'Clear wrong-PIN attempts',                           FALSE);

CREATE TABLE approval_request (
    id              BIGSERIAL    PRIMARY KEY,
    action          VARCHAR(40)  NOT NULL REFERENCES approval_policy(action),
    entity_type     VARCHAR(32),
    entity_id       VARCHAR(64),
    summary         VARCHAR(256) NOT NULL,
    payload         JSONB        NOT NULL,                 -- never PAN / PIN / keys
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','FAILED')),
    maker           VARCHAR(64)  NOT NULL,
    made_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    checker         VARCHAR(64),
    checked_at      TIMESTAMPTZ,
    checker_comment VARCHAR(256),
    error           VARCHAR(256),
    CHECK (checker IS NULL OR checker <> maker)
);
CREATE INDEX ix_approval_pending ON approval_request(made_at) WHERE status = 'PENDING';
CREATE INDEX ix_approval_entity  ON approval_request(entity_type, entity_id);

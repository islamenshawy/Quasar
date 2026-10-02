-- CMS-095: real-time fraud and risk rules, alert queue and case handling.
-- Rules are evaluated for every online card transaction after the card / PIN checks and before limits and
-- funds. Each matching rule adds its score and is recorded on an alert; a DECLINE rule (or a total score at
-- or above fraud.decline_score) declines with 102 "suspected fraud"; DECLINE_BLOCK also blocks the card.
-- Advices from the switch are scored and alerted but never declined.

ALTER TABLE iso_transaction
    ADD COLUMN acquirer_country CHAR(3),          -- field 19, ISO 3166 numeric; NULL = not sent
    ADD COLUMN fraud_score      INT,
    ADD COLUMN fraud_rules      VARCHAR(200);     -- codes of the rules that matched, comma separated

-- a confirmed false positive can exempt the card from rules for a while
ALTER TABLE card
    ADD COLUMN fraud_exempt_until TIMESTAMPTZ;

CREATE TABLE fraud_rule (
    code         VARCHAR(32)  PRIMARY KEY,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(256),
    action       VARCHAR(16)  NOT NULL CHECK (action IN ('ALERT','DECLINE','DECLINE_BLOCK')),
    score        INT          NOT NULL DEFAULT 0 CHECK (score BETWEEN 0 AND 1000),
    priority     INT          NOT NULL DEFAULT 100,           -- evaluation and display order
    conditions   JSONB        NOT NULL DEFAULT '{}',          -- see FraudService.Conditions
    active       BOOLEAN      NOT NULL DEFAULT FALSE,
    hits         BIGINT       NOT NULL DEFAULT 0,
    last_hit_at  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64)
);

CREATE TABLE fraud_alert (
    id            BIGSERIAL    PRIMARY KEY,
    card_id       BIGINT       NOT NULL REFERENCES card(id),
    iso_txn_id    BIGINT       REFERENCES iso_transaction(id),
    rules         VARCHAR(200) NOT NULL,
    score         INT          NOT NULL,
    action_taken  VARCHAR(16)  NOT NULL CHECK (action_taken IN ('ALERT','DECLINE','DECLINE_BLOCK')),
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN','CONFIRMED_FRAUD','FALSE_POSITIVE','CLOSED')),
    assigned_to   VARCHAR(64),
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolved_by   VARCHAR(64)
);
CREATE INDEX ix_fraud_alert_open ON fraud_alert (created_at DESC) WHERE status = 'OPEN';
CREATE INDEX ix_fraud_alert_card ON fraud_alert (card_id);
-- velocity look-ups: recent transactions of one card
CREATE INDEX IF NOT EXISTS ix_iso_txn_card_time ON iso_transaction (card_id, received_at DESC);

INSERT INTO cms_setting (key, value, description) VALUES
    ('institution.country', '818', 'ISO 3166 numeric country of the issuer: transactions from other countries are foreign (fraud rules, fees)'),
    ('fraud.decline_score', '100', 'Total fraud score at which a transaction is declined (102) even if every matching rule only alerts');

INSERT INTO approval_policy (action, description, required) VALUES
    ('FRAUD_RULE_SAVE',     'Create or change a fraud rule',                    TRUE),
    ('FRAUD_ALERT_RESOLVE', 'Resolve a fraud alert (confirm fraud / false positive / close)', FALSE);

-- starting rule set: all INACTIVE, supervisors review the thresholds and switch them on
INSERT INTO fraud_rule (code, name, description, action, score, priority, conditions) VALUES
    ('VELOCITY_5_IN_10M', 'Burst of transactions', 'More than 5 transactions on one card within 10 minutes',
     'DECLINE', 80, 10, '{"velocityMinutes": 10, "velocityMaxCount": 5}'),
    ('DECLINES_3_IN_15M', 'Repeated declines', '3 or more declines on the card in the last 15 minutes (card testing)',
     'ALERT', 50, 20, '{"declineWindowMinutes": 15, "declineCount": 3}'),
    ('NIGHT_ATM_CASH', 'Large night-time cash', 'ATM withdrawal of 5,000.00 or more between 00:00 and 05:00',
     'ALERT', 40, 30, '{"types": ["WITHDRAWAL"], "channels": ["ATM"], "minAmount": 500000, "hourFrom": 0, "hourTo": 5}'),
    ('FOREIGN_TXN', 'Foreign transaction', 'Acquirer outside the institution country',
     'ALERT', 30, 40, '{"foreign": true}'),
    ('NEW_COUNTRY', 'First use in a new country', 'Acquirer country never seen on this card in the last 90 days',
     'ALERT', 40, 50, '{"newCountry": true}'),
    ('NEW_CARD_ECOM_HIGH', 'High online spend on a new card', 'E-commerce of 2,000.00 or more within 3 days of issue',
     'DECLINE', 70, 60, '{"channels": ["ECOM"], "minAmount": 200000, "cardAgeDaysUnder": 3}'),
    ('GAMBLING_MCC', 'Gambling merchants', 'Betting / casino merchant category 7995',
     'DECLINE', 100, 70, '{"mccIn": ["7995"]}');

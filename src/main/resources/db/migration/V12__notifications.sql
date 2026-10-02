-- CMS-105: customer notifications (SMS / e-mail) and one-time passwords.
-- Messages are written to the notification outbox in the same transaction as the event that causes them
-- (transactional outbox) and sent by the dispatcher; a rolled-back transaction never sends a message.

ALTER TABLE customer
    ADD COLUMN notify_sms      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_email    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN language        CHAR(2) NOT NULL DEFAULT 'EN' CHECK (language IN ('EN','AR')),
    ADD COLUMN alert_threshold BIGINT  NOT NULL DEFAULT 0 CHECK (alert_threshold >= 0);  -- account-currency minor units

CREATE TABLE notification_template (
    event       VARCHAR(24)   NOT NULL CHECK (event IN ('TXN_APPROVED','TXN_DECLINED','CARD_ISSUED','CARD_ACTIVATED',
                                                        'CARD_STATUS','FRAUD_ALERT','OTP')),
    channel     VARCHAR(8)    NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    language    CHAR(2)       NOT NULL CHECK (language IN ('EN','AR')),
    subject     VARCHAR(120),
    body        VARCHAR(1000) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by  VARCHAR(64),
    PRIMARY KEY (event, channel, language)
);

CREATE TABLE notification (
    id              BIGSERIAL     PRIMARY KEY,
    event           VARCHAR(24)   NOT NULL,
    channel         VARCHAR(8)    NOT NULL,
    customer_id     BIGINT        REFERENCES customer(id),
    card_id         BIGINT        REFERENCES card(id),
    iso_txn_id      BIGINT        REFERENCES iso_transaction(id),
    destination     VARCHAR(128)  NOT NULL,
    subject         VARCHAR(120),
    body            VARCHAR(1000) NOT NULL,          -- never a full PAN; OTP bodies are redacted once sent
    status          VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(200),
    provider_ref    VARCHAR(64),
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_notification_due ON notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notification_customer ON notification (customer_id, id DESC);

-- one-time passwords: only a salted hash of the code is kept
CREATE TABLE otp (
    id            UUID          PRIMARY KEY,
    card_id       BIGINT        NOT NULL REFERENCES card(id),
    purpose       VARCHAR(32)   NOT NULL,
    salt          CHAR(32)      NOT NULL,
    code_hash     CHAR(64)      NOT NULL,
    status        VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','VERIFIED','EXPIRED','LOCKED')),
    attempts      INT           NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ   NOT NULL,
    notification_id BIGINT      REFERENCES notification(id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    verified_at   TIMESTAMPTZ
);
CREATE INDEX ix_otp_card ON otp (card_id, created_at DESC);

INSERT INTO approval_policy (action, description, required) VALUES
    ('NOTIFICATION_TEMPLATE_SAVE', 'Change a customer message template', TRUE);

-- starting templates; placeholders: {{name}} {{pan}} {{amount}} {{currency}} {{merchant}} {{balance}} {{date}}
-- {{status}} {{reason}} {{code}} {{minutes}}
INSERT INTO notification_template (event, channel, language, subject, body) VALUES
 ('TXN_APPROVED',  'SMS',   'EN', NULL, 'Card {{pan}}: {{amount}} {{currency}} at {{merchant}} on {{date}}. Available {{balance}}.'),
 ('TXN_APPROVED',  'SMS',   'AR', NULL, 'بطاقة {{pan}}: {{amount}} {{currency}} لدى {{merchant}} في {{date}}. الرصيد المتاح {{balance}}.'),
 ('TXN_APPROVED',  'EMAIL', 'EN', 'Card {{pan}} used: {{amount}} {{currency}}',
  E'Dear {{name}},\n\nYour card {{pan}} was used for {{amount}} {{currency}} at {{merchant}} on {{date}}.\nAvailable balance: {{balance}}.\n\nIf this was not you, call us immediately.'),
 ('TXN_DECLINED',  'SMS',   'EN', NULL, 'Card {{pan}}: {{amount}} {{currency}} at {{merchant}} was declined ({{reason}}).'),
 ('TXN_DECLINED',  'SMS',   'AR', NULL, 'بطاقة {{pan}}: تم رفض عملية {{amount}} {{currency}} لدى {{merchant}} ({{reason}}).'),
 ('CARD_ISSUED',   'SMS',   'EN', NULL, 'Dear {{name}}, your new card {{pan}} is ready. Activate it at the kiosk with your PIN.'),
 ('CARD_ISSUED',   'SMS',   'AR', NULL, 'عزيزي {{name}}، بطاقتك الجديدة {{pan}} جاهزة. فعّلها من الجهاز الذاتي باستخدام رقمك السري.'),
 ('CARD_ACTIVATED','SMS',   'EN', NULL, 'Your card {{pan}} is now active.'),
 ('CARD_ACTIVATED','SMS',   'AR', NULL, 'تم تفعيل بطاقتك {{pan}}.'),
 ('CARD_STATUS',   'SMS',   'EN', NULL, 'Your card {{pan}} is now {{status}}. Not you? Call us.'),
 ('CARD_STATUS',   'SMS',   'AR', NULL, 'أصبحت حالة بطاقتك {{pan}}: {{status}}. إن لم تكن أنت فاتصل بنا.'),
 ('FRAUD_ALERT',   'SMS',   'EN', NULL, 'Security alert: did you make {{amount}} {{currency}} at {{merchant}} with card {{pan}}? If not, call us now.'),
 ('FRAUD_ALERT',   'SMS',   'AR', NULL, 'تنبيه أمني: هل أجريت عملية {{amount}} {{currency}} لدى {{merchant}} ببطاقة {{pan}}؟ إن لم تكن أنت فاتصل بنا فوراً.'),
 ('OTP',           'SMS',   'EN', NULL, '{{code}} is your one-time password for card {{pan}}. Valid {{minutes}} minutes. Never share it.'),
 ('OTP',           'SMS',   'AR', NULL, '{{code}} هو رمز التحقق لبطاقتك {{pan}}، صالح لمدة {{minutes}} دقائق. لا تشاركه مع أحد.');

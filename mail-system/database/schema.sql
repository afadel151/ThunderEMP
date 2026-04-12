
CREATE TABLE IF NOT EXISTS users (
    id            SERIAL        PRIMARY KEY,
    username      VARCHAR(50)   NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,
    email         VARCHAR(100)  NOT NULL UNIQUE,
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    active        BOOLEAN       DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS emails (
    id          SERIAL          PRIMARY KEY,
    sender      VARCHAR(100)    NOT NULL,
    recipient   VARCHAR(100)    NOT NULL,
    subject     VARCHAR(255)    DEFAULT '',
    body        TEXT,
    sent_at     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    is_read     BOOLEAN         DEFAULT FALSE,
    is_deleted  BOOLEAN         DEFAULT FALSE,
    folder      VARCHAR(50)     DEFAULT 'INBOX'
);


CREATE INDEX IF NOT EXISTS idx_emails_recipient ON emails(recipient);
CREATE INDEX IF NOT EXISTS idx_emails_sender    ON emails(sender);
CREATE INDEX IF NOT EXISTS idx_emails_folder    ON emails(folder);


CREATE TABLE IF NOT EXISTS mailboxes (
    id            SERIAL          PRIMARY KEY,
    user_id       INTEGER         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(100)    NOT NULL,
    uid_validity  BIGINT          DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP)::BIGINT,
    uid_next      BIGINT          DEFAULT 1,
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS subscriptions (
    id            SERIAL          PRIMARY KEY,
    user_id       INTEGER         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mailbox_name  VARCHAR(100)    NOT NULL,
    UNIQUE(user_id, mailbox_name)
);

CREATE TABLE IF NOT EXISTS imap_emails (
    id            SERIAL          PRIMARY KEY,
    mailbox_id    INTEGER         NOT NULL REFERENCES mailboxes(id) ON DELETE CASCADE,
    uid           BIGINT          NOT NULL,
    sender        VARCHAR(100),
    recipient     VARCHAR(100),
    subject       VARCHAR(255),
    body          TEXT,
    flags         VARCHAR(255)    DEFAULT '',
    internal_date BIGINT          DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP)::BIGINT,
    size          INTEGER         DEFAULT 0,
    UNIQUE(mailbox_id, uid)
);

CREATE INDEX IF NOT EXISTS idx_imap_emails_mailbox ON imap_emails(mailbox_id);
CREATE INDEX IF NOT EXISTS idx_imap_emails_uid ON imap_emails(uid);

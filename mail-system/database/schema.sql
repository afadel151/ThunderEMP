-- ══════════════════════════════════════════════════════
-- Mail System Database Schema (Étape 5)
-- ══════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS mailsystem
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE mailsystem;

-- ── Table: users ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id            INT          AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,          -- hashed with BCrypt
    email         VARCHAR(100) NOT NULL UNIQUE,
    created_at    DATETIME     DEFAULT NOW(),
    active        BOOLEAN      DEFAULT TRUE
);

-- ── Table: emails ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS emails (
    id          INT           AUTO_INCREMENT PRIMARY KEY,
    sender      VARCHAR(100)  NOT NULL,
    recipient   VARCHAR(100)  NOT NULL,
    subject     VARCHAR(255)  DEFAULT '',
    body        TEXT,
    sent_at     DATETIME      DEFAULT NOW(),
    is_read     BOOLEAN       DEFAULT FALSE,
    is_deleted  BOOLEAN       DEFAULT FALSE,
    folder      VARCHAR(50)   DEFAULT 'INBOX'     -- for IMAP folders
);

-- ── Indexes ───────────────────────────────────────────
CREATE INDEX idx_emails_recipient ON emails(recipient);
CREATE INDEX idx_emails_sender    ON emails(sender);
CREATE INDEX idx_emails_folder    ON emails(folder);

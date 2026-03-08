-- ══════════════════════════════════════════════════════
-- Stored Procedures (Étape 5)
-- ══════════════════════════════════════════════════════

USE mailsystem;

DELIMITER $$

-- ── 1. Authenticate user ──────────────────────────────
CREATE PROCEDURE authenticate_user(
    IN  p_username VARCHAR(50),
    IN  p_password_hash VARCHAR(255),
    OUT p_result   BOOLEAN
)
BEGIN
    SELECT COUNT(*) > 0 INTO p_result
    FROM users
    WHERE username = p_username
      AND password_hash = p_password_hash
      AND active = TRUE;
END$$

-- ── 2. Store email ────────────────────────────────────
CREATE PROCEDURE store_email(
    IN p_sender    VARCHAR(100),
    IN p_recipient VARCHAR(100),
    IN p_subject   VARCHAR(255),
    IN p_body      TEXT
)
BEGIN
    INSERT INTO emails (sender, recipient, subject, body)
    VALUES (p_sender, p_recipient, p_subject, p_body);
END$$

-- ── 3. Fetch emails for a user ────────────────────────
CREATE PROCEDURE fetch_emails(
    IN p_recipient VARCHAR(100)
)
BEGIN
    SELECT id, sender, recipient, subject, sent_at, is_read, folder
    FROM emails
    WHERE recipient = p_recipient
      AND is_deleted = FALSE
    ORDER BY sent_at DESC;
END$$

-- ── 4. Delete email (soft delete) ────────────────────
CREATE PROCEDURE delete_email(
    IN p_email_id INT
)
BEGIN
    UPDATE emails
    SET is_deleted = TRUE
    WHERE id = p_email_id;
END$$

-- ── 5. Update password ────────────────────────────────
CREATE PROCEDURE update_password(
    IN p_username     VARCHAR(50),
    IN p_new_password VARCHAR(255)
)
BEGIN
    UPDATE users
    SET password_hash = p_new_password
    WHERE username = p_username;
END$$

-- ── 6. Mark email as read (IMAP \Seen flag) ───────────
CREATE PROCEDURE mark_as_read(
    IN p_email_id INT,
    IN p_is_read  BOOLEAN
)
BEGIN
    UPDATE emails
    SET is_read = p_is_read
    WHERE id = p_email_id;
END$$

DELIMITER ;

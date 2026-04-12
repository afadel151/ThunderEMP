-- ══════════════════════════════════════════════════════
-- PostgreSQL Functions (Étape 5)
-- ══════════════════════════════════════════════════════

-- ── 1. Authenticate user ──────────────────────────────
CREATE OR REPLACE FUNCTION authenticate_user(
    p_username VARCHAR(50),
    p_password_hash VARCHAR(255)
) RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM users
        WHERE username = p_username
          AND password_hash = p_password_hash
          AND active = TRUE
    );
END;
$$ LANGUAGE plpgsql;

-- ── 2. Store email ────────────────────────────────────
CREATE OR REPLACE FUNCTION store_email(
    p_sender    VARCHAR(100),
    p_recipient VARCHAR(100),
    p_subject   VARCHAR(255),
    p_body      TEXT
) RETURNS INTEGER AS $$
DECLARE
    v_email_id INTEGER;
BEGIN
    INSERT INTO emails (sender, recipient, subject, body)
    VALUES (p_sender, p_recipient, p_subject, p_body)
    RETURNING id INTO v_email_id;
    RETURN v_email_id;
END;
$$ LANGUAGE plpgsql;

-- ── 3. Fetch emails for a user ────────────────────────
CREATE OR REPLACE FUNCTION fetch_emails(p_recipient VARCHAR(100))
RETURNS TABLE (
    id INTEGER,
    sender VARCHAR(100),
    recipient VARCHAR(100),
    subject VARCHAR(255),
    sent_at TIMESTAMP,
    is_read BOOLEAN,
    folder VARCHAR(50)
) AS $$
BEGIN
    RETURN QUERY
    SELECT e.id, e.sender, e.recipient, e.subject, e.sent_at, e.is_read, e.folder
    FROM emails e
    WHERE e.recipient = p_recipient
      AND e.is_deleted = FALSE
    ORDER BY e.sent_at DESC;
END;
$$ LANGUAGE plpgsql;

-- ── 4. Delete email (soft delete) ────────────────────
CREATE OR REPLACE FUNCTION delete_email(p_email_id INTEGER)
RETURNS VOID AS $$
BEGIN
    UPDATE emails
    SET is_deleted = TRUE
    WHERE id = p_email_id;
END;
$$ LANGUAGE plpgsql;

-- ── 5. Update password ────────────────────────────────
CREATE OR REPLACE FUNCTION update_password(
    p_username     VARCHAR(50),
    p_new_password VARCHAR(255)
) RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET password_hash = p_new_password
    WHERE username = p_username;
END;
$$ LANGUAGE plpgsql;

-- ── 6. Mark email as read ────────────────────────────
CREATE OR REPLACE FUNCTION mark_as_read(
    p_email_id INTEGER,
    p_is_read  BOOLEAN
) RETURNS VOID AS $$
BEGIN
    UPDATE emails
    SET is_read = p_is_read
    WHERE id = p_email_id;
END;
$$ LANGUAGE plpgsql;

-- ── 7. Create user ───────────────────────────────────
CREATE OR REPLACE FUNCTION create_user(
    p_username      VARCHAR(50),
    p_password_hash VARCHAR(255),
    p_email         VARCHAR(100)
) RETURNS BOOLEAN AS $$
BEGIN
    INSERT INTO users (username, password_hash, email)
    VALUES (p_username, p_password_hash, p_email);
    RETURN TRUE;
EXCEPTION
    WHEN unique_violation THEN
        RETURN FALSE;
END;
$$ LANGUAGE plpgsql;

-- ── 8. Check if user exists ─────────────────────────
CREATE OR REPLACE FUNCTION user_exists(p_username VARCHAR(50))
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM users WHERE username = p_username);
END;
$$ LANGUAGE plpgsql;

-- ── 9. Get user by username ─────────────────────────
CREATE OR REPLACE FUNCTION get_user(p_username VARCHAR(50))
RETURNS TABLE (
    id INTEGER,
    username VARCHAR(50),
    password_hash VARCHAR(255),
    email VARCHAR(100),
    created_at TIMESTAMP,
    active BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.username, u.password_hash, u.email, u.created_at, u.active
    FROM users u
    WHERE u.username = p_username;
END;
$$ LANGUAGE plpgsql;

-- ── 10. List all users ────────────────────────────────
CREATE OR REPLACE FUNCTION list_users()
RETURNS TABLE (
    id INTEGER,
    username VARCHAR(50),
    email VARCHAR(100),
    active BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.username, u.email, u.active
    FROM users u
    ORDER BY u.username;
END;
$$ LANGUAGE plpgsql;

-- ── 11. Set user active/inactive ────────────────────
CREATE OR REPLACE FUNCTION set_user_active(
    p_username VARCHAR(50),
    p_active BOOLEAN
) RETURNS VOID AS $$
BEGIN
    UPDATE users
    SET active = p_active
    WHERE username = p_username;
END;
$$ LANGUAGE plpgsql;

-- ── 12. Delete user permanently ───────────────────────
CREATE OR REPLACE FUNCTION delete_user_permanent(p_username VARCHAR(50))
RETURNS VOID AS $$
BEGIN
    DELETE FROM users WHERE username = p_username;
END;
$$ LANGUAGE plpgsql;

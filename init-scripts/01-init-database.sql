-- ══════════════════════════════════════════════
-- Mail System Database Initialization
-- PostgreSQL Schema and Functions
-- ══════════════════════════════════════════════

-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create emails table for mail storage
CREATE TABLE IF NOT EXISTS emails (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    message_id VARCHAR(255) UNIQUE NOT NULL,
    sender VARCHAR(255) NOT NULL,
    recipients TEXT[] NOT NULL,
    subject TEXT,
    body TEXT,
    headers JSONB,
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    folder VARCHAR(50) DEFAULT 'INBOX',
    read BOOLEAN DEFAULT false,
    deleted BOOLEAN DEFAULT false
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_emails_user_id ON emails(user_id);
CREATE INDEX IF NOT EXISTS idx_emails_message_id ON emails(message_id);
CREATE INDEX IF NOT EXISTS idx_emails_folder ON emails(folder);

-- Function to authenticate user
CREATE OR REPLACE FUNCTION authenticate_user(p_username VARCHAR, p_password_hash VARCHAR)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM users 
        WHERE username = LOWER(p_username) 
        AND password_hash = p_password_hash 
        AND active = true
    );
END;
$$ LANGUAGE plpgsql;

-- Function to create user
CREATE OR REPLACE FUNCTION create_user(p_username VARCHAR, p_password_hash VARCHAR, p_email VARCHAR)
RETURNS BOOLEAN AS $$
BEGIN
    BEGIN
        INSERT INTO users (username, password_hash, email)
        VALUES (LOWER(p_username), p_password_hash, p_email);
        RETURN true;
    EXCEPTION WHEN unique_violation THEN
        RETURN false;
    END;
END;
$$ LANGUAGE plpgsql;

-- Function to update password
CREATE OR REPLACE FUNCTION update_password(p_username VARCHAR, p_password_hash VARCHAR)
RETURNS VOID AS $$
BEGIN
    UPDATE users 
    SET password_hash = p_password_hash, updated_at = CURRENT_TIMESTAMP
    WHERE username = LOWER(p_username);
END;
$$ LANGUAGE plpgsql;

-- Function to delete user permanently
CREATE OR REPLACE FUNCTION delete_user_permanent(p_username VARCHAR)
RETURNS VOID AS $$
BEGIN
    DELETE FROM users WHERE username = LOWER(p_username);
END;
$$ LANGUAGE plpgsql;

-- Function to set user active status
CREATE OR REPLACE FUNCTION set_user_active(p_username VARCHAR, p_active BOOLEAN)
RETURNS VOID AS $$
BEGIN
    UPDATE users 
    SET active = p_active, updated_at = CURRENT_TIMESTAMP
    WHERE username = LOWER(p_username);
END;
$$ LANGUAGE plpgsql;

-- Function to check if user exists
CREATE OR REPLACE FUNCTION user_exists(p_username VARCHAR)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM users 
        WHERE username = LOWER(p_username)
    );
END;
$$ LANGUAGE plpgsql;

-- Function to get user details
CREATE OR REPLACE FUNCTION get_user(p_username VARCHAR)
RETURNS TABLE (
    id INTEGER,
    username VARCHAR,
    email VARCHAR,
    active BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT id, username, email, active
    FROM users
    WHERE username = LOWER(p_username);
END;
$$ LANGUAGE plpgsql;

-- Function to list all users
CREATE OR REPLACE FUNCTION list_users()
RETURNS TABLE (
    user_id INTEGER,
    username VARCHAR,
    email VARCHAR,
    active BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT u.id, u.username, u.email, u.active
    FROM users u
    ORDER BY u.username;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Insert a default admin user (password: admin123 hashed)
-- Note: In production, you should change this password
INSERT INTO users (username, password_hash, email, active)
VALUES (
    'admin', 
    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', -- SHA256 of 'admin123'
    'admin@emp.org',
    true
) ON CONFLICT (username) DO NOTHING;

-- Grant permissions
GRANT USAGE ON SCHEMA public TO PUBLIC;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO PUBLIC;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO PUBLIC;

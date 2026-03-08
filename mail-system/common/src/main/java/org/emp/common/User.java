package org.emp.common;

/**
 * Shared user model.
 * Used by Auth server (RMI) and all mail servers.
 */
public class User {

    private int    id;
    private String username;
    private String passwordHash;  // stored hashed
    private String email;
    private boolean active;

    public User() {}

    public User(String username, String passwordHash, String email) {
        this.username     = username;
        this.passwordHash = passwordHash;
        this.email        = email;
        this.active       = true;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public int getId()                           { return id; }
    public void setId(int id)                    { this.id = id; }

    public String getUsername()                  { return username; }
    public void setUsername(String username)     { this.username = username; }

    public String getPasswordHash()              { return passwordHash; }
    public void setPasswordHash(String hash)     { this.passwordHash = hash; }

    public String getEmail()                     { return email; }
    public void setEmail(String email)           { this.email = email; }

    public boolean isActive()                    { return active; }
    public void setActive(boolean active)        { this.active = active; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username
                + ", email=" + email + ", active=" + active + "}";
    }
}

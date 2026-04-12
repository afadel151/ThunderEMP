package org.emp.auth;

import java.io.Serializable;

/**
 * Serializable data-transfer object for user info sent over RMI.
 *
 * Intentionally does NOT contain the password hash —
 * the hash never leaves the auth-server process.
 */
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String  username;
    private String  email;
    private boolean active;

    public UserDTO() {}

    public UserDTO(String username, String email, boolean active) {
        this.username = username;
        this.email    = email;
        this.active   = active;
    }

    public String  getUsername()            { return username; }
    public void    setUsername(String u)    { this.username = u; }

    public String  getEmail()               { return email; }
    public void    setEmail(String e)       { this.email = e; }

    public boolean isActive()               { return active; }
    public void    setActive(boolean a)     { this.active = a; }

    @Override
    public String toString() {
        return username + " <" + email + "> [" + (active ? "active" : "disabled") + "]";
    }
}
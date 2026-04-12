package org.emp.common;

public  class UserDTO {
    private final int id;
    private final String username;
    private final String email;
    private final boolean active;

    public UserDTO(int id, String username, String email, boolean active) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.active = active;
    }

    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }
}
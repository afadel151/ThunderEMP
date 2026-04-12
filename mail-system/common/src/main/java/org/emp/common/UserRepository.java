package org.emp.common;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class UserRepository {

    private static final Logger log = Logger.getLogger(UserRepository.class.getName());

    public boolean authenticate(String username, String passwordHash) {
        String sql = "SELECT authenticate_user(?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ps.setString(2, passwordHash);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.severe("Authentication failed for " + username + ": " + e.getMessage());
        }
        return false;
    }

    public boolean createUser(String username, String passwordHash, String email) {
        String sql = "SELECT create_user(?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ps.setString(2, passwordHash);
            ps.setString(3, email != null ? email : username.toLowerCase() + "@emp.org");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                boolean result = rs.getBoolean(1);
                if (result) {
                    log.info("Created user: " + username);
                } else {
                    log.warning("User already exists: " + username);
                }
                return result;
            }
        } catch (SQLException e) {
            log.severe("Failed to create user " + username + ": " + e.getMessage());
        }
        return false;
    }

    public boolean updateUser(String username, String newPasswordHash, String newEmail) {
        try (Connection conn = DBConnection.getConnection()) {
            if (newPasswordHash != null && !newPasswordHash.isBlank()) {
                String sql = "SELECT update_password(?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, username.toLowerCase());
                    ps.setString(2, newPasswordHash);
                    ps.execute();
                }
            }
            if (newEmail != null && !newEmail.isBlank()) {
                String sql = "UPDATE users SET email = ? WHERE username = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, newEmail);
                    ps.setString(2, username.toLowerCase());
                    ps.executeUpdate();
                }
            }
            log.info("Updated user: " + username);
            return true;
        } catch (SQLException e) {
            log.severe("Failed to update user " + username + ": " + e.getMessage());
            return false;
        }
    }

    public boolean deleteUser(String username) {
        String sql = "SELECT delete_user_permanent(?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ps.execute();
            log.info("Deleted user: " + username);
            return true;
        } catch (SQLException e) {
            log.severe("Failed to delete user " + username + ": " + e.getMessage());
            return false;
        }
    }

    public boolean setActive(String username, boolean active) {
        String sql = "SELECT set_user_active(?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ps.setBoolean(2, active);
            ps.execute();
            log.info("Set user " + username + " active=" + active);
            return true;
        } catch (SQLException e) {
            log.severe("Failed to set active for " + username + ": " + e.getMessage());
            return false;
        }
    }

    public boolean userExists(String username) {
        String sql = "SELECT user_exists(?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.severe("userExists check failed for " + username + ": " + e.getMessage());
        }
        return false;
    }


    public UserDTO getUser(String username) {
        String sql = "SELECT * FROM get_user(?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new UserDTO(
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getBoolean("active")
                );
            }
        } catch (SQLException e) {
            log.severe("Failed to get user " + username + ": " + e.getMessage());
        }
        return null;
    }

    public List<UserDTO> listUsers() {
        List<UserDTO> users = new ArrayList<>();
        String sql = "SELECT * FROM list_users()";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(new UserDTO(
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getBoolean("active")
                ));
            }
        } catch (SQLException e) {
            log.severe("Failed to list users: " + e.getMessage());
        }
        return users;
    }

   
}

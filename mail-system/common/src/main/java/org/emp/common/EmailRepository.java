package org.emp.common;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class EmailRepository {

    private static final Logger log = Logger.getLogger(EmailRepository.class.getName());

    public int storeEmail(String sender, String recipient, String subject, String body) {
        String sql = "SELECT store_email(?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, subject != null ? subject : "");
            ps.setString(4, body);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int emailId = rs.getInt(1);
                log.info("Stored email id=" + emailId + " for " + recipient);
                return emailId;
            }
        } catch (SQLException e) {
            log.severe("Failed to store email for " + recipient + ": " + e.getMessage());
        }
        return -1;
    }

    public List<EmailDTO> fetchEmails(String recipient) {
        List<EmailDTO> emails = new ArrayList<>();
        String sql = "SELECT * FROM fetch_emails(?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                emails.add(new EmailDTO(
                    rs.getInt("id"),
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("subject"),
                    rs.getTimestamp("sent_at"),
                    rs.getBoolean("is_read"),
                    rs.getString("folder")
                ));
            }
        } catch (SQLException e) {
            log.severe("Failed to fetch emails for " + recipient + ": " + e.getMessage());
        }
        return emails;
    }

    /**
     * Soft delete an email by ID.
     */
    public boolean deleteEmail(int emailId) {
        String sql = "SELECT delete_email(?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, emailId);
            ps.execute();
            log.info("Soft deleted email id=" + emailId);
            return true;
        } catch (SQLException e) {
            log.severe("Failed to delete email " + emailId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Permanently delete an email by ID.
     */
    public boolean permanentDeleteEmail(int emailId) {
        String sql = "DELETE FROM emails WHERE id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, emailId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Permanently deleted email id=" + emailId);
                return true;
            }
        } catch (SQLException e) {
            log.severe("Failed to permanently delete email " + emailId + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Mark an email as read or unread.
     */
    public boolean markAsRead(int emailId, boolean isRead) {
        String sql = "SELECT mark_as_read(?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, emailId);
            ps.setBoolean(2, isRead);
            ps.execute();
            return true;
        } catch (SQLException e) {
            log.severe("Failed to mark email " + emailId + " as read=" + isRead + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get full email content including body.
     */
    public EmailDTO getEmail(int emailId) {
        String sql = "SELECT * FROM emails WHERE id = ? AND is_deleted = FALSE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, emailId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new EmailDTO(
                    rs.getInt("id"),
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("subject"),
                    rs.getString("body"),
                    rs.getTimestamp("sent_at"),
                    rs.getBoolean("is_read"),
                    rs.getString("folder")
                );
            }
        } catch (SQLException e) {
            log.severe("Failed to get email " + emailId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Count emails for a recipient (optionally in a specific folder).
     */
    public int countEmails(String recipient, String folder) {
        String sql = folder != null
            ? "SELECT COUNT(*) FROM emails WHERE recipient = ? AND folder = ? AND is_deleted = FALSE"
            : "SELECT COUNT(*) FROM emails WHERE recipient = ? AND is_deleted = FALSE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient);
            if (folder != null) {
                ps.setString(2, folder);
            }
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.severe("Failed to count emails for " + recipient + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * DTO for email data.
     */
    public static class EmailDTO {
        private final int id;
        private final String sender;
        private final String recipient;
        private final String subject;
        private String body; // null for list operations
        private final Timestamp sentAt;
        private final boolean isRead;
        private final String folder;

        // Constructor for list operations (without body)
        public EmailDTO(int id, String sender, String recipient, String subject,
                        Timestamp sentAt, boolean isRead, String folder) {
            this(id, sender, recipient, subject, null, sentAt, isRead, folder);
        }

        // Full constructor
        public EmailDTO(int id, String sender, String recipient, String subject,
                        String body, Timestamp sentAt, boolean isRead, String folder) {
            this.id = id;
            this.sender = sender;
            this.recipient = recipient;
            this.subject = subject;
            this.body = body;
            this.sentAt = sentAt;
            this.isRead = isRead;
            this.folder = folder;
        }

        public int getId() { return id; }
        public String getSender() { return sender; }
        public String getRecipient() { return recipient; }
        public String getSubject() { return subject; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public Timestamp getSentAt() { return sentAt; }
        public boolean isRead() { return isRead; }
        public String getFolder() { return folder; }
    }
}

package org.emp.imap.storage;

import org.emp.common.DBConnection;
import org.emp.imap.ImapMailbox;
import org.emp.imap.ImapMessage;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * MySQL-backed IMAP mail storage for Étape 5.
 *
 * Relies on the following tables (defined in database/schema.sql):
 *   users       (id, username, password_hash, ...)
 *   mailboxes   (id, user_id, name, uid_validity, uid_next)
 *   emails      (id, mailbox_id, uid, subject, sender, recipient,
 *                body, flags, internal_date, size)
 *   subscriptions (user_id, mailbox_name)
 *
 * Activate by: imapServer.setMailStorage(new DBMailStorage());
 */
public class DBMailStorage implements ImapMailStorage {

    private static final Logger log = Logger.getLogger(DBMailStorage.class.getName());

    @Override
    public ImapMailbox getMailbox(String username, String mailboxName) {
        String sql = """
                SELECT m.id, m.name, m.uid_validity, m.uid_next
                FROM mailboxes m
                JOIN users u ON m.user_id = u.id
                WHERE u.username = ? AND m.name = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new ImapMailbox(
                        rs.getString("name"),
                        rs.getLong("uid_validity"),
                        rs.getLong("uid_next"));
            }
        } catch (SQLException e) { log.severe("getMailbox: " + e.getMessage()); }
        return null;
    }

    @Override
    public List<ImapMailbox> listMailboxes(String username, String reference, String pattern) {
        List<ImapMailbox> result = new ArrayList<>();
        String sql = """
                SELECT m.name, m.uid_validity, m.uid_next
                FROM mailboxes m
                JOIN users u ON m.user_id = u.id
                WHERE u.username = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            String glob = (reference + pattern).replace("*", ".*").replace("%", "[^/]*");
            while (rs.next()) {
                String name = rs.getString("name");
                if (name.matches(glob))
                    result.add(new ImapMailbox(name, rs.getLong("uid_validity"), rs.getLong("uid_next")));
            }
        } catch (SQLException e) { log.severe("listMailboxes: " + e.getMessage()); }
        return result;
    }

    @Override
    public boolean createMailbox(String username, String mailboxName) {
        String sql = """
                INSERT INTO mailboxes (user_id, name, uid_validity, uid_next)
                VALUES ((SELECT id FROM users WHERE username = ?), ?, UNIX_TIMESTAMP(), 1)
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.severe("createMailbox: " + e.getMessage()); return false; }
    }

    @Override
    public boolean deleteMailbox(String username, String mailboxName) {
        String sql = """
                DELETE m FROM mailboxes m
                JOIN users u ON m.user_id = u.id
                WHERE u.username = ? AND m.name = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.severe("deleteMailbox: " + e.getMessage()); return false; }
    }

    @Override
    public boolean renameMailbox(String username, String oldName, String newName) {
        String sql = """
                UPDATE mailboxes m
                JOIN users u ON m.user_id = u.id
                SET m.name = ?
                WHERE u.username = ? AND m.name = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, username);
            ps.setString(3, oldName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { log.severe("renameMailbox: " + e.getMessage()); return false; }
    }

    @Override
    public void subscribe(String username, String mailboxName) {
        String sql = """
                INSERT IGNORE INTO subscriptions (user_id, mailbox_name)
                VALUES ((SELECT id FROM users WHERE username = ?), ?)
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("subscribe: " + e.getMessage()); }
    }

    @Override
    public void unsubscribe(String username, String mailboxName) {
        String sql = """
                DELETE s FROM subscriptions s
                JOIN users u ON s.user_id = u.id
                WHERE u.username = ? AND s.mailbox_name = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("unsubscribe: " + e.getMessage()); }
    }

    @Override
    public List<ImapMessage> loadMessages(String username, String mailboxName) {
        List<ImapMessage> result = new ArrayList<>();
        String sql = """
                SELECT e.uid, e.sender, e.recipient, e.subject, e.body,
                       e.flags, e.internal_date, e.size
                FROM emails e
                JOIN mailboxes m ON e.mailbox_id = m.id
                JOIN users u     ON m.user_id = u.id
                WHERE u.username = ? AND m.name = ?
                ORDER BY e.uid ASC
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long uid  = rs.getLong("uid");
                String body = buildBody(rs);
                String flagStr = rs.getString("flags");
                List<String> flags = flagStr != null && !flagStr.isBlank()
                        ? Arrays.asList(flagStr.split(","))
                        : new ArrayList<>();
                result.add(new ImapMessage(uid, body, rs.getLong("internal_date"), flags));
            }
        } catch (SQLException e) { log.severe("loadMessages: " + e.getMessage()); }
        return result;
    }

    @Override
    public long appendMessage(String username, String mailboxName,
                              String content, List<String> flags, long internalDate) {
        // Get mailbox id and next UID
        String uidSql = """
                SELECT m.id, m.uid_next FROM mailboxes m
                JOIN users u ON m.user_id = u.id
                WHERE u.username = ? AND m.name = ?
                FOR UPDATE
                """;
        String insertSql = """
                INSERT INTO emails (mailbox_id, uid, body, flags, internal_date, size)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        String updateUid = "UPDATE mailboxes SET uid_next = uid_next + 1 WHERE id = ?";

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(uidSql)) {
                ps.setString(1, username);
                ps.setString(2, mailboxName);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { conn.rollback(); return -1; }
                long mailboxId = rs.getLong("id");
                long uid       = rs.getLong("uid_next");

                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    ins.setLong  (1, mailboxId);
                    ins.setLong  (2, uid);
                    ins.setString(3, content);
                    ins.setString(4, String.join(",", flags));
                    ins.setLong  (5, internalDate);
                    ins.setLong  (6, content.length());
                    ins.executeUpdate();
                }
                try (PreparedStatement upd = conn.prepareStatement(updateUid)) {
                    upd.setLong(1, mailboxId);
                    upd.executeUpdate();
                }
                conn.commit();
                return uid;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.severe("appendMessage: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public long copyMessage(String username, String srcMailbox, long srcUid, String destMailbox) {
        // Simplified: load source, append to destination
        List<ImapMessage> msgs = loadMessages(username, srcMailbox);
        for (ImapMessage msg : msgs) {
            if (msg.getUid() == srcUid) {
                try {
                    String body = String.join("\r\n", msg.getLines());
                    return appendMessage(username, destMailbox, body,
                            new ArrayList<>(msg.getFlags()), msg.getInternalDate());
                } catch (Exception e) {
                    log.severe("copyMessage: " + e.getMessage());
                }
            }
        }
        return -1;
    }

    @Override
    public void deleteMessage(String username, String mailboxName, long uid) {
        String sql = """
                DELETE e FROM emails e
                JOIN mailboxes m ON e.mailbox_id = m.id
                JOIN users u ON m.user_id = u.id
                WHERE u.username = ? AND m.name = ? AND e.uid = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mailboxName);
            ps.setLong  (3, uid);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("deleteMessage: " + e.getMessage()); }
    }

    @Override
    public void updateFlags(String username, String mailboxName, long uid, Set<String> flags) {
        String sql = """
                UPDATE emails e
                JOIN mailboxes m ON e.mailbox_id = m.id
                JOIN users u ON m.user_id = u.id
                SET e.flags = ?
                WHERE u.username = ? AND m.name = ? AND e.uid = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.join(",", flags));
            ps.setString(2, username);
            ps.setString(3, mailboxName);
            ps.setLong  (4, uid);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("updateFlags: " + e.getMessage()); }
    }

    @Override
    public void expunge(String username, String mailboxName, boolean silent) {
        // Load then delete each \\Deleted message
        List<ImapMessage> msgs = loadMessages(username, mailboxName);
        for (ImapMessage msg : msgs) {
            if (msg.hasFlag("\\Deleted")) {
                deleteMessage(username, mailboxName, msg.getUid());
            }
        }
    }

    private String buildBody(ResultSet rs) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sender    = rs.getString("sender");
        String recipient = rs.getString("recipient");
        String subject   = rs.getString("subject");
        if (sender    != null) sb.append("From: ").append(sender).append("\r\n");
        if (recipient != null) sb.append("To: ").append(recipient).append("\r\n");
        if (subject   != null) sb.append("Subject: ").append(subject).append("\r\n");
        sb.append("\r\n");
        sb.append(rs.getString("body"));
        return sb.toString();
    }
}
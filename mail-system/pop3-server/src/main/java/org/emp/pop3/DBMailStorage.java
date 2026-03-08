// package org.emp.pop3;


// import java.sql.*;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.logging.Logger;
// /**
//  * MySQL-backed mail storage for Étape 5.
//  *
//  * Uses stored procedures defined in database/procedures.sql:
//  *   fetch_emails(recipient)  → ResultSet of messages
//  *   delete_email(email_id)   → soft-delete
//  *
//  * Activate by: pop3Server.setMailStorage(new DBMailStorage());
//  */
// public class DBMailStorage implements Pop3MailStorage {

//     private static final Logger log = Logger.getLogger(DBMailStorage.class.getName());

//     @Override
//     public List<Pop3Mail> loadMessages(String username) {
//         List<Pop3Mail> result = new ArrayList<>();
//         try (Connection conn = DBConnection.getConnection();
//              CallableStatement cs = conn.prepareCall("{CALL fetch_emails(?)}")) {

//             cs.setString(1, username);
//             ResultSet rs = cs.executeQuery();
//             while (rs.next()) {
//                 String id   = String.valueOf(rs.getInt("id"));
//                 String body = buildBody(rs);
//                 result.add(new Pop3Mail(id, body));
//             }
//         } catch (SQLException e) {
//             log.severe("DB error loading messages for " + username + ": " + e.getMessage());
//         }
//         return result;
//     }

//     @Override
//     public boolean delete(String username, Pop3Mail mail) {
//         try (Connection conn = DBConnection.getConnection();
//              CallableStatement cs = conn.prepareCall("{CALL delete_email(?)}")) {

//             cs.setInt(1, Integer.parseInt(mail.getUid()));
//             cs.execute();
//             return true;
//         } catch (SQLException e) {
//             log.severe("DB error deleting message " + mail.getUid() + ": " + e.getMessage());
//             return false;
//         }
//     }

//     /** Build a RFC 5322-style message body from a DB row. */
//     private String buildBody(ResultSet rs) throws SQLException {
//         StringBuilder sb = new StringBuilder();
//         sb.append("From: ").append(rs.getString("sender")).append("\r\n");
//         sb.append("To: ").append(rs.getString("recipient")).append("\r\n");
//         sb.append("Subject: ").append(rs.getString("subject")).append("\r\n");
//         sb.append("Date: ").append(rs.getTimestamp("sent_at")).append("\r\n");
//         sb.append("\r\n");
//         sb.append(rs.getString("body"));
//         return sb.toString();
//     }
// }
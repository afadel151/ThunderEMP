package org.emp.smtp.storage;

import org.emp.common.DBConnection;
import org.emp.common.Message;

import java.sql.*;
import java.util.logging.Logger;

/**
 * MySQL-backed mail storage — activated in Étape 5.
 *
 * Uses the stored procedure store_email() defined in database/procedures.sql.
 *
 * To activate: pass new DBMailStorage() to SmtpSession constructor
 * instead of FileMailStorage().
 */
public class DBMailStorage implements MailStorage {

    private static final Logger log = Logger.getLogger(DBMailStorage.class.getName());

    @Override
    public boolean store(Message message) {
        String[] recipients = message.getRecipient().split(",");
        boolean allOk = true;

        for (String recipient : recipients) {
            recipient = recipient.trim();
            if (recipient.isEmpty()) continue;

            try (Connection conn = DBConnection.getConnection();
                 CallableStatement cs = conn.prepareCall("{CALL store_email(?,?,?,?)}")) {

                cs.setString(1, message.getSender());
                cs.setString(2, recipient);
                cs.setString(3, message.getSubject());
                cs.setString(4, message.getBody());
                cs.execute();
                log.info("Stored email in DB for " + recipient);

            } catch (SQLException e) {
                log.severe("DB error storing email for " + recipient + ": " + e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }
}
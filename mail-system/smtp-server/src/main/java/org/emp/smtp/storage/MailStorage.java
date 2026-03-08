package org.emp.smtp.storage;

import org.emp.common.Message;

/**
 * Storage abstraction for SMTP email delivery.
 *
 * Étape 1-4 : FileMailStorage  — stores emails as .txt files
 * Étape 5   : DBMailStorage    — stores emails in MySQL via JDBC
 *
 * SmtpSession depends only on this interface, so switching storage
 * backends requires zero changes to the session/protocol logic.
 */
public interface MailStorage {

    /**
     * Persist the given message for all its recipients.
     *
     * @param message the fully built message (sender, recipients, body)
     * @return true if stored successfully for all recipients
     */
    boolean store(Message message);
}
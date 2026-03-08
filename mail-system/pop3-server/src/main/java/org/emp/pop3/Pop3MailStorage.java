package org.emp.pop3;

import java.util.List;

/**
 * Mail storage abstraction for the POP3 server.
 *
 * Étape 1-4 : FileMailStorage  — reads .txt files from mailserver/<user>/
 * Étape 5   : DBMailStorage    — calls fetch_emails() and delete_email()
 *
 * Inject via: Pop3Server.setMailStorage(impl)
 */
public interface Pop3MailStorage {

    /**
     * Load all messages for a given user into memory.
     * Called once per session on successful PASS.
     *
     * @param username the authenticated user
     * @return ordered list of Pop3Mail (index 0 = message 1, etc.)
     */
    List<Pop3Mail> loadMessages(String username);

    /**
     * Physically remove a message from storage.
     * Called during the UPDATE state (on QUIT).
     *
     * @param username the owner of the mailbox
     * @param mail     the message to delete
     * @return true if deletion succeeded
     */
    boolean delete(String username, Pop3Mail mail);
}
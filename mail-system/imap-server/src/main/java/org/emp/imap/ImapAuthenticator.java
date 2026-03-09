package org.emp.imap;

/**
 * Authentication abstraction for the IMAP server.
 *
 * Étape 1–3 : FileAuthenticator  — checks users.properties
 * Étape 4   : RMIAuthenticator   — delegates to Java RMI auth server
 * Étape 5   : DBAuthenticator    — calls authenticate_user() stored procedure
 *
 * Inject via: ImapServer.setAuthenticator(impl)
 */
public interface ImapAuthenticator {
    /**
     * @param username  the user name from LOGIN or AUTHENTICATE command
     * @param password  the plaintext password
     * @return true if credentials are valid
     */
    boolean authenticate(String username, String password);
}
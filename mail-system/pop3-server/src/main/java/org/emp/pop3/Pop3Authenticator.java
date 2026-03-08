package org.emp.pop3;

/**
 * Authentication abstraction for the POP3 server.
 *
 * Étape 1-3 : FileAuthenticator   — checks password against users.json / file
 * Étape 4   : RMIAuthenticator    — delegates to the Java RMI auth server
 * Étape 5   : DBAuthenticator     — calls authenticate_user() stored procedure
 *
 * Inject via: Pop3Server.setAuthenticator(impl)
 */
public interface Pop3Authenticator {

    /**
     * Verify that the given username + password are valid.
     *
     * @param username the mailbox name sent with USER command
     * @param password the plaintext password sent with PASS command
     * @return true if credentials are valid, false otherwise
     */
    boolean authenticate(String username, String password);
}
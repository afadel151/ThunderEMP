package org.emp.smtp;

/**
 * Authentication hook for the SMTP server (Étape 4).
 *
 * Called during MAIL FROM to verify the sender is a known user.
 *
 * Étape 1-3 : not used (null = accept all senders)
 * Étape 4   : SmtpRMIAuthenticator — delegates to Java RMI auth server
 * Étape 5   : DBSmtpAuthenticator  — calls stored procedure directly
 *
 * Inject via: SmtpServer.setAuthenticator(impl)
 */
@FunctionalInterface
public interface SmtpAuthenticator {

    /**
     * Verify that the sender email address belongs to a valid, active user.
     * The local part (e.g. "alice" from "alice@emp.org") is checked against
     * the auth server.
     *
     * @param senderEmail  full email from MAIL FROM (e.g. alice@emp.org)
     * @return true if valid sender, false to reject with 550
     */
    boolean isValidSender(String senderEmail);
}
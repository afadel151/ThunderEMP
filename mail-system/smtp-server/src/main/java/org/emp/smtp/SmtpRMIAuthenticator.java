package org.emp.smtp;

import org.emp.auth.RMIAuthenticator;

/**
 * Bridges the RMI auth server to the SMTP SmtpAuthenticator interface (Étape 4).
 *
 * Checks that the local part of the sender address is a known, active user.
 * Example: "alice@emp.org" → checks username "alice" exists in auth server.
 *
 * Usage:
 *   SmtpServer server = new SmtpServer(25);
 *   server.setAuthenticator(new SmtpRMIAuthenticator("localhost", 1099));
 *   server.start();
 */
public class SmtpRMIAuthenticator implements SmtpAuthenticator {

    private final RMIAuthenticator rmi;

    public SmtpRMIAuthenticator(String host, int port) {
        this.rmi = new RMIAuthenticator(host, port);
    }

    public SmtpRMIAuthenticator() {
        this("localhost", 1099);
    }

    @Override
    public boolean isValidSender(String senderEmail) {
        // Null reverse-path "<>" is always valid (bounce messages)
        if (senderEmail == null || senderEmail.isBlank()) return true;
        String username = senderEmail.contains("@")
                ? senderEmail.split("@")[0].toLowerCase()
                : senderEmail.toLowerCase();
        return rmi.userExists(username);
    }
}
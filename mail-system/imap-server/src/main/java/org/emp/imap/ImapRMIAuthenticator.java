package org.emp.imap;

import org.emp.auth.RMIAuthenticator;

/**
 * Bridges the RMI auth server to the IMAP ImapAuthenticator interface (Étape 4).
 *
 * Usage in ImapServerGui or main():
 *
 *   ImapServer server = new ImapServer(143);
 *   server.setAuthenticator(new ImapRMIAuthenticator("localhost", 1099));
 *   server.start();
 */
public class ImapRMIAuthenticator implements ImapAuthenticator {

    private final RMIAuthenticator rmi;

    public ImapRMIAuthenticator(String host, int port) {
        this.rmi = new RMIAuthenticator(host, port);
    }

    public ImapRMIAuthenticator() {
        this("localhost", 1099);
    }

    @Override
    public boolean authenticate(String username, String password) {
        return rmi.authenticate(username, password);
        // return true;
    }
}
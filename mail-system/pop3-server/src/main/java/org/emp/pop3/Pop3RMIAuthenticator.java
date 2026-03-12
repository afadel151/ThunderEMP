package org.emp.pop3;

import org.emp.auth.RMIAuthenticator;

/**
 * Bridges the RMI auth server to the POP3 Pop3Authenticator interface (Étape 4).
 *
 * Usage in Pop3ServerGui or main():
 *
 *   Pop3Server server = new Pop3Server(110);
 *   server.setAuthenticator(new Pop3RMIAuthenticator("localhost", 1099));
 *   server.start();
 */
public class Pop3RMIAuthenticator implements Pop3Authenticator {

    private final RMIAuthenticator rmi;

    public Pop3RMIAuthenticator(String host, int port) {
        this.rmi = new RMIAuthenticator(host, port);
    }

    public Pop3RMIAuthenticator() {
        this("localhost", 1099);
    }

    @Override
    public boolean authenticate(String username, String password) {
        return rmi.authenticate(username, password);
    }
}
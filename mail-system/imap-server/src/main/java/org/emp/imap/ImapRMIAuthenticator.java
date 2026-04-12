package org.emp.imap;

import org.emp.common.RMIAuthenticator;

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
    }
}
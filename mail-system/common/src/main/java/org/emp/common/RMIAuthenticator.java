package org.emp.common;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

import org.emp.common.AuthService;

public class RMIAuthenticator {

    private static final Logger log = Logger.getLogger(RMIAuthenticator.class.getName());

    private final String host;
    private final int    port;

    private volatile AuthService stub;   

    public RMIAuthenticator(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public RMIAuthenticator() {
        this("localhost", AuthService.RMI_PORT);
    }

    public boolean authenticate(String username, String password) {
        try {
            return getStub().authenticate(username, password);
        } catch (Exception e) {
            log.warning("RMI auth call failed — denying login for " + username
                    + ": " + e.getMessage());
            stub = null; 
            return false;
        }
    }

    public boolean userExists(String username) {
        try { return getStub().userExists(username); }
        catch (Exception e) { log.warning("RMI userExists failed: " + e.getMessage()); return false; }
    }


    private AuthService getStub() throws Exception {
        if (stub == null) {
            Registry registry = LocateRegistry.getRegistry(host, port);
            stub = (AuthService) registry.lookup(AuthService.BINDING_NAME);
            log.info("RMIAuthenticator: connected to " + host + ":" + port);
        }
        return stub;
    }
}
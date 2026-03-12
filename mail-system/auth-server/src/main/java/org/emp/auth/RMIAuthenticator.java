package org.emp.auth;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

/**
 * RMI client adapter — used by SMTP, POP3, and IMAP servers (Étape 4).
 *
 * Implements all three server authenticator interfaces via duck-typing:
 *   org.emp.pop3.Pop3Authenticator   → boolean authenticate(String, String)
 *   org.emp.imap.ImapAuthenticator   → boolean authenticate(String, String)
 *   (SMTP uses it directly via SmtpAuthenticator)
 *
 * Because all three interfaces have the same method signature, this one
 * class can be assigned to all three via casting or by implementing them.
 *
 * ── Usage ─────────────────────────────────────────────────────────────
 *
 *   RMIAuthenticator rmi = new RMIAuthenticator("localhost", 1099);
 *
 *   // POP3
 *   pop3Server.setAuthenticator(rmi::authenticate);
 *
 *   // IMAP
 *   imapServer.setAuthenticator(rmi::authenticate);
 *
 *   // SMTP
 *   smtpServer.setAuthenticator(rmi::authenticate);
 *
 * ── Resilience ────────────────────────────────────────────────────────
 *
 * If the RMI server is unreachable, authenticate() returns false and
 * logs a warning — the mail servers degrade gracefully (deny auth).
 * The stub is looked up lazily and cached; if the connection drops it
 * is re-looked up on the next call.
 */
public class RMIAuthenticator {

    private static final Logger log = Logger.getLogger(RMIAuthenticator.class.getName());

    private final String host;
    private final int    port;

    private volatile AuthService stub;   // cached RMI stub

    public RMIAuthenticator(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Default: localhost:1099 */
    public RMIAuthenticator() {
        this("localhost", AuthService.RMI_PORT);
    }

    // ── Core method ────────────────────────────────────────────────────────

    /**
     * Verify credentials via the remote RMI auth server.
     * Compatible with Pop3Authenticator, ImapAuthenticator, and SmtpAuthenticator
     * via method reference: {@code rmi::authenticate}.
     */
    public boolean authenticate(String username, String password) {
        try {
            return getStub().authenticate(username, password);
        } catch (Exception e) {
            log.warning("RMI auth call failed — denying login for " + username
                    + ": " + e.getMessage());
            stub = null;  // force re-lookup next time
            return false;
        }
    }

    // ── User management helpers (used by RMI adapters on mail servers) ─────

    public boolean userExists(String username) {
        try { return getStub().userExists(username); }
        catch (Exception e) { log.warning("RMI userExists failed: " + e.getMessage()); return false; }
    }

    // ── Stub management ────────────────────────────────────────────────────

    private AuthService getStub() throws Exception {
        if (stub == null) {
            Registry registry = LocateRegistry.getRegistry(host, port);
            stub = (AuthService) registry.lookup(AuthService.BINDING_NAME);
            log.info("RMIAuthenticator: connected to " + host + ":" + port);
        }
        return stub;
    }
}
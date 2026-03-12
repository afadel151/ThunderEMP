package org.emp.auth;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

/**
 * Entry point for the RMI Authentication Server (Étape 4).
 *
 * Starts an embedded RMI registry on port 1099 (configurable),
 * creates the AuthServiceImpl, and binds it under "AuthService".
 *
 * ── How to run ────────────────────────────────────────────────────────
 *
 *   java -jar auth-server/target/auth-server.jar
 *
 *   # Custom port and data directory:
 *   java -Drmi.port=2099 \
 *        -Dauth.data.dir=/home/fadel/mail-system/auth-server/data \
 *        -jar auth-server/target/auth-server.jar
 *
 * ── How SMTP / POP3 / IMAP connect ───────────────────────────────────
 *
 *   RMIAuthenticator auth = new RMIAuthenticator("localhost", 1099);
 *   server.setAuthenticator(auth);
 *
 * ── Admin GUI ─────────────────────────────────────────────────────────
 *
 *   java -cp auth-server/target/auth-server.jar org.emp.auth.gui.AuthAdminGui
 */
public class AuthServer {

    private static final Logger log = Logger.getLogger(AuthServer.class.getName());

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("rmi.port",
                String.valueOf(AuthService.RMI_PORT)));

        // Start embedded RMI registry (avoids needing a separate rmiregistry process)
        Registry registry = LocateRegistry.createRegistry(port);

        // Create and bind the service
        AuthServiceImpl impl = new AuthServiceImpl();
        registry.rebind(AuthService.BINDING_NAME, impl);

        log.info("╔══════════════════════════════════════════╗");
        log.info("║  RMI Auth Server started                 ║");
        log.info("║  Binding : " + AuthService.BINDING_NAME + "                  ║");
        log.info("║  Port    : " + port + "                            ║");
        log.info("╚══════════════════════════════════════════╝");

        // Keep alive
        Thread.currentThread().join();
    }
}
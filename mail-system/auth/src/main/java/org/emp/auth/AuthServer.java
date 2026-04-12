package org.emp.auth;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Logger;

import org.emp.common.AuthService;


public class AuthServer {

    private static final Logger log = Logger.getLogger(AuthServer.class.getName());

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("rmi.port",
                String.valueOf(AuthService.RMI_PORT)));
        Registry registry = LocateRegistry.createRegistry(port);
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
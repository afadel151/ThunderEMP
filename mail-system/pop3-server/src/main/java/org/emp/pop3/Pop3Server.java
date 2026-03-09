package org.emp.pop3;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * POP3 Server — RFC 1939 compliant.
 *
 * Lifecycle:
 * start() → accept loop (daemon thread) → one Pop3Session per client
 * stop() → closes ServerSocket, shuts down thread pool
 *
 * GUI hook (Étape 3):
 * server.setLogListener((actor, msg) -> textArea.append(...));
 *
 * Auth hook (Étape 4 — RMI):
 * server.setAuthenticator(rmiAuthenticatorImpl);
 *
 * Storage hook (Étape 5 — MySQL):
 * server.setMailStorage(new DBMailStorage());
 */
public class Pop3Server {

    private static final Logger log = Logger.getLogger(Pop3Server.class.getName());

    public static final int DEFAULT_PORT = 1110;
    private static final String SERVER_DOMAIN = "pop3.emp.org";
    private static final int THREAD_POOL = 50;

    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    // ── Injectable hooks for later étapes ────────────────────────────────────
    private Pop3LogListener logListener;
    private Pop3Authenticator authenticator;
    private Pop3MailStorage mailStorage;

    public Pop3Server() {
        this(DEFAULT_PORT);
    }

    public Pop3Server(int port) {
        this.port = port;
    }

    public void setLogListener(Pop3LogListener l) {
        this.logListener = l;
    }

    public void setAuthenticator(Pop3Authenticator a) {
        this.authenticator = a;
    }

    public void setMailStorage(Pop3MailStorage s) {
        this.mailStorage = s;
    }

    /** Called by GUI "Start" or main(). */
    public void start() {
        if (running)
            return;
        // Defaults if no hooks injected
        if (authenticator == null)
            authenticator = new FileAuthenticator();
        if (mailStorage == null)
            mailStorage = new FileMailStorage();

        pool = Executors.newFixedThreadPool(THREAD_POOL);
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            log("POP3 Server started on port " + port);

            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        log("Connection from " + client.getInetAddress());
                        pool.execute(new Pop3Session(client, SERVER_DOMAIN,
                                logListener, authenticator, mailStorage));
                    } catch (IOException e) {
                        if (running)
                            log("Accept error: " + e.getMessage());
                    }
                }
            }, "pop3-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            log("Failed to start POP3 server: " + e.getMessage());
        }
    }

    /** Called by GUI "Stop". */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null)
            pool.shutdown();
        log("POP3 Server stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    private void log(String msg) {
        log.info(msg);
        if (logListener != null)
            logListener.onLog("SERVER", msg);
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Pop3Server server = new Pop3Server(port);
        server.start();

        // keep JVM alive
        Thread.currentThread().join();
    }
}
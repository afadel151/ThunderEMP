package org.emp.smtp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * SMTP Server — RFC 5321 compliant.
 *
 * Changes from original:
 *  - Renamed startServer() → start() so it can be called as a standard
 *    lifecycle method from the GUI (Étape 3) and other modules.
 *  - Uses a thread pool (ExecutorService) instead of raw Thread.start(),
 *    which is safer and easier to shut down cleanly.
 *  - volatile running flag + close() method allow the GUI to stop the server.
 *  - Domain name is configurable (needed for 220 / 221 greetings per RFC 5321
 *    §4.3.1 which requires the FQDN as the first word after the reply code).
 */
public class SmtpServer {

    private static final Logger log = Logger.getLogger(SmtpServer.class.getName());

    public  static final int    DEFAULT_PORT   = 25;
    private static final String SERVER_DOMAIN  = "smtp.eoc.dz";
    private static final int    THREAD_POOL    = 50;

    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    // ── Observer hook for GUI (Étape 3) ──────────────────────────────────────
    private SmtpLogListener logListener;

    public SmtpServer() {
        this(DEFAULT_PORT);
    }

    public SmtpServer(int port) {
        this.port = port;
    }

    public void setLogListener(SmtpLogListener listener) {
        this.logListener = listener;
    }

    /** Called by GUI "Start" button or by main(). */
    public void start() {
        if (running) return;
        pool = Executors.newFixedThreadPool(THREAD_POOL);
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            log("SMTP Server started on port " + port);

            // Accept loop runs on its own thread so GUI stays responsive.
            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        log("Connection from " + client.getInetAddress());
                        pool.execute(new SmtpSession(client, SERVER_DOMAIN, logListener));
                    } catch (IOException e) {
                        if (running) log("Accept error: " + e.getMessage());
                    }
                }
            }, "smtp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            log("Failed to start SMTP server: " + e.getMessage());
        }
    }

    /** Called by GUI "Stop" button. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (pool != null) pool.shutdown();
        log("SMTP Server stopped.");
    }

    public boolean isRunning() { return running; }

    private void log(String msg) {
        log.info(msg);
        if (logListener != null) logListener.onServerLog("SERVER", msg);
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new SmtpServer(port).start();
    }
}
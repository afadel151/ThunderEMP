package org.emp.imap;

import org.emp.imap.storage.DBMailStorage;
import org.emp.imap.storage.FileMailStorage;
import org.emp.imap.storage.ImapMailStorage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

 
public class ImapServer {

    private static final Logger log = Logger.getLogger(ImapServer.class.getName());

    public static final int DEFAULT_PORT = 1143;
    public static final String SERVER_DOMAIN = "imap.emp.org";
    private static final int THREAD_POOL = 50;

    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    // ── Injectable hooks 
    private ImapLogListener logListener;
    private ImapAuthenticator authenticator;
    private ImapMailStorage mailStorage;

    public ImapServer() {
        this(DEFAULT_PORT);
    }

    public ImapServer(int port) {
        this.port = port;
    }

    public void setLogListener(ImapLogListener l) {
        this.logListener = l;
    }

    public void setAuthenticator(ImapAuthenticator a) {
        this.authenticator = a;
    }

    public void setMailStorage(ImapMailStorage s) {
        this.mailStorage = s;
    }

    // ── Start / Stop ────

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
            log("IMAP Server started on port " + port);

            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        log("Connection from " + client.getInetAddress());
                        pool.execute(new ImapSession(
                                client, SERVER_DOMAIN,
                                logListener, authenticator, mailStorage));
                    } catch (IOException e) {
                        if (running)
                            log("Accept error: " + e.getMessage());
                    }
                }
            }, "imap-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            log("Failed to start IMAP server: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null)
            pool.shutdown();
        log("IMAP Server stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    private void log(String msg) {
        log.info(msg);
        if (logListener != null)
            logListener.onLog("SERVER", msg);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        ImapServer server = new ImapServer(port);
        server.start();

        // keep JVM alive
        Thread.currentThread().join();
    }
}
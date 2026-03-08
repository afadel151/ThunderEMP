package org.emp.smtp;

/**
 * Observer interface used by SmtpServer and SmtpSession to push log events
 * to the GUI (Étape 3) or any other listener without coupling to Swing.
 *
 * Usage:
 *   server.setLogListener((actor, message) -> textArea.append(actor + " -> " + message));
 */
@FunctionalInterface
public interface SmtpLogListener {

    /**
     * Called whenever a notable SMTP event occurs.
     *
     * @param actor   "CLIENT", "SERVER", or a client identifier like "Client[127.0.0.1]"
     * @param message the log line to display
     */
    void onServerLog(String actor, String message);
}
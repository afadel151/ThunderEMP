package org.emp.imap;

/**
 * Observer interface for real-time GUI supervision panel (Étape 3).
 * Inject via ImapServer.setLogListener().
 *
 * Example (Étape 3):
 *   server.setLogListener((actor, msg) -> Platform.runLater(() ->
 *       logArea.appendText("[" + actor + "] " + msg + "\n")));
 */
@FunctionalInterface
public interface ImapLogListener {
    void onLog(String actor, String message);
}
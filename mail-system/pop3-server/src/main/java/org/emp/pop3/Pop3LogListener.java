package org.emp.pop3;

/**
 * Observer interface for GUI supervision panel (Étape 3).
 * Same pattern as SmtpLogListener.
 */
@FunctionalInterface
public interface Pop3LogListener {
    void onLog(String actor, String message);
}
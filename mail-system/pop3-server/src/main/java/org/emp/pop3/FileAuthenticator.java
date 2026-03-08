package org.emp.pop3;

import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Simple file-based authenticator for Étapes 1–3.
 *
 * Reads credentials from mailserver/users.properties:
 *   alice=secret123
 *   bob=hunter2
 *
 * In Étape 4, replace with RMIAuthenticator.
 * In Étape 5, replace with DBAuthenticator.
 */
public class FileAuthenticator implements Pop3Authenticator {

    private static final Logger log = Logger.getLogger(FileAuthenticator.class.getName());
    private static final String USERS_FILE = "mailserver/users.properties";

    @Override
    public boolean authenticate(String username, String password) {
        Properties users = new Properties();
        File f = new File(USERS_FILE);
        if (!f.exists()) {
            // Fallback: accept any user whose maildir exists
            log.warning("users.properties not found — falling back to directory check");
            return new File("mailserver/" + username).isDirectory();
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            users.load(fis);
            String stored = users.getProperty(username);
            return stored != null && stored.equals(password);
        } catch (IOException e) {
            log.severe("Cannot read users.properties: " + e.getMessage());
            return false;
        }
    }
}
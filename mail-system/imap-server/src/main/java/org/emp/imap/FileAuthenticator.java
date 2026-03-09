package org.emp.imap;

import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Simple file-based authenticator for the IMAP server (Étapes 1–3).
 *
 * Reads credentials from:  mailserver/users.properties
 *   alice=secret123
 *   bob=hunter2
 *
 * Étape 4: replace with RMIAuthenticator
 * Étape 5: replace with DBAuthenticator
 */
public class FileAuthenticator implements ImapAuthenticator {

    private static final Logger log = Logger.getLogger(FileAuthenticator.class.getName());
    private static final String USERS_FILE = "mailserver/users.properties";

    @Override
    public boolean authenticate(String username, String password) {
        File f = new File(USERS_FILE);
        if (!f.exists()) {
            log.warning("users.properties not found — falling back to directory check");
            return new File("mailserver/" + username).isDirectory();
        }
        Properties users = new Properties();
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
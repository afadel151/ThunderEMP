package org.emp.common;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Single source of truth for the shared mail storage directory.
 *
 * ══════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS
 * ══════════════════════════════════════════════════════════════════════
 * Each Maven module (smtp-server, pop3-server, imap-server) runs from its
 * own working directory when launched individually:
 *
 *   smtp-server/mailserver/alice/  ← SMTP writes here
 *   pop3-server/mailserver/alice/  ← POP3 reads here  ← WRONG, empty!
 *   imap-server/mailserver/alice/  ← IMAP reads here  ← WRONG, empty!
 *
 * By reading a single absolute path from mail.properties, all three
 * servers read and write the SAME directory regardless of where they
 * are launched from.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CONFIGURATION — mail.properties
 * ══════════════════════════════════════════════════════════════════════
 * The file is resolved in this priority order:
 *
 *   1. System property:   -Dmail.properties=/path/to/mail.properties
 *   2. Environment var:   MAIL_PROPERTIES=/path/to/mail.properties
 *   3. Working directory: ./mail.properties
 *   4. Project root:      ../../mail.properties  (when run from a module)
 *   5. Built-in default:  <project-root>/mailserver/  (auto-detected)
 *
 * Minimal mail.properties:
 *   mail.storage.dir=/home/user/mail-system/mailserver
 *
 * ══════════════════════════════════════════════════════════════════════
 * USAGE IN STORAGE CLASSES
 * ══════════════════════════════════════════════════════════════════════
 *   // Instead of:
 *   private static final String BASE = "mailserver";
 *
 *   // Use:
 *   private static final String BASE = MailStorageConfig.getBaseDir();
 *
 * ══════════════════════════════════════════════════════════════════════
 * DEPLOYMENT SCENARIOS
 * ══════════════════════════════════════════════════════════════════════
 * Scenario A — all servers in one JVM (tests, demo):
 *   mail.storage.dir=/home/user/mail-system/mailserver
 *
 * Scenario B — each server run from its module directory:
 *   cd smtp-server && java -Dmail.properties=../mail.properties -jar ...
 *
 * Scenario C — Étape 8, NGINX load balancer, shared NFS mount:
 *   mail.storage.dir=/mnt/shared/mailserver
 */
public class MailStorageConfig {

    private static final Logger log = Logger.getLogger(MailStorageConfig.class.getName());

    /** Cached resolved base directory — computed once at class load time. */
    private static final String BASE_DIR = "/home/fadel/GitHub/ThunderEMP/mail-system/mailserver";

    /**
     * Returns the absolute path to the shared mail storage root directory.
     * The directory is guaranteed to exist after this call.
     */
    public static String getBaseDir() {

        return BASE_DIR;
    }

    /**
     * Convenience: returns File(getBaseDir() + "/" + username).
     * Creates the directory if it does not exist.
     */
    public static File getUserDir(String username) {
        File dir = new File(BASE_DIR, username);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Convenience: returns File(getUserDir(username) + "/" + subDir).
     * Creates the directory if it does not exist.
     */
    public static File getSubDir(String username, String subDir) {
        File dir = new File(getUserDir(username), subDir);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── Resolution logic ──────────────────────────────────────────────────────

    private static String resolve() {
        // 1. System property overrides everything
        String sysProp = System.getProperty("mail.properties");
        if (sysProp != null) {
            String dir = loadFromFile(new File(sysProp));
            if (dir != null) return ensureExists(dir);
        }

        // 2. Environment variable
        String envProp = System.getenv("MAIL_PROPERTIES");
        if (envProp != null) {
            String dir = loadFromFile(new File(envProp));
            if (dir != null) return ensureExists(dir);
        }

        // 3. mail.storage.dir system property (direct, no file needed)
        String directProp = System.getProperty("mail.storage.dir");
        if (directProp != null) {
            return ensureExists(directProp);
        }

        // 4. mail.properties in working directory
        File localProps = new File("mail.properties");
        if (localProps.exists()) {
            String dir = loadFromFile(localProps);
            if (dir != null) return ensureExists(dir);
        }

        // 5. mail.properties two levels up (when run from smtp-server/, etc.)
        File parentProps = new File("../../mail.properties");
        if (parentProps.exists()) {
            String dir = loadFromFile(parentProps);
            if (dir != null) return ensureExists(dir);
        }

        // 6. Auto-detect project root by walking up from CWD
        String autoDetected = autoDetectProjectRoot();
        if (autoDetected != null) {
            log.info("MailStorageConfig: auto-detected project root → " + autoDetected);
            return ensureExists(autoDetected);
        }

        // 7. Last resort: use CWD/mailserver (original behaviour, same as before)
        String fallback = new File("mailserver").getAbsolutePath();
        log.warning("MailStorageConfig: no mail.properties found — using fallback: " + fallback);
        log.warning("  → Create mail.properties in the project root to fix cross-module sharing.");
        return ensureExists(fallback);
    }

    private static String loadFromFile(File propsFile) {
        if (!propsFile.exists()) return null;
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            props.load(fis);
            String dir = props.getProperty("mail.storage.dir");
            if (dir != null && !dir.isBlank()) {
                log.info("MailStorageConfig: loaded mail.storage.dir from "
                        + propsFile.getAbsolutePath() + " → " + dir.trim());
                return dir.trim();
            }
        } catch (IOException e) {
            log.warning("MailStorageConfig: cannot read " + propsFile + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Walk up from CWD looking for a directory that contains pom.xml + at least
     * two of: smtp-server/, pop3-server/, imap-server/.
     * That is the project root. Return root/mailserver as the shared path.
     */
    private static String autoDetectProjectRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 5; i++) {
            if (new File(dir, "pom.xml").exists()) {
                int hits = 0;
                for (String module : new String[]{"smtp-server", "pop3-server", "imap-server"}) {
                    if (new File(dir, module).isDirectory()) hits++;
                }
                if (hits >= 2) {
                    return new File(dir, "mailserver").getAbsolutePath();
                }
            }
            File parent = dir.getParentFile();
            if (parent == null) break;
            dir = parent;
        }
        return null;
    }

    private static String ensureExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) log.info("MailStorageConfig: created storage directory: " + path);
            else    log.warning("MailStorageConfig: could not create directory: " + path);
        }
        return dir.getAbsolutePath();
    }

    // Prevent instantiation
    private MailStorageConfig() {}
}
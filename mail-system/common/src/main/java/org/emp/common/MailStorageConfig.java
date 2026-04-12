package org.emp.common;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.logging.Logger;

public class MailStorageConfig {

    private static final Logger log = Logger.getLogger(MailStorageConfig.class.getName());

    private static final String BASE_DIR = "/home/fadel/GitHub/ThunderEMP/mail-system/mailserver";

    public static String getBaseDir() {

        return BASE_DIR;
    }

    public static File getUserDir(String username) {
        File dir = new File(BASE_DIR, username);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getSubDir(String username, String subDir) {
        File dir = new File(getUserDir(username), subDir);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String resolve() {

        String sysProp = System.getProperty("mail.properties");
        if (sysProp != null) {
            String dir = loadFromFile(new File(sysProp));
            if (dir != null) return ensureExists(dir);
        }


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
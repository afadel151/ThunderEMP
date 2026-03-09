package org.emp.pop3;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.emp.common.MailStorageConfig;

/**
 * File-based mail storage for Étapes 1–4.
 * Reads emails from mailserver/<username>/*.txt (written by SmtpServer).
 *
 * In Étape 5, replace with DBMailStorage injected via Pop3Server.setMailStorage().
 */
public class FileMailStorage implements Pop3MailStorage {

    private static final Logger log = Logger.getLogger(FileMailStorage.class.getName());
    private static final String BASE_DIR = MailStorageConfig.getBaseDir();

    @Override
    public List<Pop3Mail> loadMessages(String username) {
        List<Pop3Mail> result = new ArrayList<>();
        File userDir = new File(BASE_DIR + File.separator + username);

        if (!userDir.exists() || !userDir.isDirectory()) {
            log.info("No maildir found for: " + username);
            return result;
        }

        File[] files = userDir.listFiles(f -> f.isFile() && f.getName().endsWith(".txt"));
        if (files == null) return result;

        // Sort by filename (timestamp-based names from SmtpSession = chronological order)
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        for (File f : files) result.add(new Pop3Mail(f));
        log.info("Loaded " + result.size() + " messages for " + username);
        return result;
    }

    @Override
    public boolean delete(String username, Pop3Mail mail) {
        File userDir = new File(BASE_DIR + File.separator + username);
        File target  = new File(userDir, mail.getUid());
        if (target.exists()) {
            boolean deleted = target.delete();
            if (!deleted) log.warning("Failed to delete: " + target.getAbsolutePath());
            return deleted;
        }
        return true; // already gone — treat as success
    }
}
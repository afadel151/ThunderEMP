package org.emp.imap.storage;

import org.emp.imap.ImapMailbox;
import org.emp.imap.ImapMessage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * File-system backed IMAP mail storage for Étapes 1–4.
 *
 * Directory layout:
 *   mailserver/
 *     <username>/
 *       INBOX/                ← always exists after first login
 *         1.eml               ← message file, name = UID
 *         1.meta              ← flags + internalDate JSON-like text
 *         .uidvalidity        ← single long on one line
 *         .uidnext            ← single long on one line
 *         .subscriptions      ← list of subscribed mailbox names
 *       Sent/
 *         ...
 *
 * This layout is compatible with the SMTP FileMailStorage which writes
 * messages to  mailserver/<username>/<timestamp>.txt — those files are
 * imported into INBOX on first access.
 *
 * In Étape 5, replace entirely with DBMailStorage.
 */
public class FileMailStorage implements ImapMailStorage {

    private static final Logger log = Logger.getLogger(FileMailStorage.class.getName());
    private static final String BASE = "mailserver";

    // ── Mailbox management ────────────────────────────────────────────────────

    @Override
    public ImapMailbox getMailbox(String username, String mailboxName) {
        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) {
            // Auto-create INBOX on first access
            if (mailboxName.equalsIgnoreCase("INBOX")) {
                createMailbox(username, "INBOX");
                importSmtpMessages(username);
            } else {
                return null;
            }
        }
        long uidv = readLong(new File(dir, ".uidvalidity"), System.currentTimeMillis() / 1000);
        long uidn = readLong(new File(dir, ".uidnext"), 1L);
        return new ImapMailbox(mailboxName, uidv, uidn);
    }

    @Override
    public List<ImapMailbox> listMailboxes(String username, String reference, String pattern) {
        List<ImapMailbox> result = new ArrayList<>();
        File userRoot = userDir(username);
        if (!userRoot.exists()) return result;

        String glob = (reference + pattern).replace("*", ".*").replace("%", "[^/]*");

        File[] dirs = userRoot.listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            String name = dir.getName();
            if (name.matches(glob) || name.equalsIgnoreCase(pattern.replace("*", ".*").replace("%", ".*"))) {
                ImapMailbox mb = getMailbox(username, name);
                if (mb != null) result.add(mb);
            }
        }

        // Always include INBOX if pattern matches
        if ("*".equals(pattern) || "%".equals(pattern) || "INBOX".equalsIgnoreCase(pattern)) {
            if (result.stream().noneMatch(m -> m.getName().equalsIgnoreCase("INBOX"))) {
                ImapMailbox inbox = getMailbox(username, "INBOX");
                if (inbox != null) result.add(0, inbox);
            }
        }

        return result;
    }

    @Override
    public boolean createMailbox(String username, String mailboxName) {
        File dir = mailboxDir(username, mailboxName);
        if (dir.exists()) return false;
        if (!dir.mkdirs()) return false;

        // Initialize UIDVALIDITY (current Unix time, guaranteed unique per §2.3.1.1)
        writeLong(new File(dir, ".uidvalidity"), System.currentTimeMillis() / 1000);
        writeLong(new File(dir, ".uidnext"), 1L);
        return true;
    }

    @Override
    public boolean deleteMailbox(String username, String mailboxName) {
        if (mailboxName.equalsIgnoreCase("INBOX")) return false;
        File dir = mailboxDir(username, mailboxName);
        return deleteDir(dir);
    }

    @Override
    public boolean renameMailbox(String username, String oldName, String newName) {
        if (oldName.equalsIgnoreCase("INBOX")) {
            // Special INBOX rename: move messages to newName, leave INBOX empty
            createMailbox(username, newName);
            List<ImapMessage> msgs = loadMessages(username, "INBOX");
            for (ImapMessage msg : msgs) {
                copyMessage(username, "INBOX", msg.getUid(), newName);
                deleteMessage(username, "INBOX", msg.getUid());
            }
            return true;
        }
        File src  = mailboxDir(username, oldName);
        File dest = mailboxDir(username, newName);
        return src.renameTo(dest);
    }

    @Override
    public void subscribe(String username, String mailboxName) {
        File subFile = new File(userDir(username), ".subscriptions");
        try {
            List<String> subs = subFile.exists()
                    ? new ArrayList<>(Files.readAllLines(subFile.toPath()))
                    : new ArrayList<>();
            if (!subs.contains(mailboxName)) {
                subs.add(mailboxName);
                Files.write(subFile.toPath(), subs);
            }
        } catch (IOException e) { log.warning("subscribe failed: " + e.getMessage()); }
    }

    @Override
    public void unsubscribe(String username, String mailboxName) {
        File subFile = new File(userDir(username), ".subscriptions");
        try {
            if (!subFile.exists()) return;
            List<String> subs = new ArrayList<>(Files.readAllLines(subFile.toPath()));
            subs.remove(mailboxName);
            Files.write(subFile.toPath(), subs);
        } catch (IOException e) { log.warning("unsubscribe failed: " + e.getMessage()); }
    }

    // ── Message access ────────────────────────────────────────────────────────

    @Override
    public List<ImapMessage> loadMessages(String username, String mailboxName) {
        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) return new ArrayList<>();

        File[] emlFiles = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".eml"));
        if (emlFiles == null) return new ArrayList<>();

        // Sort by UID (filename without extension)
        Arrays.sort(emlFiles, Comparator.comparingLong(f ->
                parseLongSafe(f.getName().replace(".eml", ""))));

        List<ImapMessage> result = new ArrayList<>();
        for (File eml : emlFiles) {
            long uid  = parseLongSafe(eml.getName().replace(".eml", ""));
            long date = eml.lastModified();
            List<String> flags = readMeta(dir, uid);
            result.add(new ImapMessage(uid, eml, date, flags));
        }
        return result;
    }

    @Override
    public long appendMessage(String username, String mailboxName,
                              String content, List<String> flagList, long internalDate) {
        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) createMailbox(username, mailboxName);

        long uid = readLong(new File(dir, ".uidnext"), 1L);
        writeLong(new File(dir, ".uidnext"), uid + 1);

        File emlFile = new File(dir, uid + ".eml");
        try {
            Files.writeString(emlFile.toPath(), content);
            writeMeta(dir, uid, flagList);
        } catch (IOException e) {
            log.severe("appendMessage failed: " + e.getMessage());
        }
        return uid;
    }

    @Override
    public long copyMessage(String username, String srcMailbox, long srcUid, String destMailbox) {
        File srcDir  = mailboxDir(username, srcMailbox);
        File destDir = mailboxDir(username, destMailbox);
        if (!destDir.exists()) createMailbox(username, destMailbox);

        File srcEml  = new File(srcDir, srcUid + ".eml");
        long newUid  = readLong(new File(destDir, ".uidnext"), 1L);
        writeLong(new File(destDir, ".uidnext"), newUid + 1);

        File destEml = new File(destDir, newUid + ".eml");
        try {
            Files.copy(srcEml.toPath(), destEml.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // Copy flags
            List<String> flags = readMeta(srcDir, srcUid);
            writeMeta(destDir, newUid, flags);
        } catch (IOException e) {
            log.severe("copyMessage failed: " + e.getMessage());
        }
        return newUid;
    }

    @Override
    public void deleteMessage(String username, String mailboxName, long uid) {
        File dir  = mailboxDir(username, mailboxName);
        File eml  = new File(dir, uid + ".eml");
        File meta = new File(dir, uid + ".meta");
        eml.delete();
        meta.delete();
    }

    @Override
    public void updateFlags(String username, String mailboxName, long uid, Set<String> flags) {
        File dir = mailboxDir(username, mailboxName);
        writeMeta(dir, uid, new ArrayList<>(flags));
    }

    @Override
    public void expunge(String username, String mailboxName, boolean silent) {
        List<ImapMessage> msgs = loadMessages(username, mailboxName);
        for (ImapMessage msg : msgs) {
            if (msg.hasFlag("\\Deleted")) {
                deleteMessage(username, mailboxName, msg.getUid());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Import legacy SMTP-format .txt files into INBOX as proper .eml files. */
    private void importSmtpMessages(String username) {
        File userRoot = userDir(username);
        File[] txtFiles = userRoot.listFiles(f -> f.isFile() && f.getName().endsWith(".txt"));
        if (txtFiles == null) return;
        for (File txt : txtFiles) {
            try {
                String content = Files.readString(txt.toPath());
                appendMessage(username, "INBOX", content,
                        Collections.emptyList(), txt.lastModified());
                txt.delete(); // remove the old file
            } catch (IOException e) {
                log.warning("Failed to import SMTP message: " + e.getMessage());
            }
        }
    }

    private File userDir(String username) {
        return new File(BASE + File.separator + username);
    }

    private File mailboxDir(String username, String mailboxName) {
        return new File(userDir(username), mailboxName);
    }

    private List<String> readMeta(File dir, long uid) {
        File meta = new File(dir, uid + ".meta");
        if (!meta.exists()) return new ArrayList<>();
        try {
            return new ArrayList<>(Files.readAllLines(meta.toPath()));
        } catch (IOException e) { return new ArrayList<>(); }
    }

    private void writeMeta(File dir, long uid, List<String> flags) {
        File meta = new File(dir, uid + ".meta");
        try { Files.write(meta.toPath(), flags); }
        catch (IOException e) { log.warning("writeMeta failed: " + e.getMessage()); }
    }

    private long readLong(File f, long defaultVal) {
        if (!f.exists()) return defaultVal;
        try { return Long.parseLong(Files.readString(f.toPath()).trim()); }
        catch (Exception e) { return defaultVal; }
    }

    private void writeLong(File f, long value) {
        try { Files.writeString(f.toPath(), String.valueOf(value)); }
        catch (IOException e) { log.warning("writeLong failed: " + e.getMessage()); }
    }

    private long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private boolean deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files)
            if (f.isDirectory()) deleteDir(f); else f.delete();
        return dir.delete();
    }
}
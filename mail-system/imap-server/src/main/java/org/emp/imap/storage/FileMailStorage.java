package org.emp.imap.storage;

import org.emp.common.MailStorageConfig;
import org.emp.imap.ImapMailbox;
import org.emp.imap.ImapMessage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * File-system backed IMAP mail storage for Étapes 1–4.
 *
 * ══════════════════════════════════════════════════════════════════════
 * HOW THE THREE SERVERS SHARE FILES
 * ══════════════════════════════════════════════════════════════════════
 *
 *  SMTP writes:   mailserver/<user>/<timestamp>.txt
 *  POP3 reads:    mailserver/<user>/*.txt          ← untouched by IMAP
 *  IMAP reads:    mailserver/<user>/INBOX/*.eml
 *
 * When IMAP opens INBOX (SELECT), it calls syncSmtpMessages() which:
 *   1. Scans mailserver/<user>/*.txt for any NEW files
 *      (those not yet listed in INBOX/.imported)
 *   2. For each new file: copies content into INBOX/<uid>.eml + <uid>.meta
 *   3. Records the filename in INBOX/.imported so it is not imported twice
 *   4. Does NOT delete the .txt file — POP3 still needs it
 *
 * This way:
 *   • SMTP delivers → .txt file appears
 *   • POP3 sees it  → reads .txt directly (unchanged)
 *   • IMAP sees it  → syncs it into INBOX on next SELECT/NOOP
 *   • POP3 deletes  → .txt is gone; IMAP already has its own .eml copy
 *
 * ══════════════════════════════════════════════════════════════════════
 * File layout under MailStorageConfig.getBaseDir()
 * ══════════════════════════════════════════════════════════════════════
 *
 *   <user>/
 *     <timestamp>.txt          ← SMTP writes here; POP3 reads here
 *     INBOX/
 *       .uidvalidity           ← long, changes only if mailbox recreated
 *       .uidnext               ← next UID to assign (increments forever)
 *       .imported              ← one .txt filename per line already synced
 *       1.eml                  ← message content (RFC 5322 text)
 *       1.meta                 ← flags, one per line (e.g. \Seen)
 *       2.eml
 *       2.meta
 *     Sent/
 *       .uidvalidity
 *       .uidnext
 *     Trash/
 *       ...
 *     .subscriptions           ← subscribed mailbox names, one per line
 *
 * In Étape 5, replace entirely with DBMailStorage.
 */
public class FileMailStorage implements ImapMailStorage {

    private static final Logger log = Logger.getLogger(FileMailStorage.class.getName());

    // Shared absolute path — same across all Maven modules
    private static final String BASE = MailStorageConfig.getBaseDir();

    // ── Mailbox management ────────────────────────────────────────────────────

    @Override
    public ImapMailbox getMailbox(String username, String mailboxName) {
        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) {
            if (mailboxName.equalsIgnoreCase("INBOX")) {
                createMailbox(username, "INBOX");
            } else {
                return null;
            }
        }
        // Sync new SMTP arrivals every time INBOX metadata is read
        if (mailboxName.equalsIgnoreCase("INBOX")) {
            syncSmtpMessages(username);
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

        // Sync before listing so newly arrived messages appear in EXISTS count
        syncSmtpMessages(username);

        String glob = (reference + pattern)
                .replace(".", "\\.")   // escape literal dots
                .replace("*", ".*")
                .replace("%", "[^/]*");

        File[] dirs = userRoot.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String name = dir.getName();
                if (name.matches(glob) || pattern.equals("*") || pattern.equals("%")) {
                    ImapMailbox mb = getMailboxDirect(username, name);
                    if (mb != null) result.add(mb);
                }
            }
        }

        // INBOX is mandatory — always include it if pattern is a wildcard
        if (pattern.equals("*") || pattern.equals("%") || pattern.equalsIgnoreCase("INBOX")) {
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
        // RFC 9051 §2.3.1.1 — UIDVALIDITY must never decrease; use Unix time
        writeLong(new File(dir, ".uidvalidity"), System.currentTimeMillis() / 1000);
        writeLong(new File(dir, ".uidnext"), 1L);
        return true;
    }

    @Override
    public boolean deleteMailbox(String username, String mailboxName) {
        if (mailboxName.equalsIgnoreCase("INBOX")) return false;
        return deleteDir(mailboxDir(username, mailboxName));
    }

    @Override
    public boolean renameMailbox(String username, String oldName, String newName) {
        if (oldName.equalsIgnoreCase("INBOX")) {
            // RFC 9051 §6.3.6 — renaming INBOX moves messages to new mailbox
            createMailbox(username, newName);
            for (ImapMessage msg : loadMessages(username, "INBOX")) {
                copyMessage(username, "INBOX", msg.getUid(), newName);
                deleteMessage(username, "INBOX", msg.getUid());
            }
            return true;
        }
        return mailboxDir(username, oldName).renameTo(mailboxDir(username, newName));
    }

    @Override
    public void subscribe(String username, String mailboxName) {
        File f = new File(userDir(username), ".subscriptions");
        try {
            List<String> subs = f.exists()
                    ? new ArrayList<>(Files.readAllLines(f.toPath())) : new ArrayList<>();
            if (!subs.contains(mailboxName)) { subs.add(mailboxName); Files.write(f.toPath(), subs); }
        } catch (IOException e) { log.warning("subscribe: " + e.getMessage()); }
    }

    @Override
    public void unsubscribe(String username, String mailboxName) {
        File f = new File(userDir(username), ".subscriptions");
        try {
            if (!f.exists()) return;
            List<String> subs = new ArrayList<>(Files.readAllLines(f.toPath()));
            subs.remove(mailboxName);
            Files.write(f.toPath(), subs);
        } catch (IOException e) { log.warning("unsubscribe: " + e.getMessage()); }
    }

    // ── Message access ────────────────────────────────────────────────────────

    @Override
    public List<ImapMessage> loadMessages(String username, String mailboxName) {
        // Always sync before loading INBOX so fresh SMTP mail appears immediately
        if (mailboxName.equalsIgnoreCase("INBOX")) {
            syncSmtpMessages(username);
        }

        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) return new ArrayList<>();

        File[] emlFiles = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".eml"));
        if (emlFiles == null || emlFiles.length == 0) return new ArrayList<>();

        // Sort ascending by UID (filename is the UID number)
        Arrays.sort(emlFiles, Comparator.comparingLong(
                f -> parseLongSafe(f.getName().replace(".eml", ""))));

        List<ImapMessage> result = new ArrayList<>();
        for (File eml : emlFiles) {
            long uid   = parseLongSafe(eml.getName().replace(".eml", ""));
            long date  = eml.lastModified();
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

        try {
            Files.writeString(new File(dir, uid + ".eml").toPath(), content);
            writeMeta(dir, uid, flagList);
        } catch (IOException e) {
            log.severe("appendMessage: " + e.getMessage());
        }
        return uid;
    }

    @Override
    public long copyMessage(String username, String srcMailbox, long srcUid, String destMailbox) {
        File srcDir  = mailboxDir(username, srcMailbox);
        File destDir = mailboxDir(username, destMailbox);
        if (!destDir.exists()) createMailbox(username, destMailbox);

        long newUid = readLong(new File(destDir, ".uidnext"), 1L);
        writeLong(new File(destDir, ".uidnext"), newUid + 1);

        try {
            Files.copy(new File(srcDir, srcUid + ".eml").toPath(),
                       new File(destDir, newUid + ".eml").toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            writeMeta(destDir, newUid, readMeta(srcDir, srcUid));
        } catch (IOException e) {
            log.severe("copyMessage: " + e.getMessage());
        }
        return newUid;
    }

    @Override
    public void deleteMessage(String username, String mailboxName, long uid) {
        File dir = mailboxDir(username, mailboxName);
        new File(dir, uid + ".eml").delete();
        new File(dir, uid + ".meta").delete();
    }

    @Override
    public void updateFlags(String username, String mailboxName, long uid, Set<String> flags) {
        writeMeta(mailboxDir(username, mailboxName), uid, new ArrayList<>(flags));
    }

    @Override
    public void expunge(String username, String mailboxName, boolean silent) {
        for (ImapMessage msg : loadMessages(username, mailboxName)) {
            if (msg.hasFlag("\\Deleted"))
                deleteMessage(username, mailboxName, msg.getUid());
        }
    }

    // ── Core sync logic ───────────────────────────────────────────────────────

    /**
     * Scans the user's root directory for SMTP-written *.txt files that have
     * not yet been imported into INBOX.
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  CONTRACT:                                                          │
     * │  • We NEVER delete .txt files — POP3 still needs them              │
     * │  • Each .txt is imported exactly once (tracked in INBOX/.imported) │
     * │  • The .eml content is identical to the .txt content               │
     * │    (both are plain RFC 5322 text — no format conversion needed)    │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * Called from: getMailbox(), listMailboxes(), loadMessages() for INBOX.
     */
    private synchronized void syncSmtpMessages(String username) {
        File userRoot  = userDir(username);
        File inboxDir  = mailboxDir(username, "INBOX");

        // Ensure INBOX exists before we try to import into it
        if (!inboxDir.exists()) {
            if (!inboxDir.mkdirs()) {
                log.warning("syncSmtpMessages: cannot create INBOX for " + username);
                return;
            }
            writeLong(new File(inboxDir, ".uidvalidity"), System.currentTimeMillis() / 1000);
            writeLong(new File(inboxDir, ".uidnext"), 1L);
        }

        // Load the set of already-imported .txt filenames
        File importedFile = new File(inboxDir, ".imported");
        Set<String> alreadyImported = readImportedSet(importedFile);

        // Find all .txt files in the user root directory
        File[] txtFiles = userRoot.listFiles(
                f -> f.isFile() && f.getName().endsWith(".txt"));
        if (txtFiles == null || txtFiles.length == 0) return;

        // Sort by filename (= timestamp order = arrival order)
        Arrays.sort(txtFiles, Comparator.comparing(File::getName));

        List<String> newlyImported = new ArrayList<>();
        for (File txt : txtFiles) {
            if (alreadyImported.contains(txt.getName())) continue; // already done

            try {
                String content   = Files.readString(txt.toPath());
                long   uid       = readLong(new File(inboxDir, ".uidnext"), 1L);
                long   timestamp = txt.lastModified();

                // Write the .eml (content is identical — no conversion needed)
                Files.writeString(new File(inboxDir, uid + ".eml").toPath(), content);
                // New messages have no flags (Unseen by default per RFC 9051 §2.3.2)
                writeMeta(inboxDir, uid, Collections.emptyList());
                // Advance UIDNEXT
                writeLong(new File(inboxDir, ".uidnext"), uid + 1);

                newlyImported.add(txt.getName());
                log.info("IMAP sync: imported " + txt.getName()
                        + " → INBOX/" + uid + ".eml for " + username);

            } catch (IOException e) {
                log.warning("syncSmtpMessages: failed to import "
                        + txt.getName() + ": " + e.getMessage());
            }
        }

        // Persist the updated imported set
        if (!newlyImported.isEmpty()) {
            alreadyImported.addAll(newlyImported);
            writeImportedSet(importedFile, alreadyImported);
        }
    }

    /** Read the .imported tracking file into a Set. */
    private Set<String> readImportedSet(File f) {
        if (!f.exists()) return new LinkedHashSet<>();
        try {
            return new LinkedHashSet<>(Files.readAllLines(f.toPath()));
        } catch (IOException e) {
            log.warning("readImportedSet: " + e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    /** Write the set of imported filenames back to disk. */
    private void writeImportedSet(File f, Set<String> names) {
        try { Files.write(f.toPath(), new ArrayList<>(names)); }
        catch (IOException e) { log.warning("writeImportedSet: " + e.getMessage()); }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** getMailbox without triggering sync (avoids recursion inside listMailboxes). */
    private ImapMailbox getMailboxDirect(String username, String mailboxName) {
        File dir = mailboxDir(username, mailboxName);
        if (!dir.exists()) return null;
        long uidv = readLong(new File(dir, ".uidvalidity"), System.currentTimeMillis() / 1000);
        long uidn = readLong(new File(dir, ".uidnext"), 1L);
        return new ImapMailbox(mailboxName, uidv, uidn);
    }

    private File userDir(String username) {
        return new File(BASE, username);
    }

    private File mailboxDir(String username, String mailboxName) {
        return new File(userDir(username), mailboxName);
    }

    private List<String> readMeta(File dir, long uid) {
        File meta = new File(dir, uid + ".meta");
        if (!meta.exists()) return new ArrayList<>();
        try { return new ArrayList<>(Files.readAllLines(meta.toPath())); }
        catch (IOException e) { return new ArrayList<>(); }
    }

    private void writeMeta(File dir, long uid, List<String> flags) {
        try { Files.write(new File(dir, uid + ".meta").toPath(), flags); }
        catch (IOException e) { log.warning("writeMeta: " + e.getMessage()); }
    }

    private long readLong(File f, long defaultVal) {
        if (!f.exists()) return defaultVal;
        try { return Long.parseLong(Files.readString(f.toPath()).trim()); }
        catch (Exception e) { return defaultVal; }
    }

    private void writeLong(File f, long value) {
        try { Files.writeString(f.toPath(), String.valueOf(value)); }
        catch (IOException e) { log.warning("writeLong: " + e.getMessage()); }
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
package org.emp.imap.storage;

import org.emp.imap.ImapMailbox;
import org.emp.imap.ImapMessage;

import java.util.List;
import java.util.Set;

/**
 * Storage abstraction for the IMAP server.
 *
 * Étape 1–4 : FileMailStorage  — filesystem (mailserver/<user>/<mailbox>/)
 * Étape 5   : DBMailStorage    — MySQL stored procedures
 *
 * Inject via: ImapServer.setMailStorage(impl)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * All methods receive `username` because the server is multi-user and
 * storage implementations need to scope access per authenticated user.
 * ─────────────────────────────────────────────────────────────────────────
 */
public interface ImapMailStorage {

    // ── Mailbox management ────────────────────────────────────────────────────

    /** Return mailbox metadata, or null if not found. */
    ImapMailbox getMailbox(String username, String mailboxName);

    /**
     * List mailboxes matching a reference + pattern.
     * Pattern wildcards: * = any chars, % = any chars except hierarchy delimiter.
     */
    List<ImapMailbox> listMailboxes(String username, String reference, String pattern);

    /** Create a new mailbox. Returns true on success. */
    boolean createMailbox(String username, String mailboxName);

    /** Permanently delete a mailbox and all its messages. Returns true on success. */
    boolean deleteMailbox(String username, String mailboxName);

    /**
     * Rename a mailbox. If oldName is INBOX, move all messages to newName
     * and leave INBOX empty (RFC 9051 §6.3.6).
     */
    boolean renameMailbox(String username, String oldName, String newName);

    /** Add mailbox to user's subscription list. */
    void subscribe(String username, String mailboxName);

    /** Remove mailbox from user's subscription list. */
    void unsubscribe(String username, String mailboxName);

    // ── Message access ────────────────────────────────────────────────────────

    /**
     * Load all messages in a mailbox, ordered by UID ascending.
     * Index 0 = message sequence number 1.
     */
    List<ImapMessage> loadMessages(String username, String mailboxName);

    /**
     * Append a new message to a mailbox.
     * @param flags        initial flags (may be empty)
     * @param internalDate timestamp in milliseconds
     * @return the UID assigned to the new message
     */
    long appendMessage(String username, String mailboxName,
                       String content, List<String> flags, long internalDate);

    /**
     * Copy a message from srcMailbox to destMailbox.
     * Preserves flags and internalDate.
     * @return the UID assigned in the destination mailbox
     */
    long copyMessage(String username, String srcMailbox, long srcUid, String destMailbox);

    /** Permanently delete a specific message by UID. */
    void deleteMessage(String username, String mailboxName, long uid);

    /** Update the flag set for a specific message. */
    void updateFlags(String username, String mailboxName, long uid, Set<String> flags);

    /**
     * Expunge all \\Deleted messages from a mailbox.
     * @param silent if true, do not return EXPUNGE responses (used by CLOSE command)
     */
    void expunge(String username, String mailboxName, boolean silent);
}
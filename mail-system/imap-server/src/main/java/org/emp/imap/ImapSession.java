package org.emp.imap;

import org.emp.imap.storage.ImapMailStorage;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Handles one IMAP4rev2 client connection.
 * Implements RFC 9051 — all four states, all mandatory commands.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  RFC 9051 FSM — four states (§3)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  NOT_AUTHENTICATED ──LOGIN──▶ AUTHENTICATED ──SELECT/EXAMINE──▶ SELECTED
 *                                     ▲                              │
 *                                     └──────CLOSE/UNSELECT──────────┘
 *  Any state ──LOGOUT──▶ LOGOUT (BYE + tagged OK + close TCP)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  Commands implemented per state
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  ANY state     : CAPABILITY, NOOP, LOGOUT
 *  NOT_AUTH      : LOGIN
 *  AUTHENTICATED : SELECT, EXAMINE, LIST, STATUS, CREATE, DELETE,
 *                  RENAME, SUBSCRIBE, UNSUBSCRIBE, APPEND, NAMESPACE
 *  SELECTED      : CLOSE, UNSELECT, EXPUNGE, SEARCH, FETCH, STORE,
 *                  COPY, MOVE, UID (FETCH/SEARCH/STORE/COPY/EXPUNGE)
 *                  + all AUTHENTICATED commands
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  Design notes (integration hooks for later Étapes)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  • All authentication is delegated to ImapAuthenticator (Étapes 4, 5).
 *  • All mailbox / message I/O is delegated to ImapMailStorage (Étape 5).
 *  • GUI live log goes through ImapLogListener (Étape 3).
 *  • ImapMessage and ImapMailbox are value objects — storage-agnostic.
 */
public class ImapSession implements Runnable {

    private static final Logger log = Logger.getLogger(ImapSession.class.getName());

    // RFC 9051 §3 — four states
    private enum ImapState { NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT }

    // RFC 9051 §5.4 — autologout timer: MUST be >= 30 minutes after authentication
    private static final int AUTOLOGOUT_MS = 30 * 60 * 1000;

    // Standard system flags (RFC 9051 §2.3.2)
    private static final Set<String> SYSTEM_FLAGS = new HashSet<>(Arrays.asList(
            "\\Answered", "\\Flagged", "\\Deleted", "\\Seen", "\\Draft"
    ));

    private final Socket           socket;
    private final String           serverDomain;
    private final ImapLogListener  logListener;
    private final ImapAuthenticator authenticator;
    private final ImapMailStorage  mailStorage;

    private BufferedReader in;
    private PrintWriter    out;

    // Session state
    private ImapState    state        = ImapState.NOT_AUTHENTICATED;
    private String       username     = null;

    // Selected mailbox state (valid only in SELECTED)
    private ImapMailbox  selectedMailbox  = null;
    private boolean      readOnly         = false;  // true when opened via EXAMINE

    // In-session message list (index 0 = seqnum 1, stable until EXPUNGE)
    private List<ImapMessage> messages      = new ArrayList<>();

    // IMAP UIDs assigned this session (persisted in storage)
    private long uidValidity = 0;
    private long uidNext     = 1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ImapSession(Socket socket, String serverDomain,
                       ImapLogListener logListener,
                       ImapAuthenticator authenticator,
                       ImapMailStorage mailStorage) {
        this.socket        = socket;
        this.serverDomain  = serverDomain;
        this.logListener   = logListener;
        this.authenticator = authenticator;
        this.mailStorage   = mailStorage;
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            // Pre-auth: no specific timer restriction (§5.4 note)
            socket.setSoTimeout(60_000); // 60s before auth

            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);

            // RFC 9051 §3 — greeting MUST be a positive response
            // Include CAPABILITY in greeting for client convenience (§7.1 CAPABILITY code)
            sendUntagged("OK [CAPABILITY IMAP4rev2 AUTH=PLAIN] " + serverDomain + " IMAP4rev2 Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                logClient(line);
                if (state == ImapState.LOGOUT) break;
                dispatch(line.trim());
            }

        } catch (java.net.SocketTimeoutException e) {
            // RFC 9051 §5.4 — autologout: send BYE then close
            sendUntagged("BYE Autologout; idle for too long");
            log.info("Session timed out for " + socket.getInetAddress());
        } catch (IOException e) {
            log.warning("Session IO error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Command dispatcher ────────────────────────────────────────────────────

    /**
     * Parse tag + command + args and route.
     * RFC 9051 §2.2 — command format: tag SP command [SP args] CRLF
     */
    private void dispatch(String line) {
        if (line.isEmpty()) return;

        // Split into: tag, command, rest
        String[] parts   = line.split(" ", 3);
        String   tag     = parts[0];
        String   command = parts.length > 1 ? parts[1].toUpperCase() : "";
        String   args    = parts.length > 2 ? parts[2] : "";

        // RFC 9051 §6 — commands valid in any state
        switch (command) {
            case "CAPABILITY": handleCapability(tag);       return;
            case "NOOP":       handleNoop(tag);             return;
            case "LOGOUT":     handleLogout(tag);           return;
        }

        // RFC 9051 §6.2 — NOT_AUTHENTICATED commands
        if (state == ImapState.NOT_AUTHENTICATED) {
            switch (command) {
                case "LOGIN":        handleLogin(tag, args);       return;
                case "AUTHENTICATE": handleAuthenticate(tag, args); return;
                default:
                    sendTagged(tag, "BAD Command not permitted in NOT AUTHENTICATED state");
                    return;
            }
        }

        // RFC 9051 §6.3 — AUTHENTICATED + SELECTED commands
        if (state == ImapState.AUTHENTICATED || state == ImapState.SELECTED) {
            switch (command) {
                case "SELECT":      handleSelect(tag, args, false); return;
                case "EXAMINE":     handleSelect(tag, args, true);  return;
                case "LIST":        handleList(tag, args);          return;
                case "STATUS":      handleStatus(tag, args);        return;
                case "CREATE":      handleCreate(tag, args);        return;
                case "DELETE":      handleDelete(tag, args);        return;
                case "RENAME":      handleRename(tag, args);        return;
                case "SUBSCRIBE":   handleSubscribe(tag, args);     return;
                case "UNSUBSCRIBE": handleUnsubscribe(tag, args);   return;
                case "APPEND":      handleAppend(tag, args);        return;
                case "NAMESPACE":   handleNamespace(tag);           return;
            }
        }

        // RFC 9051 §6.4 — SELECTED-only commands
        if (state == ImapState.SELECTED) {
            switch (command) {
                case "CLOSE":    handleClose(tag);             return;
                case "UNSELECT": handleUnselect(tag);          return;
                case "EXPUNGE":  handleExpunge(tag);           return;
                case "SEARCH":   handleSearch(tag, args, false); return;
                case "FETCH":    handleFetch(tag, args, false); return;
                case "STORE":    handleStore(tag, args, false); return;
                case "COPY":     handleCopy(tag, args, false);  return;
                case "MOVE":     handleMove(tag, args, false);  return;
                case "UID":      handleUid(tag, args);          return;
                default:
                    sendTagged(tag, "BAD Unknown or invalid command in SELECTED state");
                    return;
            }
        }

        sendTagged(tag, "BAD Command not valid in current state");
    }

    // ── ANY STATE commands ────────────────────────────────────────────────────

    /**
     * CAPABILITY — RFC 9051 §6.1.1
     * MUST send untagged CAPABILITY response including "IMAP4rev2".
     */
    private void handleCapability(String tag) {
        sendUntagged("CAPABILITY IMAP4rev2 AUTH=PLAIN LITERAL+");
        sendTagged(tag, "OK CAPABILITY completed");
    }

    /**
     * NOOP — RFC 9051 §6.1.2
     * Always succeeds. Used to poll for updates and reset autologout timer.
     * In SELECTED state, we piggyback any pending EXISTS updates.
     */
    private void handleNoop(String tag) {
        if (state == ImapState.SELECTED) {
            sendMailboxUpdates();
        }
        sendTagged(tag, "OK NOOP completed");
    }

    /**
     * LOGOUT — RFC 9051 §6.1.3
     * MUST send untagged BYE before tagged OK.
     * MUST close the connection after the tagged OK.
     */
    private void handleLogout(String tag) {
        sendUntagged("BYE " + serverDomain + " IMAP4rev2 Server logging out");
        sendTagged(tag, "OK LOGOUT completed");
        state = ImapState.LOGOUT;
        // Socket will be closed in the finally block of run()
    }

    // ── NOT_AUTHENTICATED commands ────────────────────────────────────────────

    /**
     * LOGIN user password — RFC 9051 §6.2.3
     * Transitions to AUTHENTICATED on success.
     * RFC note: LOGIN SHOULD NOT be used on insecure networks.
     * We implement it as the primary mechanism for this TP context.
     */
    private void handleLogin(String tag, String args) {
        // Parse: LOGIN "user" "password"  or  LOGIN user password
        String[] parts = parseArgs(args, 2);
        if (parts == null || parts.length < 2) {
            sendTagged(tag, "BAD LOGIN requires username and password");
            return;
        }
        String user = parts[0];
        String pass = parts[1];

        if (!authenticator.authenticate(user, pass)) {
            sendTagged(tag, "NO [AUTHENTICATIONFAILED] Authentication failed");
            return;
        }

        username = user;
        state    = ImapState.AUTHENTICATED;

        // RFC 9051 §5.4 — after auth, autologout MUST be >= 30 minutes
        try { socket.setSoTimeout(AUTOLOGOUT_MS); }
        catch (IOException e) { log.warning("Could not set socket timeout: " + e.getMessage()); }

        // MAY include CAPABILITY in tagged OK to save a round-trip (§7.1)
        sendTagged(tag, "OK [CAPABILITY IMAP4rev2 AUTH=PLAIN LITERAL+] LOGIN completed");
    }

    /**
     * AUTHENTICATE mechanism [initial-response] — RFC 9051 §6.2.2
     * Minimal PLAIN implementation for this TP context.
     * Full SASL exchange would be added in Étape 4/5.
     */
    private void handleAuthenticate(String tag, String args) {
        String[] parts = args.split(" ", 2);
        String mechanism = parts[0].toUpperCase();

        if (!mechanism.equals("PLAIN")) {
            sendTagged(tag, "NO Unsupported authentication mechanism");
            return;
        }

        // PLAIN: base64(authzid\0authcid\0password)
        // Send continuation challenge
        sendRaw("+ ");

        try {
            String response = in.readLine();
            if (response == null || response.equals("*")) {
                sendTagged(tag, "BAD Authentication cancelled");
                return;
            }

            byte[] decoded = java.util.Base64.getDecoder().decode(response.trim());
            String plain   = new String(decoded);
            String[] tokens = plain.split("\0");

            if (tokens.length < 3) {
                sendTagged(tag, "BAD Invalid PLAIN response format");
                return;
            }

            String user = tokens[1];
            String pass = tokens[2];

            if (!authenticator.authenticate(user, pass)) {
                sendTagged(tag, "NO [AUTHENTICATIONFAILED] Authentication failed");
                return;
            }

            username = user;
            state    = ImapState.AUTHENTICATED;
            try { socket.setSoTimeout(AUTOLOGOUT_MS); }
            catch (IOException e) { log.warning("Could not set socket timeout"); }

            sendTagged(tag, "OK [CAPABILITY IMAP4rev2 LITERAL+] AUTHENTICATE completed");

        } catch (IOException | IllegalArgumentException e) {
            sendTagged(tag, "BAD Authentication error: " + e.getMessage());
        }
    }

    // ── AUTHENTICATED commands ────────────────────────────────────────────────

    /**
     * SELECT / EXAMINE mailbox — RFC 9051 §6.3.2 / §6.3.3
     *
     * SELECT  → read-write (readOnly = false)
     * EXAMINE → read-only  (readOnly = true)
     *
     * MUST send: FLAGS, <n> EXISTS, LIST response, and OK with:
     *   [PERMANENTFLAGS], [UIDNEXT], [UIDVALIDITY]
     *
     * If a mailbox was already selected, MUST send untagged OK [CLOSED]
     * before responses for the new mailbox. (§7.1 CLOSED)
     */
    private void handleSelect(String tag, String args, boolean examineMode) {
        String mailboxName = unquote(args.trim());
        if (mailboxName.isEmpty()) {
            sendTagged(tag, "BAD Mailbox name required");
            return;
        }

        // RFC 9051 §6.3.2 — if currently selected, deselect first
        if (state == ImapState.SELECTED) {
            // MUST send [CLOSED] when transitioning away from selected mailbox
            sendUntagged("OK [CLOSED] Previous mailbox is now closed");
            selectedMailbox = null;
            messages        = new ArrayList<>();
            readOnly        = false;
        }

        ImapMailbox mailbox = mailStorage.getMailbox(username, mailboxName);
        if (mailbox == null) {
            // Failed SELECT → back to AUTHENTICATED (not SELECTED)
            state = ImapState.AUTHENTICATED;
            sendTagged(tag, "NO [NONEXISTENT] No such mailbox");
            return;
        }

        // Load messages for session
        messages     = mailStorage.loadMessages(username, mailboxName);
        uidValidity  = mailbox.getUidValidity();
        uidNext      = mailbox.getUidNext();
        selectedMailbox = mailbox;
        readOnly     = examineMode;
        state        = ImapState.SELECTED;

        // REQUIRED untagged responses (§6.3.2)
        sendUntagged(messages.size() + " EXISTS");
        sendUntagged("OK [UIDVALIDITY " + uidValidity + "] UIDs valid");
        sendUntagged("OK [UIDNEXT " + uidNext + "] Predicted next UID");
        sendUntagged("FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)");
        sendUntagged("OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Permanent flags");

        // REQUIRED LIST response with mailbox name (§6.3.2)
        sendUntagged("LIST () \"/\" " + quoteMailbox(mailboxName));

        // Tagged OK with READ-WRITE or READ-ONLY response code
        if (examineMode) {
            sendTagged(tag, "OK [READ-ONLY] EXAMINE completed");
        } else {
            sendTagged(tag, "OK [READ-WRITE] SELECT completed");
        }
    }

    /**
     * LIST "" pattern — RFC 9051 §6.3.9
     * Returns mailbox names matching the pattern.
     * Wildcard: * matches everything, % matches everything except hierarchy delimiter.
     */
    private void handleList(String tag, String args) {
        // Parse:  LIST reference pattern
        //         LIST "" *        (basic)
        //         LIST "" "INBOX"  (specific)
        String[] parts = parseArgs(args, 2);
        if (parts == null || parts.length < 2) {
            sendTagged(tag, "BAD LIST requires reference and mailbox name");
            return;
        }

        String reference = parts[0];  // typically "" — we treat it as prefix
        String pattern   = parts[1];

        // Special case: LIST "" "" → return hierarchy delimiter only (§6.3.9)
        if (pattern.isEmpty()) {
            sendUntagged("LIST (\\Noselect) \"/\" \"\"");
            sendTagged(tag, "OK LIST completed");
            return;
        }

        List<ImapMailbox> mailboxes = mailStorage.listMailboxes(username, reference, pattern);
        for (ImapMailbox mb : mailboxes) {
            String attrs = mb.getAttributes().isEmpty()
                    ? "()"
                    : "(" + String.join(" ", mb.getAttributes()) + ")";
            sendUntagged("LIST " + attrs + " \"/\" " + quoteMailbox(mb.getName()));
        }
        sendTagged(tag, "OK LIST completed");
    }

    /**
     * STATUS mailbox (items...) — RFC 9051 §6.3.11
     * Returns requested status info without selecting the mailbox.
     * Items: MESSAGES, UIDNEXT, UIDVALIDITY, UNSEEN, DELETED, SIZE
     */
    private void handleStatus(String tag, String args) {
        // Parse: STATUS "mailbox" (MESSAGES UNSEEN ...)
        int parenOpen  = args.indexOf('(');
        int parenClose = args.lastIndexOf(')');
        if (parenOpen < 0 || parenClose < 0) {
            sendTagged(tag, "BAD STATUS requires mailbox name and status items");
            return;
        }

        String mailboxName = unquote(args.substring(0, parenOpen).trim());
        String[] items     = args.substring(parenOpen + 1, parenClose).trim().split("\\s+");

        ImapMailbox mailbox = mailStorage.getMailbox(username, mailboxName);
        if (mailbox == null) {
            sendTagged(tag, "NO [NONEXISTENT] No such mailbox");
            return;
        }

        List<ImapMessage> msgs = mailStorage.loadMessages(username, mailboxName);
        StringBuilder statusResult = new StringBuilder();

        for (String item : items) {
            switch (item.toUpperCase()) {
                case "MESSAGES":
                    statusResult.append("MESSAGES ").append(msgs.size()).append(" ");
                    break;
                case "UIDNEXT":
                    statusResult.append("UIDNEXT ").append(mailbox.getUidNext()).append(" ");
                    break;
                case "UIDVALIDITY":
                    statusResult.append("UIDVALIDITY ").append(mailbox.getUidValidity()).append(" ");
                    break;
                case "UNSEEN":
                    long unseen = msgs.stream().filter(m -> !m.hasFlag("\\Seen")).count();
                    statusResult.append("UNSEEN ").append(unseen).append(" ");
                    break;
                case "DELETED":
                    long deleted = msgs.stream().filter(m -> m.hasFlag("\\Deleted")).count();
                    statusResult.append("DELETED ").append(deleted).append(" ");
                    break;
                case "SIZE":
                    long size = msgs.stream().mapToLong(ImapMessage::getSize).sum();
                    statusResult.append("SIZE ").append(size).append(" ");
                    break;
                default:
                    sendTagged(tag, "BAD Unknown STATUS item: " + item);
                    return;
            }
        }

        sendUntagged("STATUS " + quoteMailbox(mailboxName)
                + " (" + statusResult.toString().trim() + ")");
        sendTagged(tag, "OK STATUS completed");
    }

    /**
     * CREATE mailbox — RFC 9051 §6.3.4
     * Returns OK only if the mailbox was successfully created.
     * INBOX cannot be created.
     */
    private void handleCreate(String tag, String args) {
        String mailboxName = unquote(args.trim());
        if (mailboxName.isEmpty()) {
            sendTagged(tag, "BAD Mailbox name required");
            return;
        }
        if (mailboxName.equalsIgnoreCase("INBOX")) {
            sendTagged(tag, "NO [CANNOT] Cannot create INBOX");
            return;
        }
        if (mailStorage.getMailbox(username, mailboxName) != null) {
            sendTagged(tag, "NO [ALREADYEXISTS] Mailbox already exists");
            return;
        }
        boolean ok = mailStorage.createMailbox(username, mailboxName);
        if (ok) sendTagged(tag, "OK CREATE completed");
        else    sendTagged(tag, "NO [CANNOT] Could not create mailbox");
    }

    /**
     * DELETE mailbox — RFC 9051 §6.3.5
     * Cannot delete INBOX.
     * Cannot delete a mailbox that has children and has \\Noselect attribute.
     */
    private void handleDelete(String tag, String args) {
        String mailboxName = unquote(args.trim());
        if (mailboxName.equalsIgnoreCase("INBOX")) {
            sendTagged(tag, "NO [CANNOT] Cannot delete INBOX");
            return;
        }
        if (mailStorage.getMailbox(username, mailboxName) == null) {
            sendTagged(tag, "NO [NONEXISTENT] No such mailbox");
            return;
        }
        boolean ok = mailStorage.deleteMailbox(username, mailboxName);
        if (ok) sendTagged(tag, "OK DELETE completed");
        else    sendTagged(tag, "NO [INUSE] Could not delete mailbox");
    }

    /**
     * RENAME old new — RFC 9051 §6.3.6
     * Renaming INBOX moves all messages to new mailbox, leaves INBOX empty.
     */
    private void handleRename(String tag, String args) {
        String[] parts = parseArgs(args, 2);
        if (parts == null || parts.length < 2) {
            sendTagged(tag, "BAD RENAME requires old and new mailbox names");
            return;
        }
        String oldName = parts[0];
        String newName = parts[1];

        if (mailStorage.getMailbox(username, oldName) == null) {
            sendTagged(tag, "NO [NONEXISTENT] Source mailbox does not exist");
            return;
        }
        if (!oldName.equalsIgnoreCase("INBOX")
                && mailStorage.getMailbox(username, newName) != null) {
            sendTagged(tag, "NO [ALREADYEXISTS] Destination mailbox already exists");
            return;
        }
        boolean ok = mailStorage.renameMailbox(username, oldName, newName);
        if (ok) sendTagged(tag, "OK RENAME completed");
        else    sendTagged(tag, "NO Could not rename mailbox");
    }

    /** SUBSCRIBE mailbox — RFC 9051 §6.3.7 */
    private void handleSubscribe(String tag, String args) {
        String mailboxName = unquote(args.trim());
        mailStorage.subscribe(username, mailboxName);
        sendTagged(tag, "OK SUBSCRIBE completed");
    }

    /** UNSUBSCRIBE mailbox — RFC 9051 §6.3.8 */
    private void handleUnsubscribe(String tag, String args) {
        String mailboxName = unquote(args.trim());
        mailStorage.unsubscribe(username, mailboxName);
        sendTagged(tag, "OK UNSUBSCRIBE completed");
    }

    /**
     * NAMESPACE — RFC 9051 §6.3.10
     * Returns personal/other/shared namespace info.
     * We expose one personal namespace with "/" hierarchy delimiter.
     */
    private void handleNamespace(String tag) {
        sendUntagged("NAMESPACE ((\"\" \"/\")) NIL NIL");
        sendTagged(tag, "OK NAMESPACE completed");
    }

    /**
     * APPEND mailbox [flags] [datetime] literal — RFC 9051 §6.3.12
     *
     * Accepts a message literal and appends it to the specified mailbox.
     * Supports both synchronizing literal ({n}) and non-synchronizing ({n+}).
     * Returns APPENDUID response code on success.
     */
    private void handleAppend(String tag, String args) {
        // Parse: APPEND mailbox [(flags)] ["datetime"] {n[+]}
        // Simplified parsing — find mailbox name then literal size
        String remaining = args.trim();
        String mailboxName;

        if (remaining.startsWith("\"")) {
            int end = remaining.indexOf('"', 1);
            mailboxName = remaining.substring(1, end);
            remaining   = remaining.substring(end + 1).trim();
        } else {
            int space = remaining.indexOf(' ');
            if (space < 0) {
                sendTagged(tag, "BAD APPEND requires mailbox and literal");
                return;
            }
            mailboxName = remaining.substring(0, space);
            remaining   = remaining.substring(space + 1).trim();
        }

        // Optional flags
        List<String> flags = new ArrayList<>();
        if (remaining.startsWith("(")) {
            int end = remaining.indexOf(')');
            String flagStr = remaining.substring(1, end).trim();
            if (!flagStr.isEmpty()) flags.addAll(Arrays.asList(flagStr.split("\\s+")));
            remaining = remaining.substring(end + 1).trim();
        }

        // Optional datetime (quoted string) — skip it for simplicity
        if (remaining.startsWith("\"")) {
            int end = remaining.indexOf('"', 1);
            remaining = remaining.substring(end + 1).trim();
        }

        // Literal size: {n} or {n+}
        if (!remaining.startsWith("{")) {
            sendTagged(tag, "BAD Expected literal");
            return;
        }

        boolean nonSync = remaining.contains("+");
        int literalSize;
        try {
            String sizeStr = remaining.replaceAll("\\{|\\+|\\}", "");
            literalSize = Integer.parseInt(sizeStr.trim());
        } catch (NumberFormatException e) {
            sendTagged(tag, "BAD Invalid literal size");
            return;
        }

        // For synchronizing literals, send continuation request
        if (!nonSync) {
            sendRaw("+ Ready for literal data");
        }

        // Read exactly literalSize bytes
        try {
            char[] buf = new char[literalSize];
            int totalRead = 0;
            while (totalRead < literalSize) {
                int read = in.read(buf, totalRead, literalSize - totalRead);
                if (read < 0) break;
                totalRead += read;
            }
            in.readLine(); // consume trailing CRLF

            String messageBody = new String(buf, 0, totalRead);

            ImapMailbox mailbox = mailStorage.getMailbox(username, mailboxName);
            if (mailbox == null) {
                sendTagged(tag, "NO [TRYCREATE] Mailbox does not exist");
                return;
            }

            long assignedUid = mailStorage.appendMessage(
                    username, mailboxName, messageBody, flags, System.currentTimeMillis());

            // RFC 9051 §7.1 APPENDUID
            sendTagged(tag, "OK [APPENDUID " + mailbox.getUidValidity()
                    + " " + assignedUid + "] APPEND completed");

        } catch (IOException e) {
            sendTagged(tag, "NO APPEND failed: " + e.getMessage());
        }
    }

    // ── SELECTED commands ─────────────────────────────────────────────────────

    /**
     * CLOSE — RFC 9051 §6.4.1
     * Silently expunges \\Deleted messages (no EXPUNGE responses sent),
     * then returns to AUTHENTICATED state.
     * No-op if read-only (EXAMINE).
     */
    private void handleClose(String tag) {
        if (!readOnly) {
            // Silently remove \\Deleted messages — no untagged EXPUNGE responses
            mailStorage.expunge(username, selectedMailbox.getName(), true);
        }
        state           = ImapState.AUTHENTICATED;
        selectedMailbox = null;
        messages        = new ArrayList<>();
        readOnly        = false;
        sendTagged(tag, "OK CLOSE completed");
    }

    /**
     * UNSELECT — RFC 9051 §6.4.2
     * Same as CLOSE but does NOT expunge \\Deleted messages.
     * Returns to AUTHENTICATED state.
     */
    private void handleUnselect(String tag) {
        state           = ImapState.AUTHENTICATED;
        selectedMailbox = null;
        messages        = new ArrayList<>();
        readOnly        = false;
        sendTagged(tag, "OK UNSELECT completed");
    }

    /**
     * EXPUNGE — RFC 9051 §6.4.3
     * Permanently removes all \\Deleted messages.
     * MUST send an untagged EXPUNGE response for each removed message.
     * NOTE: EXPUNGE responses use the sequence number AFTER previous removals
     * (i.e., all responses say the current position, not original).
     */
    private void handleExpunge(String tag) {
        if (readOnly) {
            sendTagged(tag, "NO [READ-ONLY] Mailbox is read-only");
            return;
        }
        doExpunge(tag, null);  // null = expunge all \\Deleted
    }

    /**
     * Internal expunge implementation shared by EXPUNGE and UID EXPUNGE.
     * @param uidSet if non-null, only expunge messages whose UID is in the set (UID EXPUNGE)
     */
    private void doExpunge(String tag, Set<Long> uidSet) {
        // Reload messages to get current state
        messages = mailStorage.loadMessages(username, selectedMailbox.getName());

        // Walk backwards to preserve sequence numbers in forward direction
        // But RFC says: send EXPUNGE with the sequence number AT TIME OF EXPUNGE
        // So we walk forward and renumber as we go
        int seqOffset = 0;
        List<Integer> toRemove = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            ImapMessage msg = messages.get(i);
            if (msg.hasFlag("\\Deleted")
                    && (uidSet == null || uidSet.contains(msg.getUid()))) {
                toRemove.add(i);
            }
        }

        // Remove from highest to lowest index to keep sequence numbers valid
        List<Integer> removed = new ArrayList<>();
        for (int idx : toRemove) {
            int currentSeq = idx + 1 - removed.stream().mapToInt(r -> r < idx ? 1 : 0).sum();
            mailStorage.deleteMessage(username, selectedMailbox.getName(), messages.get(idx).getUid());
            sendUntagged(currentSeq + " EXPUNGE");
            removed.add(idx);
        }

        // Reload after expunge
        messages = mailStorage.loadMessages(username, selectedMailbox.getName());
        uidNext  = mailStorage.getMailbox(username, selectedMailbox.getName()).getUidNext();

        if (tag != null) sendTagged(tag, "OK EXPUNGE completed");
    }

    /**
     * SEARCH [RETURN (...)] [CHARSET x] criteria — RFC 9051 §6.4.4
     * Returns ESEARCH response (IMAP4rev2 uses ESEARCH, not SEARCH).
     * Supports: ALL, ANSWERED, DELETED, DRAFT, FLAGGED, SEEN, UNSEEN,
     *           UNDELETED, UNDRAFT, UNFLAGGED, UNANSWERED, UID,
     *           FROM, TO, CC, BCC, SUBJECT, BODY, TEXT,
     *           BEFORE, ON, SINCE, LARGER, SMALLER, HEADER, NOT, OR
     */
    private void handleSearch(String tag, String args, boolean uidMode) {
        // Parse optional RETURN (options)
        List<String> returnOptions = new ArrayList<>();
        String criteria = args.trim();

        if (criteria.toUpperCase().startsWith("RETURN")) {
            int parenOpen  = criteria.indexOf('(');
            int parenClose = criteria.indexOf(')');
            if (parenOpen >= 0 && parenClose > parenOpen) {
                String opts = criteria.substring(parenOpen + 1, parenClose).trim();
                if (!opts.isEmpty()) returnOptions.addAll(Arrays.asList(opts.split("\\s+")));
                criteria = criteria.substring(parenClose + 1).trim();
            }
        }

        // Skip optional CHARSET (we treat everything as UTF-8)
        if (criteria.toUpperCase().startsWith("CHARSET")) {
            criteria = criteria.substring(criteria.indexOf(' ', 7)).trim();
        }

        // Perform search
        List<ImapMessage> matched = searchMessages(criteria, messages);
        List<Integer> seqNums     = new ArrayList<>();
        List<Long>    uids        = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            ImapMessage msg = messages.get(i);
            if (matched.contains(msg)) {
                seqNums.add(i + 1);
                uids.add(msg.getUid());
            }
        }

        List<Object> results = uidMode
                ? uids.stream().map(u -> (Object) u).collect(Collectors.toList())
                : seqNums.stream().map(s -> (Object) s).collect(Collectors.toList());

        // Build ESEARCH response — RFC 9051 requires ESEARCH (not SEARCH) for IMAP4rev2
        StringBuilder esearch = new StringBuilder();
        esearch.append("ESEARCH (TAG \"").append(tag).append("\")");
        if (uidMode) esearch.append(" UID");

        // If no RETURN options or empty RETURN (), default is ALL
        if (returnOptions.isEmpty()) returnOptions.add("ALL");

        for (String opt : returnOptions) {
            switch (opt.toUpperCase()) {
                case "ALL":
                    if (!results.isEmpty())
                        esearch.append(" ALL ").append(toSequenceSet(results));
                    break;
                case "MIN":
                    if (!results.isEmpty())
                        esearch.append(" MIN ").append(results.get(0));
                    break;
                case "MAX":
                    if (!results.isEmpty())
                        esearch.append(" MAX ").append(results.get(results.size() - 1));
                    break;
                case "COUNT":
                    esearch.append(" COUNT ").append(results.size());
                    break;
            }
        }

        sendUntagged(esearch.toString());
        sendTagged(tag, "OK SEARCH completed");
    }

    /**
     * FETCH seqset (items) — RFC 9051 §6.4.5
     *
     * Macros:
     *   ALL  = (FLAGS INTERNALDATE RFC822.SIZE ENVELOPE)
     *   FAST = (FLAGS INTERNALDATE RFC822.SIZE)
     *   FULL = (FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODY)
     *
     * Items: FLAGS, INTERNALDATE, RFC822.SIZE, ENVELOPE, BODY, BODYSTRUCTURE,
     *        BODY[], BODY[HEADER], BODY[TEXT], BODY.PEEK[], UID
     *
     * UID FETCH: same but seqset is UIDs + UID always included in response.
     */
    private void handleFetch(String tag, String args, boolean uidMode) {
        // Split: FETCH seqset items
        int firstSpace = args.indexOf(' ');
        if (firstSpace < 0) {
            sendTagged(tag, "BAD FETCH requires sequence set and items");
            return;
        }
        String seqSetStr = args.substring(0, firstSpace).trim();
        String itemsStr  = args.substring(firstSpace + 1).trim();

        // Expand macros
        switch (itemsStr.toUpperCase()) {
            case "ALL":  itemsStr = "(FLAGS INTERNALDATE RFC822.SIZE ENVELOPE)"; break;
            case "FAST": itemsStr = "(FLAGS INTERNALDATE RFC822.SIZE)";          break;
            case "FULL": itemsStr = "(FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODY)"; break;
        }

        // Parse items list
        List<String> items = parseFetchItems(itemsStr);

        // Resolve sequence set to message indices
        List<Integer> indices = uidMode
                ? resolveUidSet(seqSetStr)
                : resolveSeqSet(seqSetStr);

        for (int idx : indices) {
            if (idx < 0 || idx >= messages.size()) continue;
            ImapMessage msg = messages.get(idx);
            int seqNum = idx + 1;

            StringBuilder fetchData = new StringBuilder();
            boolean setSeen = false;

            for (String item : items) {
                String itemUpper = item.toUpperCase();

                if (itemUpper.equals("FLAGS")) {
                    fetchData.append("FLAGS (").append(String.join(" ", msg.getFlags())).append(") ");

                } else if (itemUpper.equals("INTERNALDATE")) {
                    fetchData.append("INTERNALDATE \"")
                            .append(formatInternalDate(msg.getInternalDate()))
                            .append("\" ");

                } else if (itemUpper.equals("RFC822.SIZE")) {
                    fetchData.append("RFC822.SIZE ").append(msg.getSize()).append(" ");

                } else if (itemUpper.equals("UID")) {
                    fetchData.append("UID ").append(msg.getUid()).append(" ");

                } else if (itemUpper.equals("ENVELOPE")) {
                    fetchData.append("ENVELOPE ").append(buildEnvelope(msg)).append(" ");

                } else if (itemUpper.equals("BODY") || itemUpper.equals("BODYSTRUCTURE")) {
                    fetchData.append(itemUpper).append(" ").append(buildBodyStructure(msg)).append(" ");

                } else if (itemUpper.startsWith("BODY[") || itemUpper.startsWith("BODY.PEEK[")) {
                    boolean peek = itemUpper.startsWith("BODY.PEEK[");
                    String section = extractSection(item);
                    String content = fetchBodySection(msg, section);

                    // BODY[] (not PEEK) implicitly sets \\Seen
                    if (!peek && !msg.hasFlag("\\Seen") && !readOnly) {
                        setSeen = true;
                    }

                    // Normalize the item name in response (BODY.PEEK → BODY)
                    String responseItem = item.toUpperCase().replace("BODY.PEEK", "BODY");
                    fetchData.append(responseItem)
                            .append(" {").append(content.length()).append("}\r\n")
                            .append(content).append(" ");

                // RFC 822 legacy aliases — Thunderbird and many clients send these
                // RFC 9051 § 6.4.5: RFC822.HEADER ≡ BODY.PEEK[HEADER] (no \Seen side-effect)
                //                        RFC822.TEXT   ≡ BODY[TEXT]         (sets \Seen)
                //                        RFC822        ≡ BODY[]             (sets \Seen)
                } else if (itemUpper.equals("RFC822.HEADER")) {
                    String content = fetchBodySection(msg, "HEADER");
                    fetchData.append("RFC822.HEADER {").append(content.length()).append("}\r\n")
                             .append(content).append(" ");
                    // PEEK-equivalent: does NOT set \Seen

                } else if (itemUpper.equals("RFC822.TEXT")) {
                    String content = fetchBodySection(msg, "TEXT");
                    fetchData.append("RFC822.TEXT {").append(content.length()).append("}\r\n")
                             .append(content).append(" ");
                    if (!msg.hasFlag("\\Seen") && !readOnly) setSeen = true;

                } else if (itemUpper.equals("RFC822")) {
                    String content = fetchBodySection(msg, "");
                    fetchData.append("RFC822 {").append(content.length()).append("}\r\n")
                             .append(content).append(" ");
                    if (!msg.hasFlag("\\Seen") && !readOnly) setSeen = true;
                }
            }

            // RFC 9051 §6.4.5 — UID FETCH MUST include UID in response
            if (uidMode && !items.stream().anyMatch(i -> i.equalsIgnoreCase("UID"))) {
                fetchData.append("UID ").append(msg.getUid()).append(" ");
            }

            // Apply \\Seen flag if a non-PEEK BODY fetch occurred
            if (setSeen && !readOnly) {
                msg.addFlag("\\Seen");
                mailStorage.updateFlags(username, selectedMailbox.getName(),
                        msg.getUid(), msg.getFlags());
                fetchData.append("FLAGS (").append(String.join(" ", msg.getFlags())).append(") ");
            }

            sendUntagged(seqNum + " FETCH (" + fetchData.toString().trim() + ")");
        }

        sendTagged(tag, "OK FETCH completed");
    }

    /**
     * STORE seqset FLAGS|+FLAGS|-FLAGS[.SILENT] (flags) — RFC 9051 §6.4.6
     *
     * FLAGS        → replace flags; return FETCH response
     * +FLAGS       → add flags;     return FETCH response
     * -FLAGS       → remove flags;  return FETCH response
     * FLAGS.SILENT / +FLAGS.SILENT / -FLAGS.SILENT → same but no FETCH response
     */
    private void handleStore(String tag, String args, boolean uidMode) {
        if (readOnly) {
            sendTagged(tag, "NO [READ-ONLY] Mailbox is read-only");
            return;
        }

        // Parse: seqset item (flags)
        String[] parts = args.split(" ", 3);
        if (parts.length < 3) {
            sendTagged(tag, "BAD STORE requires sequence set, item, and flags");
            return;
        }

        String seqSetStr  = parts[0];
        String storeItem  = parts[1].toUpperCase();
        String flagsParen = parts[2].trim();

        boolean silent = storeItem.contains(".SILENT");
        String mode    = storeItem.replace(".SILENT", "");   // FLAGS, +FLAGS, -FLAGS

        // Parse flag list
        List<String> newFlags = new ArrayList<>();
        if (flagsParen.startsWith("(")) {
            String inner = flagsParen.substring(1, flagsParen.lastIndexOf(')'));
            if (!inner.trim().isEmpty())
                newFlags.addAll(Arrays.asList(inner.trim().split("\\s+")));
        } else {
            newFlags.add(flagsParen);
        }

        List<Integer> indices = uidMode
                ? resolveUidSet(seqSetStr)
                : resolveSeqSet(seqSetStr);

        for (int idx : indices) {
            if (idx < 0 || idx >= messages.size()) continue;
            ImapMessage msg = messages.get(idx);

            switch (mode) {
                case "FLAGS":
                    msg.setFlags(newFlags);
                    break;
                case "+FLAGS":
                    newFlags.forEach(msg::addFlag);
                    break;
                case "-FLAGS":
                    newFlags.forEach(msg::removeFlag);
                    break;
                default:
                    sendTagged(tag, "BAD Unknown STORE item: " + storeItem);
                    return;
            }

            mailStorage.updateFlags(username, selectedMailbox.getName(),
                    msg.getUid(), msg.getFlags());

            // Unless .SILENT, return updated FLAGS
            if (!silent) {
                sendUntagged((idx + 1) + " FETCH (UID " + msg.getUid()
                        + " FLAGS (" + String.join(" ", msg.getFlags()) + "))");
            }
        }

        sendTagged(tag, "OK STORE completed");
    }

    /**
     * COPY seqset mailbox — RFC 9051 §6.4.7
     * Copies messages, preserving flags and internal date.
     * Returns COPYUID response code.
     */
    private void handleCopy(String tag, String args, boolean uidMode) {
        int space = args.lastIndexOf(' ');
        if (space < 0) {
            sendTagged(tag, "BAD COPY requires sequence set and mailbox");
            return;
        }
        String seqSetStr   = args.substring(0, space).trim();
        String destMailbox = unquote(args.substring(space + 1).trim());

        if (mailStorage.getMailbox(username, destMailbox) == null) {
            sendTagged(tag, "NO [TRYCREATE] Destination mailbox does not exist");
            return;
        }

        List<Integer> indices = uidMode
                ? resolveUidSet(seqSetStr)
                : resolveSeqSet(seqSetStr);

        List<Long> srcUids  = new ArrayList<>();
        List<Long> destUids = new ArrayList<>();

        for (int idx : indices) {
            if (idx < 0 || idx >= messages.size()) continue;
            ImapMessage src = messages.get(idx);
            long newUid = mailStorage.copyMessage(
                    username, selectedMailbox.getName(), src.getUid(), destMailbox);
            srcUids.add(src.getUid());
            destUids.add(newUid);
        }

        ImapMailbox destMb = mailStorage.getMailbox(username, destMailbox);
        if (!srcUids.isEmpty()) {
            // RFC 9051 §7.1 COPYUID
            sendTagged(tag, "OK [COPYUID " + destMb.getUidValidity()
                    + " " + toUidSetStr(srcUids)
                    + " " + toUidSetStr(destUids) + "] COPY completed");
        } else {
            sendTagged(tag, "OK COPY completed, nothing copied");
        }
    }

    /**
     * MOVE seqset mailbox — RFC 9051 §6.4.8
     * Atomically moves messages. Sends COPYUID then EXPUNGE (no STORE +FLAGS response).
     */
    private void handleMove(String tag, String args, boolean uidMode) {
        int space = args.lastIndexOf(' ');
        if (space < 0) {
            sendTagged(tag, "BAD MOVE requires sequence set and mailbox");
            return;
        }
        String seqSetStr   = args.substring(0, space).trim();
        String destMailbox = unquote(args.substring(space + 1).trim());

        if (mailStorage.getMailbox(username, destMailbox) == null) {
            sendTagged(tag, "NO [TRYCREATE] Destination mailbox does not exist");
            return;
        }

        List<Integer> indices = uidMode
                ? resolveUidSet(seqSetStr)
                : resolveSeqSet(seqSetStr);

        List<Long> srcUids  = new ArrayList<>();
        List<Long> destUids = new ArrayList<>();

        for (int idx : indices) {
            if (idx < 0 || idx >= messages.size()) continue;
            ImapMessage src = messages.get(idx);
            long newUid = mailStorage.copyMessage(
                    username, selectedMailbox.getName(), src.getUid(), destMailbox);
            srcUids.add(src.getUid());
            destUids.add(newUid);
        }

        ImapMailbox destMb = mailStorage.getMailbox(username, destMailbox);

        if (!srcUids.isEmpty()) {
            // RFC 9051 §6.4.8 — MUST send COPYUID in untagged OK BEFORE EXPUNGE
            sendUntagged("OK [COPYUID " + destMb.getUidValidity()
                    + " " + toUidSetStr(srcUids)
                    + " " + toUidSetStr(destUids) + "] Moved");

            // Delete originals (mark \\Deleted, then expunge by UID)
            for (Long uid : srcUids) {
                mailStorage.deleteMessage(username, selectedMailbox.getName(), uid);
            }

            // Send EXPUNGE responses
            messages = mailStorage.loadMessages(username, selectedMailbox.getName());
            // Re-index — we already deleted, so reload reflects removed messages
            // The pre-delete indices guide which sequence numbers to EXPUNGE
            // (We report seqnum before each removal, highest first)
            for (int i = indices.size() - 1; i >= 0; i--) {
                sendUntagged((indices.get(i) + 1) + " EXPUNGE");
            }

            messages = mailStorage.loadMessages(username, selectedMailbox.getName());
        }

        sendTagged(tag, "OK MOVE completed");
    }

    /**
     * UID command — RFC 9051 §6.4.9
     * UID FETCH, UID STORE, UID COPY, UID MOVE, UID SEARCH, UID EXPUNGE
     */
    private void handleUid(String tag, String args) {
        String[] parts  = args.split(" ", 2);
        String subCmd   = parts[0].toUpperCase();
        String subArgs  = parts.length > 1 ? parts[1] : "";

        switch (subCmd) {
            case "FETCH":   handleFetch(tag, subArgs, true);   break;
            case "STORE":   handleStore(tag, subArgs, true);   break;
            case "COPY":    handleCopy(tag, subArgs, true);    break;
            case "MOVE":    handleMove(tag, subArgs, true);    break;
            case "SEARCH":  handleSearch(tag, subArgs, true);  break;
            case "EXPUNGE": handleUidExpunge(tag, subArgs);    break;
            default:
                sendTagged(tag, "BAD Unknown UID command: " + subCmd);
        }
    }

    /**
     * UID EXPUNGE uidset — RFC 9051 §6.4.9
     * Expunges only messages in the given UID set that are also \\Deleted.
     */
    private void handleUidExpunge(String tag, String args) {
        if (readOnly) {
            sendTagged(tag, "NO [READ-ONLY] Mailbox is read-only");
            return;
        }
        Set<Long> uidSet = parseUidSet(args.trim());
        doExpunge(tag, uidSet);
    }

    // ── Search engine ─────────────────────────────────────────────────────────

    /**
     * Evaluate a SEARCH criteria string against the message list.
     * Handles AND (multiple criteria), NOT, OR, and all basic keys.
     */
    private List<ImapMessage> searchMessages(String criteria, List<ImapMessage> pool) {
        if (criteria.trim().equalsIgnoreCase("ALL")) return new ArrayList<>(pool);

        return pool.stream()
                .filter(msg -> matchesCriteria(msg, criteria.trim()))
                .collect(Collectors.toList());
    }

    private boolean matchesCriteria(ImapMessage msg, String criteria) {
        criteria = criteria.trim();
        if (criteria.isEmpty()) return true;

        String upper = criteria.toUpperCase();

        if (upper.startsWith("NOT ")) {
            return !matchesCriteria(msg, criteria.substring(4).trim());
        }
        if (upper.startsWith("OR ")) {
            String rest = criteria.substring(3).trim();
            String[] orParts = splitTwoKeys(rest);
            if (orParts != null)
                return matchesCriteria(msg, orParts[0]) || matchesCriteria(msg, orParts[1]);
        }

        // Flag criteria
        if (upper.equals("ALL"))         return true;
        if (upper.equals("SEEN"))        return msg.hasFlag("\\Seen");
        if (upper.equals("UNSEEN"))      return !msg.hasFlag("\\Seen");
        if (upper.equals("DELETED"))     return msg.hasFlag("\\Deleted");
        if (upper.equals("UNDELETED"))   return !msg.hasFlag("\\Deleted");
        if (upper.equals("FLAGGED"))     return msg.hasFlag("\\Flagged");
        if (upper.equals("UNFLAGGED"))   return !msg.hasFlag("\\Flagged");
        if (upper.equals("ANSWERED"))    return msg.hasFlag("\\Answered");
        if (upper.equals("UNANSWERED"))  return !msg.hasFlag("\\Answered");
        if (upper.equals("DRAFT"))       return msg.hasFlag("\\Draft");
        if (upper.equals("UNDRAFT"))     return !msg.hasFlag("\\Draft");

        // Header/content criteria
        if (upper.startsWith("FROM "))     return msg.headerContains("From",    argOf(criteria));
        if (upper.startsWith("TO "))       return msg.headerContains("To",      argOf(criteria));
        if (upper.startsWith("CC "))       return msg.headerContains("Cc",      argOf(criteria));
        if (upper.startsWith("BCC "))      return msg.headerContains("Bcc",     argOf(criteria));
        if (upper.startsWith("SUBJECT "))  return msg.headerContains("Subject", argOf(criteria));
        if (upper.startsWith("BODY "))     return msg.bodyContains(argOf(criteria));
        if (upper.startsWith("TEXT "))     return msg.fullContains(argOf(criteria));
        if (upper.startsWith("LARGER "))   return msg.getSize() > parseLong(argOf(criteria));
        if (upper.startsWith("SMALLER "))  return msg.getSize() < parseLong(argOf(criteria));
        if (upper.startsWith("UID "))      return matchesUidSet(msg.getUid(), argOf(criteria));
        if (upper.startsWith("KEYWORD "))  return msg.hasFlag(argOf(criteria));
        if (upper.startsWith("UNKEYWORD "))return !msg.hasFlag(argOf(criteria));

        // Multiple space-separated criteria → AND
        // Try to split into two tokens and AND them
        if (criteria.contains(" ")) {
            String[] tokens = splitTwoKeys(criteria);
            if (tokens != null)
                return matchesCriteria(msg, tokens[0]) && matchesCriteria(msg, tokens[1]);
        }

        return true; // unknown criteria — pass through
    }

    // ── Sequence/UID set parsers ──────────────────────────────────────────────

    /** Resolve a sequence set like "1", "1:3", "1,3,5:7", "*" to 0-based indices. */
    private List<Integer> resolveSeqSet(String seqSet) {
        List<Integer> result = new ArrayList<>();
        int maxSeq = messages.size();
        for (String part : seqSet.split(",")) {
            part = part.trim();
            if (part.contains(":")) {
                String[] range = part.split(":");
                int lo = parseSeqNum(range[0], maxSeq);
                int hi = parseSeqNum(range[1], maxSeq);
                if (lo > hi) { int tmp = lo; lo = hi; hi = tmp; }
                for (int i = lo; i <= hi; i++)
                    if (i >= 1 && i <= maxSeq) result.add(i - 1);
            } else {
                int n = parseSeqNum(part, maxSeq);
                if (n >= 1 && n <= maxSeq) result.add(n - 1);
            }
        }
        return result;
    }

    /** Resolve a UID set to 0-based message indices. */
    private List<Integer> resolveUidSet(String uidSet) {
        Set<Long> uids = parseUidSet(uidSet);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (uids.contains(messages.get(i).getUid())) result.add(i);
        }
        return result;
    }

    private Set<Long> parseUidSet(String uidSet) {
        Set<Long> result = new HashSet<>();
        long maxUid = messages.stream().mapToLong(ImapMessage::getUid).max().orElse(0);
        for (String part : uidSet.split(",")) {
            part = part.trim();
            if (part.contains(":")) {
                String[] range = part.split(":");
                long lo = parseUidNum(range[0], maxUid);
                long hi = parseUidNum(range[1], maxUid);
                if (lo > hi) { long tmp = lo; lo = hi; hi = tmp; }
                for (long u = lo; u <= hi; u++) result.add(u);
            } else {
                result.add(parseUidNum(part, maxUid));
            }
        }
        return result;
    }

    private boolean matchesUidSet(long uid, String uidSet) {
        return parseUidSet(uidSet).contains(uid);
    }

    private int  parseSeqNum(String s, int max) { return s.equals("*") ? max : Integer.parseInt(s); }
    private long parseUidNum(String s, long max) { return s.equals("*") ? max : Long.parseLong(s); }

    // ── FETCH helpers ─────────────────────────────────────────────────────────

    private List<String> parseFetchItems(String itemsStr) {
        itemsStr = itemsStr.trim();
        if (itemsStr.startsWith("(") && itemsStr.endsWith(")"))
            itemsStr = itemsStr.substring(1, itemsStr.length() - 1).trim();

        // Split on spaces, but keep BODY[...] as one token
        List<String> items = new ArrayList<>();
        int i = 0;
        StringBuilder current = new StringBuilder();
        while (i < itemsStr.length()) {
            char c = itemsStr.charAt(i);
            if (c == '[') {
                while (i < itemsStr.length() && itemsStr.charAt(i) != ']') {
                    current.append(itemsStr.charAt(i++));
                }
                current.append(']');
                i++;
            } else if (c == ' ' && current.length() > 0) {
                items.add(current.toString().trim());
                current = new StringBuilder();
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        if (current.length() > 0) items.add(current.toString().trim());
        return items;
    }

    /** Extract section spec from BODY[section] or BODY.PEEK[section] */
    private String extractSection(String item) {
        int open  = item.indexOf('[');
        int close = item.indexOf(']');
        if (open < 0 || close < 0) return "";
        return item.substring(open + 1, close).toUpperCase();
    }

    /** Fetch the content of a body section. */
    private String fetchBodySection(ImapMessage msg, String section) {
        try {
            List<String> allLines = msg.getLines();
            if (section.isEmpty() || section.equals("")) {
                // BODY[] — entire message
                return String.join("\r\n", allLines);
            }
            if (section.equals("HEADER")) {
                return extractHeaders(allLines);
            }
            if (section.equals("TEXT")) {
                return extractBody(allLines);
            }
            if (section.startsWith("HEADER.FIELDS ")) {
                String fieldList = section.substring(15).trim();
                fieldList = fieldList.replaceAll("[()]", "");
                Set<String> fields = new HashSet<>(Arrays.asList(fieldList.split("\\s+")));
                return extractHeaderFields(allLines, fields, false);
            }
            if (section.startsWith("HEADER.FIELDS.NOT ")) {
                String fieldList = section.substring(18).trim();
                fieldList = fieldList.replaceAll("[()]", "");
                Set<String> fields = new HashSet<>(Arrays.asList(fieldList.split("\\s+")));
                return extractHeaderFields(allLines, fields, true);
            }
            // Default: full message
            return String.join("\r\n", allLines);
        } catch (IOException e) {
            return "";
        }
    }

    private String extractHeaders(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) { sb.append("\r\n"); break; }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    private String extractBody(List<String> lines) {
        boolean inBody = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!inBody) { if (line.isEmpty()) inBody = true; continue; }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    private String extractHeaderFields(List<String> lines, Set<String> fields, boolean exclude) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) { sb.append("\r\n"); break; }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toUpperCase();
                boolean inList = fields.stream().anyMatch(f -> f.equalsIgnoreCase(name));
                if (inList != exclude) sb.append(line).append("\r\n");
            }
        }
        return sb.toString();
    }

    /** Build a minimal ENVELOPE structure for a message. */
    private String buildEnvelope(ImapMessage msg) {
        // RFC 9051 §7.5.2 — envelope is a parenthesized list of 10 fields
        // date, subject, from, sender, reply-to, to, cc, bcc, in-reply-to, message-id
        String date      = nstring(msg.getHeader("Date"));
        String subject   = nstring(msg.getHeader("Subject"));
        String from      = addressList(msg.getHeader("From"));
        String sender    = addressList(msg.getHeader("Sender", msg.getHeader("From")));
        String replyTo   = addressList(msg.getHeader("Reply-To", msg.getHeader("From")));
        String to        = addressList(msg.getHeader("To"));
        String cc        = addressList(msg.getHeader("Cc"));
        String bcc       = addressList(msg.getHeader("Bcc"));
        String inReplyTo = nstring(msg.getHeader("In-Reply-To"));
        String messageId = nstring(msg.getHeader("Message-Id"));

        return "(" + date + " " + subject + " " + from + " " + sender + " "
                + replyTo + " " + to + " " + cc + " " + bcc + " "
                + inReplyTo + " " + messageId + ")";
    }

    /** Build a minimal BODYSTRUCTURE for a simple text message. */
    private String buildBodyStructure(ImapMessage msg) {
        // Simplified: assume TEXT/PLAIN for all messages
        String contentType = msg.getHeader("Content-Type");
        String type    = "TEXT";
        String subtype = "PLAIN";
        if (contentType != null && contentType.contains("/")) {
            String[] ct = contentType.split("/", 2);
            type    = ct[0].trim().toUpperCase();
            subtype = ct[1].split(";")[0].trim().toUpperCase();
        }
        return "(" + "\"" + type + "\" \"" + subtype + "\" "
                + "NIL NIL NIL \"7BIT\" " + msg.getSize() + " "
                + countLines(msg) + ")";
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private void sendMailboxUpdates() {
        if (selectedMailbox == null) return;
        List<ImapMessage> fresh = mailStorage.loadMessages(username, selectedMailbox.getName());
        if (fresh.size() != messages.size()) {
            sendUntagged(fresh.size() + " EXISTS");
            messages = fresh;
        }
    }

    private void sendTagged(String tag, String response) {
        String line = tag + " " + response;
        out.println(line);
        logServer(line);
    }

    private void sendUntagged(String response) {
        String line = "* " + response;
        out.println(line);
        logServer(line);
    }

    private void sendRaw(String line) {
        out.println(line);
        logServer(line);
    }

    private void logClient(String msg) {
        log.fine("C: " + msg);
        if (logListener != null)
            logListener.onLog("CLIENT [" + socket.getInetAddress().getHostAddress() + "]", msg);
    }

    private void logServer(String msg) {
        log.fine("S: " + msg);
        if (logListener != null) logListener.onLog("SERVER", msg);
    }

    // ── String / parsing utilities ────────────────────────────────────────────

    /** Remove surrounding double-quotes from a quoted string. */
    private String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
            return s.substring(1, s.length() - 1);
        return s;
    }

    /** Wrap in double-quotes if the mailbox name contains spaces. */
    private String quoteMailbox(String name) {
        return (name.contains(" ") || name.isEmpty()) ? "\"" + name + "\"" : name;
    }

    /**
     * Parse space-separated arguments, respecting quoted strings.
     * Returns an array of at most maxParts arguments.
     */
    private String[] parseArgs(String args, int maxParts) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.length() && result.size() < maxParts) {
            // Skip leading spaces
            while (i < args.length() && args.charAt(i) == ' ') i++;
            if (i >= args.length()) break;

            if (args.charAt(i) == '"') {
                // Quoted string
                int end = args.indexOf('"', i + 1);
                if (end < 0) end = args.length() - 1;
                result.add(args.substring(i + 1, end));
                i = end + 1;
            } else {
                // Unquoted token
                int end = args.indexOf(' ', i);
                if (end < 0 || result.size() == maxParts - 1) end = args.length();
                result.add(args.substring(i, end));
                i = end;
            }
        }
        return result.toArray(new String[0]);
    }

    /** Get the first argument of a search criterion (e.g. FROM "Smith" → Smith). */
    private String argOf(String criterion) {
        int space = criterion.indexOf(' ');
        if (space < 0) return "";
        return unquote(criterion.substring(space + 1).trim());
    }

    /** Split a criteria string into two independent search keys. */
    private String[] splitTwoKeys(String criteria) {
        // Very simplified: split at first unquoted space
        criteria = criteria.trim();
        int i = 0;
        boolean inQuote = false;
        while (i < criteria.length()) {
            char c = criteria.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (c == ' ' && !inQuote) {
                return new String[]{ criteria.substring(0, i), criteria.substring(i + 1) };
            }
            i++;
        }
        return null;
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Convert a list of sequence numbers or UIDs to IMAP sequence-set string. */
    private String toSequenceSet(List<Object> nums) {
        if (nums.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(nums.get(i));
        }
        return sb.toString();
    }

    private String toUidSetStr(List<Long> uids) {
        return uids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String nstring(String s) {
        if (s == null || s.isEmpty()) return "NIL";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String addressList(String addr) {
        if (addr == null || addr.isEmpty()) return "NIL";
        // Simplified: wrap in address structure
        return "((" + nstring(null) + " NIL " + nstring(addr) + " NIL))";
    }

    private String formatInternalDate(long timestamp) {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", java.util.Locale.ENGLISH);
        return sdf.format(new java.util.Date(timestamp));
    }

    private int countLines(ImapMessage msg) {
        try { return msg.getLines().size(); }
        catch (IOException e) { return 0; }
    }
}
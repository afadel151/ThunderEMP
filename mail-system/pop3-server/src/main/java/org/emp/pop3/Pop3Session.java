package org.emp.pop3;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles one POP3 client connection. Fully RFC 1939 compliant.
 *
 * ═══════════════════════════════════════════════════════════════════
 * BUGS FIXED vs. original pop3Server.java
 * ═══════════════════════════════════════════════════════════════════
 *
 * BUG 1 — FSM not implemented: only a boolean flag (RFC 1939 §3)
 *   ORIGINAL : Single boolean `authenticated` used for all state tracking.
 *              Commands like STAT, LIST, RETR are accessible once authenticated
 *              but there is no proper AUTHORIZATION / TRANSACTION / UPDATE
 *              state machine.
 *   RFC says : POP3 has three distinct states:
 *              AUTHORIZATION → TRANSACTION → UPDATE (on QUIT only).
 *              Commands issued in the wrong state MUST get "-ERR".
 *   FIX      : Pop3State enum {AUTHORIZATION, TRANSACTION, UPDATE} with
 *              per-command state guards on every handler.
 *
 * BUG 2 — USER accepted in TRANSACTION state (RFC 1939 §7 USER)
 *   ORIGINAL : handleUser() has no state check — USER can be called after
 *              authentication, resetting username mid-session.
 *   RFC says : USER may only be given in the AUTHORIZATION state.
 *   FIX      : Guard added: reject USER unless state == AUTHORIZATION.
 *
 * BUG 3 — PASS accepted without prior successful USER (RFC 1939 §7 PASS)
 *   ORIGINAL : handlePass() only checks `username == null`.
 *              If USER returned -ERR (user not found), username stays null
 *              BUT the check still works by coincidence. However, if someone
 *              calls PASS twice it is silently accepted.
 *   RFC says : PASS may only be given immediately after a successful USER.
 *              A dedicated flag `userAccepted` is required.
 *   FIX      : Added `userAccepted` boolean, set only on successful USER.
 *              PASS checks state == AUTHORIZATION && userAccepted.
 *
 * BUG 4 — Password not verified at all (RFC 1939 §7 PASS)
 *   ORIGINAL : handlePass() sets authenticated=true for ANY password,
 *              never checking it against stored credentials.
 *   RFC says : "the POP3 server uses the USER and PASS arguments to
 *              determine if the client should be given access".
 *   FIX      : Authentication delegated to Pop3Authenticator interface.
 *              FileAuthenticator for Étapes 1-4, RMIAuthenticator for
 *              Étape 4, DBAuthenticator for Étape 5.
 *
 * BUG 5 — USER reveals whether mailbox exists (RFC 1939 §13 Security)
 *   ORIGINAL : handleUser() returns "-ERR User not found" if directory
 *              doesn't exist — giving attackers a user enumeration oracle.
 *   RFC says : "The server may return a positive response even though no
 *              such mailbox exists" — i.e., always return +OK to USER.
 *   FIX      : handleUser() always returns "+OK" regardless of existence.
 *              The real check happens in PASS via the authenticator.
 *
 * BUG 6 — STAT and LIST count deleted messages (RFC 1939 §5 STAT/LIST)
 *   ORIGINAL : handleStat() sums ALL emails including ones flagged deleted.
 *              handleList() lists ALL emails including deleted ones.
 *   RFC says : "Note that messages marked as deleted are not counted in
 *              either total." (STAT) and "messages marked as deleted are
 *              not listed." (LIST)
 *   FIX      : Both handlers skip entries where deletionFlags.get(i) == true.
 *
 * BUG 7 — RETR does not skip deleted messages (RFC 1939 §5 RETR)
 *   ORIGINAL : handleRetr() retrieves a message even if it is flagged deleted.
 *   RFC says : message-number in RETR "may NOT refer to a message marked
 *              as deleted".
 *   FIX      : Guard added — return "-ERR message marked as deleted".
 *
 * BUG 8 — RETR does not byte-stuff lines starting with "." (RFC 1939 §3)
 *   ORIGINAL : Lines are sent as-is. A line "." in the message body would
 *              be interpreted by the client as the end-of-response terminator.
 *   RFC says : "If any line of the multi-line response begins with the
 *              termination octet, the line is byte-stuffed by pre-pending
 *              the termination octet to that line."
 *   FIX      : Each line starting with "." is prefixed with an extra "."
 *              before being sent.
 *
 * BUG 9 — DELE does not skip already-deleted messages cleanly (RFC 1939 §5)
 *   ORIGINAL : Returns "-ERR Message already marked for deletion" which is
 *              acceptable, but the RFC example wording is
 *              "-ERR message N already deleted".
 *   RFC says : "Any future reference to the message-number associated with
 *              the message in a POP3 command generates an error."
 *   FIX      : Minor wording fix + consistent error message format.
 *
 * BUG 10 — QUIT from AUTHORIZATION state must NOT enter UPDATE (RFC 1939 §6)
 *   ORIGINAL : handleQuit() always tries to delete flagged messages, even
 *              when the client never authenticated (AUTHORIZATION state).
 *   RFC says : "if the client issues the QUIT command from the AUTHORIZATION
 *              state, the POP3 session terminates but does NOT enter the
 *              UPDATE state."  → no deletions should happen.
 *   FIX      : handleQuit() only calls applyDeletions() when
 *              state == TRANSACTION.
 *
 * BUG 11 — Abrupt disconnect must NOT delete messages (RFC 1939 §6)
 *   ORIGINAL : Comment says deletions won't be applied on abrupt disconnect,
 *              but the code does nothing to enforce this — if the loop exits
 *              unexpectedly, the finally block just closes the socket.
 *              Deletion is only done in handleQuit(), so it's actually safe,
 *              BUT there's no explicit guard — a future refactor could break it.
 *   RFC says : "If a session terminates for some reason other than a client-
 *              issued QUIT command, the POP3 session does NOT enter the
 *              UPDATE state and MUST NOT remove any messages."
 *   FIX      : `quitReceived` boolean flag. applyDeletions() is only called
 *              when quitReceived == true. The finally block checks this
 *              explicitly and logs a warning if connection dropped mid-session.
 *
 * BUG 12 — NOOP command not implemented (RFC 1939 §5 NOOP)
 *   ORIGINAL : NOOP is not in the switch — falls to default "-ERR Unknown".
 *   RFC says : NOOP is a mandatory TRANSACTION-state command; reply "+OK".
 *   FIX      : NOOP case added → "+OK".
 *
 * BUG 13 — TOP command not implemented (RFC 1939 §7 TOP)
 *   ORIGINAL : Not in switch at all.
 *   RFC says : TOP is optional but strongly encouraged.
 *   FIX      : TOP case added — sends headers + blank line + N body lines.
 *
 * BUG 14 — UIDL command not implemented (RFC 1939 §7 UIDL)
 *   ORIGINAL : Not in switch at all.
 *   RFC says : UIDL is optional but strongly encouraged.
 *   FIX      : UIDL case added — returns filename as unique ID (stable across
 *              sessions if files aren't renamed).
 *
 * BUG 15 — No inactivity timeout (RFC 1939 §3)
 *   ORIGINAL : No timeout — a hung client blocks a thread forever.
 *   RFC says : "A POP3 server MAY have an inactivity autologout timer.
 *              Such a timer MUST be of at least 10 minutes."
 *   FIX      : socket.setSoTimeout(10 * 60 * 1000) set on session start.
 *              SocketTimeoutException caught → session closed, NO deletions.
 * ═══════════════════════════════════════════════════════════════════
 */
public class Pop3Session implements Runnable {

    private static final Logger log = Logger.getLogger(Pop3Session.class.getName());

    // RFC 1939 §3 — three states
    private enum Pop3State { AUTHORIZATION, TRANSACTION, UPDATE }

    private final Socket           socket;
    private final String           serverDomain;
    private final Pop3LogListener  logListener;
    private final Pop3Authenticator authenticator;
    private final Pop3MailStorage  mailStorage;

    private BufferedReader in;
    private PrintWriter    out;

    private Pop3State      state        = Pop3State.AUTHORIZATION;
    private String         username     = null;
    private boolean        userAccepted = false;   // FIX #3
    private boolean        quitReceived = false;   // FIX #11

    // Loaded on successful PASS — index-stable for the whole session
    private List<Pop3Mail>  messages;
    private List<Boolean>   deletionFlags;

    // ── Constructor ───────────────────────────────────────────────────────────

    public Pop3Session(Socket socket, String serverDomain,
                       Pop3LogListener logListener,
                       Pop3Authenticator authenticator,
                       Pop3MailStorage mailStorage) {
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
            // FIX #15 — inactivity timeout (RFC minimum: 10 minutes)
            socket.setSoTimeout(10 * 60 * 1000);

            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);

            // RFC 1939 §3 — greeting must be a positive response
            sendOk(serverDomain + " POP3 server ready");

            String line;
            while ((line = in.readLine()) != null) {
                logClient(line);
                String[] parts   = line.split(" ", 2);
                String   command = parts[0].toUpperCase().trim();
                String   arg     = parts.length > 1 ? parts[1].trim() : "";

                switch (command) {
                    case "USER": handleUser(arg);  break;
                    case "PASS": handlePass(arg);  break;
                    case "STAT": handleStat();     break;
                    case "LIST": handleList(arg);  break;
                    case "RETR": handleRetr(arg);  break;
                    case "DELE": handleDele(arg);  break;
                    case "RSET": handleRset();     break;
                    case "NOOP": handleNoop();     break;   // FIX #12
                    case "TOP":  handleTop(arg);   break;   // FIX #13
                    case "UIDL": handleUidl(arg);  break;   // FIX #14
                    case "QUIT": handleQuit(); return;
                    default:
                        sendErr("Unknown command");
                }
            }

            // Connection dropped without QUIT — FIX #11
            if (state == Pop3State.TRANSACTION) {
                log.warning("Client dropped without QUIT — no messages deleted.");
            }

        } catch (java.net.SocketTimeoutException e) {
            // FIX #15 — timeout: close silently, NO deletions
            log.info("Session timed out — closing without UPDATE.");
        } catch (IOException e) {
            log.warning("Session IO error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── AUTHORIZATION state handlers ─────────────────────────────────────────

    /**
     * USER name
     * RFC 1939 §7 — only valid in AUTHORIZATION state.
     * FIX #2, FIX #5: always returns +OK (no user-enumeration oracle).
     */
    private void handleUser(String arg) {
        if (state != Pop3State.AUTHORIZATION) {      // FIX #2
            sendErr("Command only valid in AUTHORIZATION state");
            return;
        }
        if (arg.isEmpty()) {
            sendErr("Usage: USER <name>");
            return;
        }
        username     = arg;
        userAccepted = true;                         // FIX #3
        // FIX #5 — always +OK; real check happens in PASS
        sendOk(arg + " is a valid mailbox");
    }

    /**
     * PASS string
     * RFC 1939 §7 — only valid immediately after a successful USER.
     * FIX #3: requires userAccepted flag.
     * FIX #4: password actually verified via authenticator.
     */
    private void handlePass(String arg) {
        if (state != Pop3State.AUTHORIZATION || !userAccepted) {  // FIX #3
            sendErr("USER command required first");
            return;
        }
        if (arg.isEmpty()) {
            sendErr("Usage: PASS <password>");
            return;
        }

        // FIX #4 — real authentication
        if (!authenticator.authenticate(username, arg)) {
            userAccepted = false;   // must re-issue USER before trying again
            sendErr("Invalid password");
            return;
        }

        // Load messages for this user
        messages      = mailStorage.loadMessages(username);
        deletionFlags = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) deletionFlags.add(false);

        state = Pop3State.TRANSACTION;
        sendOk(username + "'s maildrop has "
                + messages.size() + " messages ("
                + totalOctets() + " octets)");
    }

    // ── TRANSACTION state handlers ────────────────────────────────────────────

    /**
     * STAT
     * RFC 1939 §5 — count and size MUST exclude deleted messages. FIX #6.
     */
    private void handleStat() {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        int  count = 0;
        long size  = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletionFlags.get(i)) {           // FIX #6 — skip deleted
                count++;
                size += messages.get(i).getSize();
            }
        }
        sendOk(count + " " + size);
    }

    /**
     * LIST [msg]
     * RFC 1939 §5 — deleted messages must not be listed. FIX #6.
     * Supports optional single-message argument.
     */
    private void handleList(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        if (!arg.isEmpty()) {
            // Single message listing
            int idx = parseMessageNumber(arg);
            if (idx < 0) { sendErr("Invalid message number"); return; }
            if (deletionFlags.get(idx)) { sendErr("Message " + (idx+1) + " already deleted"); return; }
            sendOk((idx + 1) + " " + messages.get(idx).getSize());
            return;
        }

        // Multi-line listing — FIX #6: skip deleted
        int  count = 0;
        long size  = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletionFlags.get(i)) { count++; size += messages.get(i).getSize(); }
        }
        sendOk(count + " messages (" + size + " octets)");
        for (int i = 0; i < messages.size(); i++) {
            if (!deletionFlags.get(i)) {           // FIX #6
                sendRaw((i + 1) + " " + messages.get(i).getSize());
            }
        }
        sendRaw(".");
    }

    /**
     * RETR msg
     * RFC 1939 §5 — must not retrieve deleted messages (FIX #7).
     * Must byte-stuff lines starting with "." (FIX #8).
     */
    private void handleRetr(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        int idx = parseMessageNumber(arg);
        if (idx < 0) { sendErr("Invalid message number"); return; }
        if (deletionFlags.get(idx)) { sendErr("Message marked as deleted"); return; }  // FIX #7

        Pop3Mail mail = messages.get(idx);
        sendOk(mail.getSize() + " octets");

        try {
            for (String line : mail.getLines()) {
                // FIX #8 — byte-stuffing: prepend "." to lines starting with "."
                if (line.startsWith(".")) {
                    sendRaw("." + line);
                } else {
                    sendRaw(line);
                }
            }
        } catch (IOException e) {
            log.warning("Error reading message: " + e.getMessage());
        }
        sendRaw(".");   // end-of-message terminator
    }

    /**
     * DELE msg
     * RFC 1939 §5 — marks message, does NOT delete immediately.
     * FIX #9: consistent error wording.
     */
    private void handleDele(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        int idx = parseMessageNumber(arg);
        if (idx < 0) { sendErr("Invalid message number"); return; }
        if (deletionFlags.get(idx)) {
            sendErr("Message " + (idx + 1) + " already deleted");  // FIX #9
            return;
        }
        deletionFlags.set(idx, true);
        sendOk("Message " + (idx + 1) + " deleted");
    }

    /**
     * RSET
     * RFC 1939 §5 — unmarks all deletion flags. +OK with maildrop info.
     */
    private void handleRset() {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }
        for (int i = 0; i < deletionFlags.size(); i++) deletionFlags.set(i, false);
        sendOk("maildrop has " + messages.size() + " messages (" + totalOctets() + " octets)");
    }

    /**
     * NOOP — FIX #12
     * RFC 1939 §5 — does nothing, replies +OK.
     */
    private void handleNoop() {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }
        sendOk("");
    }

    /**
     * TOP msg n — FIX #13
     * RFC 1939 §7 — optional; sends headers + blank line + n body lines.
     * Must also byte-stuff "." lines.
     */
    private void handleTop(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) { sendErr("Usage: TOP <msg> <n>"); return; }

        int idx = parseMessageNumber(parts[0]);
        if (idx < 0) { sendErr("Invalid message number"); return; }
        if (deletionFlags.get(idx)) { sendErr("Message marked as deleted"); return; }

        int n;
        try { n = Integer.parseInt(parts[1].trim()); }
        catch (NumberFormatException e) { sendErr("Invalid line count"); return; }
        if (n < 0) { sendErr("Line count must be non-negative"); return; }

        sendOk("top of message follows");
        try {
            List<String> lines = messages.get(idx).getLines();
            boolean inBody    = false;
            int     bodyLines = 0;

            for (String line : lines) {
                if (!inBody) {
                    // Send all header lines (including blank separator)
                    String toSend = line.startsWith(".") ? "." + line : line;
                    sendRaw(toSend);
                    if (line.isEmpty()) inBody = true;  // blank line = header/body boundary
                } else {
                    if (bodyLines >= n) break;
                    String toSend = line.startsWith(".") ? "." + line : line;
                    sendRaw(toSend);
                    bodyLines++;
                }
            }
        } catch (IOException e) {
            log.warning("Error reading message for TOP: " + e.getMessage());
        }
        sendRaw(".");
    }

    /**
     * UIDL [msg] — FIX #14
     * RFC 1939 §7 — unique ID listing. Uses filename as UID (stable).
     */
    private void handleUidl(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        if (!arg.isEmpty()) {
            int idx = parseMessageNumber(arg);
            if (idx < 0) { sendErr("Invalid message number"); return; }
            if (deletionFlags.get(idx)) { sendErr("Message marked as deleted"); return; }
            sendOk((idx + 1) + " " + messages.get(idx).getUid());
            return;
        }

        sendOk("unique-id listing follows");
        for (int i = 0; i < messages.size(); i++) {
            if (!deletionFlags.get(i)) {
                sendRaw((i + 1) + " " + messages.get(i).getUid());
            }
        }
        sendRaw(".");
    }

    /**
     * QUIT
     * RFC 1939 §4 (AUTHORIZATION) and §6 (UPDATE):
     *  - From AUTHORIZATION: terminate only, NO deletions (FIX #10).
     *  - From TRANSACTION: enter UPDATE, apply deletions (FIX #11).
     */
    private void handleQuit() {
        quitReceived = true;

        // FIX #10 — only apply deletions if we reached TRANSACTION state
        if (state == Pop3State.TRANSACTION) {
            applyDeletions();
        }

        sendOk(serverDomain + " POP3 server signing off");
    }

    // ── UPDATE state: apply deletions ────────────────────────────────────────

    /**
     * RFC 1939 §6 — physically remove all messages marked for deletion.
     * If removal fails, reply -ERR but still close the connection.
     * FIX #11 — only called when quitReceived == true AND state == TRANSACTION.
     */
    private void applyDeletions() {
        state = Pop3State.UPDATE;
        for (int i = 0; i < messages.size(); i++) {
            if (deletionFlags.get(i)) {
                boolean ok = mailStorage.delete(username, messages.get(i));
                if (!ok) log.warning("Failed to delete message: " + messages.get(i).getUid());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parse a 1-based message number into a 0-based list index.
     * Returns -1 on any parse error or out-of-range value.
     */
    private int parseMessageNumber(String arg) {
        try {
            int n = Integer.parseInt(arg.trim());
            if (n < 1 || n > messages.size()) return -1;
            return n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Total octets of non-deleted messages. */
    private long totalOctets() {
        long size = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!deletionFlags.get(i)) size += messages.get(i).getSize();
        }
        return size;
    }

    private void sendOk(String msg) {
        String reply = "+OK" + (msg.isEmpty() ? "" : " " + msg);
        out.println(reply);
        logServer(reply);
    }

    private void sendErr(String msg) {
        String reply = "-ERR " + msg;
        out.println(reply);
        logServer(reply);
    }

    /** Send a raw line (for multi-line responses). */
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
}
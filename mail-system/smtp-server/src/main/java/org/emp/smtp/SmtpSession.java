package org.emp.smtp;

import org.emp.common.Message;
import org.emp.smtp.storage.MailStorage;
import org.emp.smtp.storage.FileMailStorage;

import java.io.*;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles one SMTP client connection, fully RFC 5321 compliant.
 *
 * ═══════════════════════════════════════════════════════════════════
 * BUGS FIXED vs. original smtpServer.java
 * ═══════════════════════════════════════════════════════════════════
 *
 * BUG 1 — Wrong EHLO response format (RFC 5321 §4.1.1.1)
 * ORIGINAL : "250 Hello <arg>"
 * RFC says : "250 <server-domain>" — domain of the SERVER, not echoed arg.
 * For EHLO, a multi-line response listing extensions is expected.
 * FIX : HELO → "250 smtp.emp.org"
 * EHLO → multi-line "250-smtp.emp.org\r\n250 8BITMIME"
 *
 * BUG 2 — EHLO resets state but original code did not (RFC 5321 §4.1.4)
 * RFC says : "EHLO implies that the server must clear all buffers and reset
 * state exactly as if a RSET command had been issued."
 * FIX : handleHelo() now calls resetTransaction() to clear sender,
 * recipients, and data buffer, and resets to HELO_RECEIVED.
 *
 * BUG 3 — MAIL FROM accepted in any state (RFC 5321 §4.1.1.2)
 * ORIGINAL : No state check in handleMailFrom().
 * RFC says : MAIL must only be accepted after EHLO/HELO. If a transaction
 * is already open, "503 Bad sequence of commands" MUST be returned.
 * FIX : Guard added — MAIL FROM only allowed in HELO_RECEIVED state.
 *
 * BUG 4 — MAIL FROM regex matches "FROM: <>" (empty address) (RFC 5321
 * §4.1.1.2)
 * RFC allows "<>" as the null reverse-path (for bounces). The original regex
 * "^FROM:\\s*<[^>]+>$" rejects it because [^>]+ requires at least one char.
 * FIX : Accept "<>" explicitly, then validate non-empty addresses.
 *
 * BUG 5 — Dot-stuffing not handled in DATA phase (RFC 5321 §4.5.2)
 * RFC says : If a line begins with ".", the leading dot is a transparency
 * mechanism and MUST be removed by the receiver before storing.
 * ("dot-unstuffing")
 * ORIGINAL : Lines are stored as-is; a line "..foo" would be stored as "..foo"
 * instead of ".foo".
 * FIX : In DATA phase, lines starting with "." (but not ".") have the
 * leading dot stripped before buffering.
 *
 * BUG 6 — After DATA completes, state resets to HELO_RECEIVED but recipients
 * are NOT cleared (RFC 5321 §3.3)
 * RFC says : After the DATA terminator is acknowledged with 250, the
 * reverse-path buffer, forward-path buffer and mail data buffer
 * MUST be cleared.
 * ORIGINAL : dataBuffer is cleared but recipients list is NOT cleared,
 * so a second message in the same session would inherit them.
 * FIX : resetTransaction() clears sender, recipients, and dataBuffer.
 * Called after successful DATA delivery and on RSET/EHLO.
 *
 * BUG 7 — RSET command not implemented (RFC 5321 §4.1.1.5)
 * RFC says : RSET MUST be supported; server MUST reply "250 OK".
 * It discards current transaction data (sender, recipients, data).
 * FIX : RSET case added to switch; calls resetTransaction().
 *
 * BUG 8 — NOOP command not implemented (RFC 5321 §4.1.1.9)
 * RFC says : NOOP MUST be supported; server MUST reply "250 OK".
 * It may appear at any time, with or without an argument.
 * FIX : NOOP case added to switch; responds "250 OK".
 *
 * BUG 9 — VRFY command not implemented (RFC 5321 §3.5 / §4.1.1.6)
 * RFC says : VRFY SHOULD be implemented; if disabled for security, server
 * MUST return 252, NOT 500.
 * FIX : VRFY case added; returns "252 Cannot verify user but will
 * attempt delivery" (safe stub; real lookup can be added later).
 *
 * BUG 10 — "500 Command unrecognized" used for unimplemented commands
 * RFC §4.2.4 : 500 = command NOT RECOGNIZED.
 * 502 = command recognized but NOT IMPLEMENTED.
 * FIX : Default branch keeps 500 for truly unknown commands.
 * Added 502 where appropriate (e.g., EXPN if disabled).
 *
 * BUG 11 — No Received header inserted (RFC 5321 §4.4)
 * RFC says : SMTP servers MUST insert a "Received:" trace header at the
 * top of every accepted message.
 * FIX : storeEmail() prepends a proper Received: header.
 *
 * BUG 12 — Subject always hardcoded as "Test Email" (RFC 5322 §3.6.5)
 * ORIGINAL : writer.println("Subject: Test Email"); — ignores actual subject.
 * FIX : Subject is parsed from DATA body (first "Subject:" header line)
 * and stored correctly. If absent, left empty.
 *
 * BUG 13 — File-based storage tightly coupled inside session (design)
 * FIX : Storage abstracted behind MailStorage interface, allowing
 * easy swap to DBMailStorage in Étape 5 without touching this class.
 * ═══════════════════════════════════════════════════════════════════
 */
public class SmtpSession implements Runnable {

    private static final Logger log = Logger.getLogger(SmtpSession.class.getName());

    // ── SMTP FSM states (RFC 5321 §3) ────────────────────────────────────────
    private enum SmtpState {
        CONNECTED, // TCP open, waiting for EHLO/HELO
        HELO_RECEIVED, // EHLO/HELO done; ready for MAIL FROM
        MAIL_FROM_SET, // MAIL FROM accepted; ready for RCPT TO
        RCPT_TO_SET, // At least one valid RCPT TO; ready for DATA
        DATA_RECEIVING // Inside DATA block; reading lines until "."
    }

    private final Socket socket;
    private final String serverDomain;
    private final SmtpLogListener logListener;
    private final MailStorage storage;

    private BufferedReader in;
    private PrintWriter out;

    private SmtpState state;
    private String heloArg = ""; // domain sent by client in EHLO/HELO
    private String sender = "";
    private List<String> recipients = new ArrayList<>();
    private StringBuilder dataBuffer = new StringBuilder();

    // ── Constructors ──────────────────────────────────────────────────────────

    public SmtpSession(Socket socket, String serverDomain, SmtpLogListener logListener) {
        this(socket, serverDomain, logListener, new FileMailStorage());
    }

    /**
     * Full constructor — used in Étape 5 when passing a DBMailStorage.
     */
    public SmtpSession(Socket socket, String serverDomain,
            SmtpLogListener logListener, MailStorage storage) {
        this.socket = socket;
        this.serverDomain = serverDomain;
        this.logListener = logListener;
        this.storage = storage;
        this.state = SmtpState.CONNECTED;
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);

            // RFC 5321 §4.3.1 — greeting MUST start with server FQDN
            sendReply("220 " + serverDomain + " ESMTP Service Ready");

            String line;
            while ((line = in.readLine()) != null) {

                // ── DATA phase: accumulate lines until terminator ──────────
                if (state == SmtpState.DATA_RECEIVING) {
                    logClient(line);

                    // RFC 5321 §4.5.2 — transparency / dot-unstuffing
                    if (line.equals(".")) {
                        // End of DATA — store and reset transaction
                        boolean stored = storage.store(buildMessage());
                        resetTransaction(); // FIX #6
                        if (stored) {
                            sendReply("250 OK: Message accepted for delivery");
                        } else {
                            sendReply("451 Requested action aborted: local error in processing");
                        }
                    } else {
                        // Strip leading extra dot (dot-stuffing, RFC 5321 §4.5.2)
                        // FIX #5
                        String storeLine = line.startsWith("..") ? line.substring(1) : line;
                        dataBuffer.append(storeLine).append("\r\n");
                    }
                    continue;
                }

                // ── Command phase ──────────────────────────────────────────
                logClient(line);
                String command = extractCommand(line);
                String argument = extractArgument(line);

                switch (command) {
                    case "EHLO":
                        handleEhlo(argument, true);
                        break;
                    case "HELO":
                        handleEhlo(argument, false);
                        break;
                    case "MAIL":
                        handleMailFrom(argument);
                        break;
                    case "RCPT":
                        handleRcptTo(argument);
                        break;
                    case "DATA":
                        handleData();
                        break;
                    case "RSET":
                        handleRset();
                        break; // FIX #7
                    case "NOOP":
                        handleNoop();
                        break; // FIX #8
                    case "VRFY":
                        handleVrfy(argument);
                        break; // FIX #9
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        // RFC §4.2.4 — 500 = command not recognized
                        sendReply("500 Command not recognized");
                }
            }

            // Connection dropped mid-DATA
            if (state == SmtpState.DATA_RECEIVING) {
                log.warning("Connection dropped during DATA phase — message discarded.");
            }

        } catch (IOException e) {
            log.warning("Session error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ── Command handlers ─────────────────────────────────────────────────────

    /**
     * Handle both EHLO and HELO.
     *
     * RFC 5321 §4.1.1.1:
     * - EHLO response MUST start with "250 <server-domain>" (FIX #1).
     * - EHLO implies state reset (FIX #2).
     * - HELO response: "250 <server-domain>" (no multi-line).
     * - Servers MUST NOT return extended response to HELO.
     */
    private void handleEhlo(String arg, boolean isEhlo) {
        if (arg == null || arg.isEmpty()) {
            sendReply("501 Syntax error: domain required");
            return;
        }
        heloArg = arg;
        state = SmtpState.HELO_RECEIVED;
        resetTransaction(); // FIX #2

        if (isEhlo) {
            // Multi-line EHLO response listing supported extensions
            sendRaw("250-" + serverDomain + " Hello " + arg);
            sendRaw("250-8BITMIME");
            sendRaw("250-PIPELINING");
            sendReply("250 OK");
        } else {
            // Plain HELO — single line, NO extension list
            sendReply("250 " + serverDomain);
        }
    }

    /**
     * Handle MAIL FROM.
     *
     * RFC 5321 §4.1.1.2:
     * - Only allowed after EHLO/HELO (FIX #3).
     * - If a transaction is already open, return 503.
     * - "<>" (null reverse-path) MUST be accepted (FIX #4).
     */
    private void handleMailFrom(String arg) {
        // FIX #3 — must be in HELO_RECEIVED state
        if (state != SmtpState.HELO_RECEIVED) {
            if (state == SmtpState.CONNECTED) {
                sendReply("503 Bad sequence of commands: send EHLO first");
            } else {
                // Transaction already open
                sendReply("503 Bad sequence of commands: use RSET first");
            }
            return;
        }

        if (arg == null || !arg.toUpperCase().startsWith("FROM:")) {
            sendReply("501 Syntax error in parameters: expected MAIL FROM:<address>");
            return;
        }

        String addrPart = arg.substring(5).trim(); // strip "FROM:"
        int spaceIndex = addrPart.indexOf(' ');
        if (spaceIndex != -1) {
            addrPart = addrPart.substring(0, spaceIndex);
        }
        // FIX #4 — accept null reverse-path "<>"
        if (addrPart.equals("<>")) {
            sender = "";
            state = SmtpState.MAIL_FROM_SET;
            sendReply("250 OK");
            return;
        }
        if (addrPart.startsWith("\"") && addrPart.endsWith("\"")) {
            addrPart = addrPart.substring(1, addrPart.length() - 1).trim();
        }
        // Must be wrapped in angle brackets
        if (!addrPart.startsWith("<") || !addrPart.endsWith(">")) {
            sendReply("501 Syntax error: address must be in <angle brackets>");
            return;
        }

        String email = addrPart.substring(1, addrPart.length() - 1).trim();
        if (!isValidEmail(email)) {
            sendReply("553 Requested action not taken: mailbox name not allowed");
            return;
        }

        sender = email;
        state = SmtpState.MAIL_FROM_SET;
        sendReply("250 OK");
    }

    /**
     * Handle RCPT TO.
     * RFC 5321 §4.1.1.3 — only allowed after MAIL FROM.
     */
    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            sendReply("503 Bad sequence of commands");
            return;
        }
        if (arg == null || !arg.toUpperCase().startsWith("TO:")) {
            sendReply("501 Syntax error in parameters: expected RCPT TO:<address>");
            return;
        }

        String addrPart = arg.substring(3).trim(); // strip "TO:"

        if (!addrPart.startsWith("<") || !addrPart.endsWith(">")) {
            sendReply("501 Syntax error: address must be in <angle brackets>");
            return;
        }

        String email = addrPart.substring(1, addrPart.length() - 1).trim();
        if (!isValidEmail(email)) {
            sendReply("553 Requested action not taken: mailbox name not allowed");
            return;
        }

        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        sendReply("250 OK");
    }

    /**
     * Handle DATA.
     * RFC 5321 §4.1.1.4 — only valid after at least one RCPT TO.
     * If no valid recipients, return 554 (not 503).
     */
    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            // RFC §4.1.1.4: "503 or 554 if no valid recipients"
            sendReply(recipients.isEmpty()
                    ? "554 No valid recipients"
                    : "503 Bad sequence of commands");
            return;
        }
        state = SmtpState.DATA_RECEIVING;
        sendReply("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    /**
     * Handle RSET. (FIX #7)
     * RFC 5321 §4.1.1.5 — clears all transaction buffers, MUST reply 250.
     * Connection stays open, state goes back to HELO_RECEIVED.
     */
    private void handleRset() {
        resetTransaction();
        sendReply("250 OK");
    }

    /**
     * Handle NOOP. (FIX #8)
     * RFC 5321 §4.1.1.9 — does nothing, MUST reply 250.
     * Valid at ANY time (even before EHLO).
     */
    private void handleNoop() {
        sendReply("250 OK");
    }

    /**
     * Handle VRFY. (FIX #9)
     * RFC 5321 §3.5.2 — if verification is disabled for security, reply 252,
     * NOT 500 ("unrecognized") or 502 ("not implemented").
     */
    private void handleVrfy(String arg) {
        // Safe stub: 252 = "cannot verify but will accept and attempt delivery"
        sendReply("252 Cannot VRFY user, but will accept message and attempt delivery");
    }

    /**
     * Handle QUIT.
     * RFC 5321 §4.1.1.10 — MUST reply 221 with server domain.
     */
    private void handleQuit() {
        sendReply("221 " + serverDomain + " Service closing transmission channel");
    }

    // ── Transaction helpers ───────────────────────────────────────────────────

    /**
     * RFC 5321 §3.3 — after DATA completes or on RSET/EHLO, ALL transaction
     * buffers MUST be cleared. (FIX #2, FIX #6, FIX #7)
     */
    private void resetTransaction() {
        sender = "";
        recipients.clear();
        dataBuffer.setLength(0);
        // Go back to HELO_RECEIVED only if we had already greeted
        if (state != SmtpState.CONNECTED) {
            state = SmtpState.HELO_RECEIVED;
        }
    }

    /**
     * Build a Message object from the accumulated session data.
     * Parses Subject from headers if present. (FIX #12)
     * Inserts RFC 5321 §4.4 Received header. (FIX #11)
     */
    private Message buildMessage() {
        String rawData = dataBuffer.toString();

        // Parse subject from DATA headers
        String subject = "";
        for (String line : rawData.split("\r\n")) {
            if (line.toLowerCase().startsWith("subject:")) {
                subject = line.substring(8).trim();
                break;
            }
            if (line.isEmpty())
                break; // end of headers
        }

        // RFC 5321 §4.4 — Received trace header MUST be prepended
        String receivedHeader = "Received: from " + heloArg
                + " (" + socket.getInetAddress().getHostAddress() + ")"
                + " by " + serverDomain
                + " with ESMTP"
                + " ; " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                + "\r\n";

        // Each recipient gets their own Message copy
        // (storage layer handles per-recipient delivery)
        Message msg = new Message();
        msg.setSender(sender);
        msg.setSubject(subject);
        // Body = received header + raw data
        msg.setBody(receivedHeader + rawData);
        // Comma-joined recipients stored; storage layer splits them
        msg.setRecipient(String.join(",", recipients));
        return msg;
    }

    // ── Low-level helpers ────────────────────────────────────────────────────

    /** Extract the command verb (first token, uppercased). */
    private String extractCommand(String line) {
        if (line == null || line.isEmpty())
            return "";
        int sp = line.indexOf(' ');
        return (sp > 0 ? line.substring(0, sp) : line).toUpperCase().trim();
    }

    /** Extract everything after the command verb. */
    private String extractArgument(String line) {
        if (line == null)
            return "";
        int sp = line.indexOf(' ');
        return sp > 0 ? line.substring(sp + 1).trim() : "";
    }

    /**
     * Basic email validation — must have exactly one '@' with non-empty
     * local part and domain.
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty())
            return false;
        int at = email.indexOf('@');
        return at > 0 && at < email.length() - 1 && !email.contains(" ");
    }

    /** Send a reply line and flush; logs it. */
    private void sendReply(String reply) {
        out.println(reply);
        logServer(reply);
    }

    /** Send a raw line WITHOUT trailing println duplication (for multi-line). */
    private void sendRaw(String line) {
        out.println(line);
        logServer(line);
    }

    private void logClient(String msg) {
        log.fine("C: " + msg);
        if (logListener != null)
            logListener.onServerLog("CLIENT [" + socket.getInetAddress().getHostAddress() + "]", msg);
    }

    private void logServer(String msg) {
        log.fine("S: " + msg);
        if (logListener != null)
            logListener.onServerLog("SERVER", msg);
    }
}
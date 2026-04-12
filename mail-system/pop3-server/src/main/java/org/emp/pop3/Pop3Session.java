package org.emp.pop3;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

public class Pop3Session implements Runnable {

    private static final Logger log = Logger.getLogger(Pop3Session.class.getName());
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

    private List<Pop3Mail>  messages;
    private List<Boolean>   deletionFlags;
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
    @Override
    public void run() {
        try {
            socket.setSoTimeout(10 * 60 * 1000);

            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);

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

            if (state == Pop3State.TRANSACTION) {
                log.warning("Client dropped without QUIT — no messages deleted.");
            }
        } catch (java.net.SocketTimeoutException e) {
            log.info("Session timed out — closing without UPDATE.");
        } catch (IOException e) {
            log.warning("Session IO error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

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
        userAccepted = true;                         
        sendOk(arg + " is a valid mailbox");
    }

    private void handlePass(String arg) {
        if (state != Pop3State.AUTHORIZATION || !userAccepted) {  
            sendErr("USER command required first");
            return;
        }
        if (arg.isEmpty()) {
            sendErr("Usage: PASS <password>");
            return;
        }
        if (!authenticator.authenticate(username, arg)) {
            userAccepted = false; 
            sendErr("Invalid password");
            return;
        }
        messages      = mailStorage.loadMessages(username);
        deletionFlags = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) deletionFlags.add(false);
        state = Pop3State.TRANSACTION;
        sendOk(username + "'s maildrop has "
                + messages.size() + " messages ("
                + totalOctets() + " octets)");
    }
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

    private void handleRetr(String arg) {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }

        int idx = parseMessageNumber(arg);
        if (idx < 0) { sendErr("Invalid message number"); return; }
        if (deletionFlags.get(idx)) { sendErr("Message marked as deleted"); return; }  // FIX #7

        Pop3Mail mail = messages.get(idx);
        sendOk(mail.getSize() + " octets");

        try {
            for (String line : mail.getLines()) {

                if (line.startsWith(".")) {
                    sendRaw("." + line);
                } else {
                    sendRaw(line);
                }
            }
        } catch (IOException e) {
            log.warning("Error reading message: " + e.getMessage());
        }
        sendRaw(".");  
    }

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
    private void handleRset() {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }
        for (int i = 0; i < deletionFlags.size(); i++) deletionFlags.set(i, false);
        sendOk("maildrop has " + messages.size() + " messages (" + totalOctets() + " octets)");
    }

  
    private void handleNoop() {
        if (state != Pop3State.TRANSACTION) { sendErr("Not in TRANSACTION state"); return; }
        sendOk("");
    }

   
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
                    
                    String toSend = line.startsWith(".") ? "." + line : line;
                    sendRaw(toSend);
                    if (line.isEmpty()) inBody = true;  
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

 
    private void handleQuit() {
        quitReceived = true;

        if (state == Pop3State.TRANSACTION) {
            applyDeletions();
        }

        sendOk(serverDomain + " POP3 server signing off");
    }
    private void applyDeletions() {
        state = Pop3State.UPDATE;
        for (int i = 0; i < messages.size(); i++) {
            if (deletionFlags.get(i)) {
                boolean ok = mailStorage.delete(username, messages.get(i));
                if (!ok) log.warning("Failed to delete message: " + messages.get(i).getUid());
            }
        }
    }
    private int parseMessageNumber(String arg) {
        try {
            int n = Integer.parseInt(arg.trim());
            if (n < 1 || n > messages.size()) return -1;
            return n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

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
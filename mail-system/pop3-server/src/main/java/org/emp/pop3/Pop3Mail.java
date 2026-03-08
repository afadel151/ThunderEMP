package org.emp.pop3;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents one message in a POP3 session.
 *
 * Holds metadata (uid, size) eagerly and content (lines) lazily on demand.
 * The uid is stable across sessions (filename or DB id), required by
 * RFC 1939 §7 UIDL.
 */
public class Pop3Mail {

    private final String uid;    // unique, stable identifier (filename or DB id)
    private final long   size;   // size in octets (pre-calculated, RFC §11)
    private final File   file;   // null when backed by DB

    // For DB-backed storage (Étape 5)
    private String body;

    /** File-backed constructor (Étapes 1-4). */
    public Pop3Mail(File file) {
        this.file = file;
        this.uid  = file.getName();
        this.size = computeSize(file);
        this.body = null;
    }

    /** DB-backed constructor (Étape 5). */
    public Pop3Mail(String uid, String body) {
        this.file = null;
        this.uid  = uid;
        this.body = body;
        // RFC §11: count each LF as CRLF (2 octets)
        this.size = body.lines().mapToLong(l -> l.length() + 2).sum();
    }

    public String getUid()  { return uid;  }
    public long   getSize() { return size; }

    /**
     * Returns message content as a list of lines (no CRLF endings).
     * Used by RETR and TOP commands.
     *
     * RFC 1939 §11: octet count may differ from raw file size due to
     * line-ending conventions — we always serve lines as-is and let
     * PrintWriter add CRLF via println().
     */
    public List<String> getLines() throws IOException {
        List<String> lines = new ArrayList<>();
        if (file != null) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
        } else {
            for (String line : body.split("\n")) {
                lines.add(line.replace("\r", ""));
            }
        }
        return lines;
    }

    /**
     * RFC 1939 §11 — size in octets counting each line ending as CRLF (2 bytes).
     * If the file uses LF-only endings, we still report size as if CRLF.
     */
    private long computeSize(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            return lines.stream().mapToLong(l -> l.length() + 2).sum();
        } catch (IOException e) {
            return file.length(); // fallback
        }
    }
}
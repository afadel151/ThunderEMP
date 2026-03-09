package org.emp.imap;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Represents one IMAP message in a session.
 * Storage-agnostic value object.
 *
 * RFC 9051 relevant fields:
 *  • uid          — unique identifier (§2.3.1.1), stable across sessions
 *  • flags        — per-message flags (§2.3.2)
 *  • internalDate — date the message was received (§2.3.3)
 *  • size         — RFC822.SIZE in octets (§2.3.4)
 */
public class ImapMessage {

    private final long         uid;
    private final Set<String>  flags        = new LinkedHashSet<>();
    private final long         internalDate;
    private       long         size;

    // Storage backing — one of:
    private File   file;    // file-backed (Étapes 1–4)
    private String body;    // DB-backed   (Étape 5)

    // Parsed headers cache
    private Map<String, String> headers;

    // ── File-backed constructor (Étapes 1-4) ─────────────────────────────────

    public ImapMessage(long uid, File file, long internalDate, List<String> flags) {
        this.uid          = uid;
        this.file         = file;
        this.internalDate = internalDate;
        this.size         = computeSize(file);
        flags.forEach(this::addFlag);
    }

    // ── DB-backed constructor (Étape 5) ──────────────────────────────────────

    public ImapMessage(long uid, String body, long internalDate, List<String> flags) {
        this.uid          = uid;
        this.body         = body;
        this.internalDate = internalDate;
        this.size         = body.lines().mapToLong(l -> l.length() + 2).sum();
        flags.forEach(this::addFlag);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public long         getUid()          { return uid;          }
    public long         getInternalDate() { return internalDate; }
    public long         getSize()         { return size;         }
    public Set<String>  getFlags()        { return Collections.unmodifiableSet(flags); }

    public boolean hasFlag(String flag) {
        return flags.stream().anyMatch(f -> f.equalsIgnoreCase(flag));
    }

    public void addFlag(String flag) {
        if (flag != null && !flag.isBlank()) flags.add(flag);
    }

    public void removeFlag(String flag) {
        flags.removeIf(f -> f.equalsIgnoreCase(flag));
    }

    public void setFlags(List<String> newFlags) {
        flags.clear();
        newFlags.forEach(this::addFlag);
    }

    // ── Content access ───────────────────────────────────────────────────────

    /** Returns the full message as a list of lines (no CRLF endings). */
    public List<String> getLines() throws IOException {
        if (file != null) {
            return Files.readAllLines(file.toPath());
        }
        return Arrays.asList(body.split("\r?\n"));
    }

    /**
     * Get a named header field value (case-insensitive).
     * Returns null if not found.
     */
    public String getHeader(String name) {
        if (headers == null) parseHeaders();
        return headers.get(name.toLowerCase());
    }

    public String getHeader(String name, String defaultValue) {
        String val = getHeader(name);
        return val != null ? val : defaultValue;
    }

    /** True if the named header contains the substring (case-insensitive). */
    public boolean headerContains(String name, String substring) {
        String val = getHeader(name);
        if (val == null) return false;
        return val.toLowerCase().contains(substring.toLowerCase());
    }

    /** True if the message body contains the substring. */
    public boolean bodyContains(String substring) {
        try {
            boolean inBody = false;
            for (String line : getLines()) {
                if (!inBody) { if (line.isEmpty()) inBody = true; continue; }
                if (line.toLowerCase().contains(substring.toLowerCase())) return true;
            }
        } catch (IOException e) { /* ignore */ }
        return false;
    }

    /** True if headers OR body contain the substring (TEXT search key). */
    public boolean fullContains(String substring) {
        try {
            for (String line : getLines()) {
                if (line.toLowerCase().contains(substring.toLowerCase())) return true;
            }
        } catch (IOException e) { /* ignore */ }
        return false;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void parseHeaders() {
        headers = new LinkedHashMap<>();
        try {
            for (String line : getLines()) {
                if (line.isEmpty()) break; // end of headers
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.putIfAbsent(key, val);
                }
            }
        } catch (IOException e) { /* leave headers empty */ }
    }

    private long computeSize(File f) {
        try {
            return Files.readAllLines(f.toPath()).stream()
                    .mapToLong(l -> l.length() + 2L).sum();
        } catch (IOException e) { return f.length(); }
    }
}
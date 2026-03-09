package org.emp.imap;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an IMAP mailbox (folder).
 * Storage-agnostic value object — populated by ImapMailStorage implementations.
 *
 * RFC 9051 relevant fields:
 *  • name         — mailbox name (e.g. "INBOX", "Sent", "INBOX/Work")
 *  • uidValidity  — UIDVALIDITY value (§2.3.1.1). MUST NOT decrease.
 *  • uidNext      — predicted next UID (§2.3.1.1)
 *  • attributes   — LIST attributes: \\Noselect, \\Marked, \\HasChildren, etc.
 */
public class ImapMailbox {

    private final String       name;
    private       long         uidValidity;
    private       long         uidNext;
    private final List<String> attributes = new ArrayList<>();

    public ImapMailbox(String name, long uidValidity, long uidNext) {
        this.name        = name;
        this.uidValidity = uidValidity;
        this.uidNext     = uidNext;
    }

    public String       getName()        { return name;        }
    public long         getUidValidity() { return uidValidity; }
    public long         getUidNext()     { return uidNext;     }
    public List<String> getAttributes()  { return attributes;  }

    public void setUidValidity(long v) { this.uidValidity = v; }
    public void setUidNext(long n)     { this.uidNext = n;     }
    public void addAttribute(String a) { if (!attributes.contains(a)) attributes.add(a); }
}
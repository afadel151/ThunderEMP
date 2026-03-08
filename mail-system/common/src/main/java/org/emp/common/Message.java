package org.emp.common;

import java.time.LocalDateTime;

/**
 * Shared email message model.
 * Used across SMTP (store), POP3/IMAP (fetch), and Web interface.
 */
public class Message {

    private int    id;
    private String sender;
    private String recipient;
    private String subject;
    private String body;
    private LocalDateTime date;
    private boolean read;      // \Seen flag for IMAP
    private boolean deleted;   // soft delete flag

    public Message() {}

    public Message(String sender, String recipient, String subject, String body) {
        this.sender    = sender;
        this.recipient = recipient;
        this.subject   = subject;
        this.body      = body;
        this.date      = LocalDateTime.now();
        this.read      = false;
        this.deleted   = false;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public int getId()                      { return id; }
    public void setId(int id)               { this.id = id; }

    public String getSender()               { return sender; }
    public void setSender(String sender)    { this.sender = sender; }

    public String getRecipient()            { return recipient; }
    public void setRecipient(String r)      { this.recipient = r; }

    public String getSubject()              { return subject; }
    public void setSubject(String subject)  { this.subject = subject; }

    public String getBody()                 { return body; }
    public void setBody(String body)        { this.body = body; }

    public LocalDateTime getDate()          { return date; }
    public void setDate(LocalDateTime date) { this.date = date; }

    public boolean isRead()                 { return read; }
    public void setRead(boolean read)       { this.read = read; }

    public boolean isDeleted()              { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    @Override
    public String toString() {
        return "[" + id + "] From=" + sender + " To=" + recipient
                + " Subject=" + subject + " Read=" + read;
    }
}

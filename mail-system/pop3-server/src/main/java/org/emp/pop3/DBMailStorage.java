package org.emp.pop3;

import org.emp.common.EmailRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * PostgreSQL-backed mail storage for POP3 (Étape 5).
 *
 * Uses EmailRepository which calls PostgreSQL functions:
 *   fetch_emails(recipient)  → ResultSet of messages
 *   delete_email(email_id)   → soft-delete
 *
 * Activate by: pop3Server.setMailStorage(new DBMailStorage());
 */
public class DBMailStorage implements Pop3MailStorage {

    private static final Logger log = Logger.getLogger(DBMailStorage.class.getName());
    private final EmailRepository emailRepo;

    public DBMailStorage() {
        this.emailRepo = new EmailRepository();
    }

    @Override
    public List<Pop3Mail> loadMessages(String username) {
        List<Pop3Mail> result = new ArrayList<>();
        List<EmailRepository.EmailDTO> emails = emailRepo.fetchEmails(username);

        for (EmailRepository.EmailDTO email : emails) {
            String body = buildBody(email);
            result.add(new Pop3Mail(String.valueOf(email.getId()), body));
        }

        log.info("Loaded " + result.size() + " messages for " + username);
        return result;
    }

    @Override
    public boolean delete(String username, Pop3Mail mail) {
        try {
            int emailId = Integer.parseInt(mail.getUid());
            boolean success = emailRepo.deleteEmail(emailId);
            if (success) {
                log.info("Soft-deleted email id=" + emailId + " for " + username);
            } else {
                log.warning("Failed to delete email id=" + emailId);
            }
            return success;
        } catch (NumberFormatException e) {
            log.severe("Invalid email ID: " + mail.getUid());
            return false;
        }
    }

    /** Build a RFC 5322-style message body from an EmailDTO. */
    private String buildBody(EmailRepository.EmailDTO email) {
        // Fetch full email with body
        EmailRepository.EmailDTO fullEmail = emailRepo.getEmail(email.getId());
        if (fullEmail == null) {
            log.warning("Could not load full email for id=" + email.getId());
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(fullEmail.getSender()).append("\r\n");
        sb.append("To: ").append(fullEmail.getRecipient()).append("\r\n");
        sb.append("Subject: ").append(fullEmail.getSubject()).append("\r\n");
        sb.append("Date: ").append(fullEmail.getSentAt()).append("\r\n");
        sb.append("\r\n");
        sb.append(fullEmail.getBody() != null ? fullEmail.getBody() : "");
        return sb.toString();
    }
}
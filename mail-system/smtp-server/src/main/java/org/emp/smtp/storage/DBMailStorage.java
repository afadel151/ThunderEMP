package org.emp.smtp.storage;

import org.emp.common.DBConnection;
import org.emp.common.EmailRepository;
import org.emp.common.Message;

import java.util.logging.Logger;

public class DBMailStorage implements MailStorage {

    private static final Logger log = Logger.getLogger(DBMailStorage.class.getName());
    private final EmailRepository emailRepo;

    public DBMailStorage() {
        this.emailRepo = new EmailRepository();
    }

    @Override
    public boolean store(Message message) {
        String[] recipients = message.getRecipient().split(",");
        boolean allOk = true;

        for (String recipient : recipients) {
            recipient = recipient.trim();
            if (recipient.isEmpty()) continue;

            int emailId = emailRepo.storeEmail(
                message.getSender(),
                recipient,
                message.getSubject(),
                message.getBody()
            );

            if (emailId > 0) {
                log.info("Stored email id=" + emailId + " for " + recipient);
            } else {
                log.severe("Failed to store email for " + recipient);
                allOk = false;
            }
        }
        return allOk;
    }
}
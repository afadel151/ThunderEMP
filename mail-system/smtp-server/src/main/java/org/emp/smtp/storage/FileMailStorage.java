package org.emp.smtp.storage;

import org.emp.common.MailStorageConfig;
import org.emp.common.Message;

import java.io.*;   
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Stores each email as a .txt file under mailserver/<username>/<timestamp>.txt
 *
 * Used in Étapes 1–4.
 * Replaced by DBMailStorage in Étape 5 — just swap the implementation
 * injected into SmtpSession constructor.
 */
public class FileMailStorage implements MailStorage {

    private static final Logger log = Logger.getLogger(FileMailStorage.class.getName());

    private static final String BASE_DIR = MailStorageConfig.getBaseDir();
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    @Override
    public boolean store(Message message) {
        boolean allOk = true;

        // Recipients are stored as comma-separated string in Message
        String[] recipients = message.getRecipient().split(",");


        for (String recipient : recipients) {
            System.out.println("recipient : " + recipient);
            recipient = recipient.trim();
            if (recipient.isEmpty()) continue;
            
            String username = recipient.contains("@")
                    ? recipient.split("@")[0]
                    : recipient;

            File userDir = new File(BASE_DIR + File.separator + username);
            if (!userDir.exists() && !userDir.mkdirs()) {
                log.severe("Cannot create directory: " + userDir.getAbsolutePath());
                allOk = false;
                continue;
            }

            // Timestamp with millis to avoid collisions under load
            String timestamp = LocalDateTime.now().format(TS_FMT);
            File emailFile = new File(userDir, timestamp + ".txt");

            try (PrintWriter writer = new PrintWriter(new FileWriter(emailFile))) {
                // RFC 5322 headers
                writer.println("From: " + message.getSender());
                writer.println("To: "   + recipient);
                writer.println("Date: " + LocalDateTime.now());
                writer.println("Subject: " + message.getSubject());
                writer.println();
                writer.print(message.getBody());
                log.info("Stored email for " + recipient + " → " + emailFile.getAbsolutePath());
            } catch (IOException e) {
                log.severe("Error storing email for " + recipient + ": " + e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }
}
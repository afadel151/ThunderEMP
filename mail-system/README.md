# Mail System — Distributed Messaging
> TP Systèmes Distribués 2025/2026

## Project Structure

```
mail-system/
├── common/          → Shared models, DB connection (jar)
├── auth-server/     → Java RMI Authentication server (Étape 4)
├── smtp-server/     → SMTP Server + GUI (Étapes 1, 3)
├── pop3-server/     → POP3 Server + GUI (Étapes 1, 3)
├── imap-server/     → IMAP Server + GUI (Étapes 2, 3)
├── web-interface/   → HTTP Web Frontend (Étape 6) (war)
├── mail-clients/    → JavaMail clients SMTP/POP3/IMAP (Étape 7)
├── database/        → SQL schema + stored procedures (Étape 5)
├── load-balancer/   → NGINX config Round Robin (Étape 8)
└── docs/            → Rapport, diagrams, screenshots
```

## Prerequisites

- Java 17+
- Maven 3.8+
- MySQL 8.0+
- (Étape 8) NGINX

## Quick Start

### 1. Clone and build
```bash
git clone <your-repo-url>
cd mail-system
mvn clean install
```

### 2. Setup the database (Étape 5)
```bash
mysql -u root -p < database/schema.sql
mysql -u root -p mailsystem < database/procedures.sql
```

### 3. Configure DB credentials
Edit `common/src/main/resources/db.properties`

### 4. Start each server
```bash
# Auth Server (RMI)
java -jar auth-server/target/auth-server.jar

# SMTP Server
java -jar smtp-server/target/smtp-server.jar

# POP3 Server
java -jar pop3-server/target/pop3-server.jar

# IMAP Server
java -jar imap-server/target/imap-server.jar
```

## Useful Maven Commands

```bash
# Build everything
mvn clean install

# Build a single module
mvn clean package -pl imap-server -am

# Skip tests
mvn clean install -DskipTests

# Run a specific module
mvn exec:java -pl smtp-server -Dexec.mainClass="org.emp.smtp.SMTPServer"
```

## Git Workflow
Commit after each feature as required by the TP:
```bash
git add .
git commit -m "feat(imap): implement SELECT command with state machine"
git push
```

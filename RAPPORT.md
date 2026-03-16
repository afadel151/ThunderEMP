# Compte Rendu — Travaux Pratiques
## Systèmes Distribués — 2025/2026

**Sujet :** Implémentation d'un Système de Messagerie Distribuée

| | |
|---|---|
| **Réalisé par** | Fadel Akram |
| **Spécialité** | SIAD |
| **Encadré par** | Pr. SEBBAK Faouzi |
| **Année** | 2025 – 2026 |
| **Établissement** | École Militaire Polytechnique — Département de l'Informatique |

---

## Table des Matières

- [Partie 1 : Prise en main et correction du système existant](#partie-1)
  - [0.1 Introduction et présentation du système](#01-introduction)
  - [0.2 Analyse et corrections du serveur SMTP (RFC 5321)](#02-smtp)
  - [0.3 Analyse et corrections du serveur POP3 (RFC 1939)](#03-pop3)
  - [0.4 Tests fonctionnels](#04-tests-partie1)
  - [0.5 Conclusion Partie 1](#05-conclusion-partie1)
- [Partie 2 : Développement du serveur IMAP](#partie-2)
  - [0.6 Introduction](#06-intro-imap)
  - [0.7 Définitions et rappels théoriques](#07-theorie)
  - [0.8 Implémentation](#08-implementation)
  - [0.9 Tests du protocole IMAP](#09-tests-imap)
  - [0.10 Conclusion Partie 2](#010-conclusion-partie2)
- [Partie 3 : Interfaces de supervision des serveurs](#partie-3)
  - [0.11 Introduction](#011-intro-gui)
  - [0.12 Architecture générale](#012-architecture-gui)
  - [0.13 Implémentation des interfaces graphiques](#013-implementation-gui)
  - [0.14 Scénarios de test et résultats](#014-tests-gui)
  - [0.15 Discussion et analyse](#015-discussion)
  - [0.16 Conclusion Partie 3](#016-conclusion-partie3)
- [Conclusion générale](#conclusion-generale)

---

# Partie 1 : Prise en main et correction du système existant {#partie-1}

## 0.1 Introduction et présentation du système {#01-introduction}

### 0.1.1 Contexte général

Ce travail pratique s'inscrit dans le cadre du cours de Systèmes Distribués. L'objectif de cette première partie est de prendre en main un système de messagerie distribué existant, développé en Java. Ce système met en œuvre deux protocoles standards : **SMTP** pour l'envoi des courriers et **POP3** pour leur consultation.

### 0.1.2 Architecture du système

- `SmtpServer.java` : serveur chargé de recevoir et stocker les emails.
- `Pop3Server.java` : serveur permettant aux utilisateurs de récupérer leurs messages.
- Système de fichiers : emails enregistrés dans `mailserver/<utilisateur>/`.

```
mailserver/
  alice/
    20250306_091500.txt
  bob/
    20250306_150300.txt
```
> *Listing 1 : Arborescence de stockage des emails*

### 0.1.3 Protocoles et ports utilisés

| Service | Port d'origine | Port corrigé | RFC |
|---------|---------------|-------------|-----|
| SMTP | 25 | 2525 | RFC 5321 |
| POP3 | 110 | 1100 | RFC 1939 |

### 0.1.4 Cycle de vie d'un message

1. Le client se connecte au serveur SMTP (port 2525).
2. Il envoie les commandes `HELO`, `MAIL FROM`, `RCPT TO`, `DATA`.
3. Le serveur stocke l'email dans `mailserver/<destinataire>/<horodatage>.txt`.
4. Le destinataire se connecte au serveur POP3 (port 1100).
5. Il s'authentifie avec `USER` et `PASS`.
6. Il récupère ses emails avec `LIST`, `RETR`, `DELE`.
7. La commande `QUIT` ferme la session et applique les suppressions.

---

## 0.2 Analyse et corrections du serveur SMTP (RFC 5321) {#02-smtp}

### 0.2.1 Récapitulatif des anomalies détectées

| N | Description | Méthode | Sévérité |
|---|-------------|---------|----------|
| B1 | Port 25 requiert les droits root | `SmtpServer` | Critique |
| B2 | Destinataires non réinitialisés | `run()` | Critique |
| B3 | Commande RSET absente | `run()` | Majeure |
| B4 | Commande NOOP absente | `run()` | Majeure |
| B5 | Sujet figé dans le code | `storeEmail()` | Majeure |
| B6 | Byte-unstuffing non implémenté | `run()` | Critique |
| B7 | EHLO sans liste d'extensions | `handleHelo()` | Mineure |

> *Table 2 : Anomalies identifiées dans SmtpServer.java*

---

### 0.2.2 Anomalie B1 — Port privilégié (port 25)

**Problème :** Le port 25 est un port système qui nécessite les droits root. Sans `sudo`, le serveur ne peut pas démarrer et lève une `BindException`.

```java
// Port systeme, necessite sudo
private static final int PORT = 25; // ERREUR
```
> *Listing 2 : Code original — B1*

<!-- IMAGE: Figure 1 — Erreur au démarrage du serveur SMTP avec le port 25 -->

**Correction :** Remplacer par le port 2525, un port applicatif non restreint.

```java
// Port applicatif accessible sans privileges
public static final int DEFAULT_PORT = 2525; // CORRIGE
```
> *Listing 3 : Code corrigé — B1*

Extrait de [`SmtpServer.java`](mail-system/smtp-server/src/main/java/org/emp/smtp/SmtpServer.java) :

```java
public class SmtpServer {
    public static final int DEFAULT_PORT = 2525;
    private static final String SERVER_DOMAIN = "smtp.emp.org";
    private static final int THREAD_POOL = 50;

    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    public SmtpServer() { this(DEFAULT_PORT); }
    public SmtpServer(int port) { this.port = port; }
}
```

<!-- IMAGE: Figure 2 — Démarrage réussi du serveur SMTP sur le port 2525 -->

---

### 0.2.3 Anomalie B2 — Destinataires non réinitialisés entre deux messages

**Problème :** Après l'envoi d'un premier email, les listes `recipients` et `sender` ne sont pas réinitialisées. Les anciens destinataires s'accumulent avec les nouveaux lors d'un second envoi dans la même session.

```java
if (line.equals(".")) {
    storeEmail(dataBuffer.toString());
    dataBuffer.setLength(0);
    // ERREUR : recipients et sender jamais reinitialises
    state = SmtpState.HELO_RECEIVED;
    out.println("250 OK : Message accepted for delivery");
}
```
> *Listing 4 : Code original — B2*

**Correction :**

```java
if (line.equals(".")) {
    storeEmail(dataBuffer.toString());
    dataBuffer.setLength(0);
    recipients.clear(); // AJOUT
    sender = "";        // AJOUT
    state = SmtpState.HELO_RECEIVED;
    out.println("250 OK : Message accepted for delivery");
}
```
> *Listing 5 : Code corrigé — B2*

Implémenté via `resetTransaction()` dans [`SmtpSession.java`](mail-system/smtp-server/src/main/java/org/emp/smtp/SmtpSession.java) :

```java
private void resetTransaction() {
    sender = "";
    recipients.clear();
    dataBuffer.setLength(0);
    if (state != SmtpState.CONNECTED) {
        state = SmtpState.HELO_RECEIVED;
    }
}
```

<!-- IMAGE: Figure 3 — Correction B2 : liste des destinataires réinitialisée correctement -->

---

### 0.2.4 Anomalie B3 — Commande RSET absente

**Référence :** RFC 5321, Section 4.1.1.5. La commande `RSET` est obligatoire selon la RFC. Elle annule la transaction en cours sans fermer la connexion.

```java
switch (command) {
    case "HELO":
    case "EHLO": handleHelo(argument); break;
    case "MAIL": handleMailFrom(argument); break;
    case "RCPT": handleRcptTo(argument); break;
    case "DATA": handleData(); break;
    case "QUIT": handleQuit(); return;
    // Pas de case "RSET" !
    default:
        out.println("500 Command unrecognized");
}
```
> *Listing 6 : Code original — B3*

<!-- IMAGE: Figure 4 — Anomalie B3 : commande RSET non reconnue -->

**Correction :**

```java
case "RSET": // AJOUT
    sender = "";
    recipients.clear();
    dataBuffer.setLength(0);
    if (state != SmtpState.CONNECTED) {
        state = SmtpState.HELO_RECEIVED;
    }
    out.println("250 OK : Reset state");
    break;
```
> *Listing 7 : Code corrigé — B3*

---

### 0.2.5 Anomalie B4 — Commande NOOP absente

**Référence :** RFC 5321, Section 4.1.1.9. La commande `NOOP` est obligatoire. Le serveur doit répondre `250 OK` sans effectuer aucune action.

**Correction :**

```java
case "NOOP": // AJOUT
    out.println("250 OK");
    break;
```
> *Listing 8 : Code corrigé — B4*

<!-- IMAGE: Figure 5 — Corrections B3 et B4 : RSET et NOOP répondent correctement 250 OK -->

---

### 0.2.6 Anomalie B5 — Sujet du message figé dans le code

**Problème :** Le sujet enregistré est toujours `"Test Email"`, quelle que soit la valeur transmise par le client.

```java
writer.println("Subject: Test Email"); // ERREUR : toujours identique
```
> *Listing 9 : Code original — B5*

**Correction :**

```java
String subject = "No Subject";
String[] lines = data.split("\r\n");
for (String headerLine : lines) {
    if (headerLine.isEmpty()) break;
    if (headerLine.toLowerCase().startsWith("subject:")) {
        subject = headerLine.substring(8).trim();
        break;
    }
}
writer.println("Subject: " + subject); // MODIFIE
```
> *Listing 10 : Code corrigé — B5*

Implémenté dans `buildMessage()` de [`SmtpSession.java`](mail-system/smtp-server/src/main/java/org/emp/smtp/SmtpSession.java) :

```java
private Message buildMessage() {
    String rawData = dataBuffer.toString();
    String subject = "";
    for (String line : rawData.split("\r\n")) {
        if (line.toLowerCase().startsWith("subject:")) {
            subject = line.substring(8).trim();
            break;
        }
        if (line.isEmpty()) break;
    }
    // ...
}
```

---

### 0.2.7 Anomalie B6 — Byte-unstuffing absent dans DATA

**Référence :** RFC 5321, Section 4.5.2. Lorsqu'une ligne commence par `.`, le client ajoute un second point que le serveur doit retirer.

```java
// Pas de byte-unstuffing
dataBuffer.append(line).append("\r\n");
```
> *Listing 11 : Code original — B6*

**Correction :**

```java
// Byte-unstuffing conforme RFC 5321
if (line.startsWith("..")) {
    line = line.substring(1);
}
dataBuffer.append(line).append("\r\n");
```
> *Listing 12 : Code corrigé — B6*

---

## 0.3 Analyse et corrections du serveur POP3 (RFC 1939) {#03-pop3}

### 0.3.1 Récapitulatif des anomalies détectées

| N | Description | Méthode | Sévérité |
|---|-------------|---------|----------|
| B7 | Port 110 requiert les droits root | `Pop3Server` | Critique |
| B8 | Absence d'automate à états finis | Toutes | Critique |
| B9 | STAT inclut les messages supprimés | `handleStat()` | Majeure |
| B10 | LIST affiche les messages supprimés | `handleList()` | Majeure |
| B11 | RETR accepté sur message supprimé | `handleRetr()` | Majeure |
| B12 | Réponses non conformes RFC | `handleDele()`, `handleRset()` | Mineure |
| B13 | Commande NOOP absente | `run()` | Majeure |
| B14 | QUIT supprime sans vérifier l'état | `handleQuit()` | Critique |

> *Table 3 : Anomalies identifiées dans Pop3Server.java*

---

### 0.3.2 Anomalie B7 — Port privilégié (port 110)

**Problème :** Identique à B1. Le port 110 est un port système nécessitant les droits root.

```java
private static final int PORT = 110; // ERREUR : port systeme
```
> *Listing 13 : Code original — B7*

<!-- IMAGE: Figure 6 — Anomalie B7 : le port 110 dans l'ancien code -->

```java
public static final int DEFAULT_PORT = 1110; // CORRIGE
```
> *Listing 14 : Code corrigé — B7*

---

### 0.3.3 Anomalie B8 — Absence d'automate à états finis

**Référence :** RFC 1939, Section 3. La RFC définit trois états obligatoires : `AUTHORIZATION`, `TRANSACTION`, `UPDATE`.

**Problème :** L'implémentation utilise un simple booléen `authenticated`.

```java
// Un simple booleen, pas un automate d'etats
private boolean authenticated;
```
> *Listing 15 : Code original — B8*

**Correction :**

```java
private enum Pop3State {
    AUTHORIZATION,
    TRANSACTION,
    UPDATE
}
private Pop3State state;

// Dans le constructeur :
this.state = Pop3State.AUTHORIZATION;
```
> *Listing 16 : Code corrigé — B8*

Implémenté dans [`Pop3Session.java`](mail-system/pop3-server/src/main/java/org/emp/pop3/Pop3Session.java) :

```java
public class Pop3Session implements Runnable {
    private enum Pop3State { AUTHORIZATION, TRANSACTION, UPDATE }

    private Pop3State state        = Pop3State.AUTHORIZATION;
    private String    username     = null;
    private boolean   userAccepted = false;
    private boolean   quitReceived = false;

    private List<Pop3Mail> messages;
    private List<Boolean>  deletionFlags;
}
```

<!-- IMAGE: Figure 7 — Correction B8 : automate d'états POP3 conforme RFC 1939 -->

---

### 0.3.4 Anomalie B9 — STAT comptabilise les messages supprimés

**Référence :** RFC 1939, Section 5.

```java
// Compte TOUS les messages, meme les supprimes
long size = emails.stream().mapToLong(File::length).sum();
out.println("+OK " + emails.size() + " " + size);
```
> *Listing 17 : Code original — B9*

<!-- IMAGE: Figure 8 — Anomalie B9 : STAT comptabilise incorrectement les messages supprimés -->

**Correction :**

```java
int count = 0;
long size = 0;
for (int i = 0; i < emails.size(); i++) {
    if (!deletionFlags.get(i)) {
        count++;
        size += emails.get(i).length();
    }
}
out.println("+OK " + count + " " + size);
```
> *Listing 18 : Code corrigé — B9*

<!-- IMAGE: Figure 9 — Correction B9 : STAT exclut correctement les messages supprimés -->

---

### 0.3.5 Anomalie B10 — LIST affiche les messages supprimés

**Référence :** RFC 1939, Section 5.

```java
out.println("+OK " + emails.size() + " messages");
for (int i = 0; i < emails.size(); i++) {
    out.println((i + 1) + " " + emails.get(i).length());
}
```
> *Listing 19 : Code original — B10*

<!-- IMAGE: Figure 10 — Anomalie B10 : LIST affiche les messages supprimés -->

**Correction :**

```java
int count = 0; long size = 0;
for (int i = 0; i < emails.size(); i++) {
    if (!deletionFlags.get(i)) { count++; size += emails.get(i).length(); }
}
out.println("+OK " + count + " messages (" + size + " octets)");
for (int i = 0; i < emails.size(); i++) {
    if (!deletionFlags.get(i)) {
        out.println((i + 1) + " " + emails.get(i).length());
    }
}
out.println(".");
```
> *Listing 20 : Code corrigé — B10*

<!-- IMAGE: Figure 11 — Correction B10 : LIST filtre correctement les messages supprimés -->

---

### 0.3.6 Anomalie B11 — RETR autorisé sur un message supprimé

**Référence :** RFC 1939, Section 5.

```java
int index = Integer.parseInt(arg) - 1;
if (index < 0 || index >= emails.size()) {
    out.println("-ERR No such message");
    return;
}
// ERREUR : pas de verification du flag de suppression
File emailFile = emails.get(index);
```
> *Listing 21 : Code original — B11*

<!-- IMAGE: Figure 12 — Anomalie B11 : RETR autorisé sur un message supprimé -->

**Correction :**

```java
if (deletionFlags.get(index)) {
    out.println("-ERR Message " + (index + 1) + " already deleted");
    return;
}
```
> *Listing 22 : Code corrigé — B11*

---

### 0.3.7 Anomalie B12 — Messages de réponse non conformes à la RFC

**Problème :** Les réponses de `handleDele()` et `handleRset()` ne respectent pas le format exact de la RFC 1939.

<!-- IMAGE: Figure 13 — Anomalie B12 : messages de réponse non conformes -->

**Correction :**

```java
// handleDele() :
out.println("-ERR Message " + (index+1) + " already deleted");
out.println("+OK Message " + (index+1) + " deleted");

// handleRset() :
out.println("+OK maildrop has " + emails.size()
    + " messages (" + getTotalSize() + " octets)");
```
> *Listing 23 : Code corrigé — B12*

<!-- IMAGE: Figure 14 — Correction B12 : réponses conformes à la RFC 1939 -->

---

### 0.3.8 Anomalie B13 — Commande NOOP absente

**Référence :** RFC 1939, Section 5.

**Correction :**

```java
case "NOOP": // AJOUT
    if (state != Pop3State.TRANSACTION) {
        out.println("-ERR Authentication required");
    } else {
        out.println("+OK");
    }
    break;
```
> *Listing 24 : Code corrigé — B13*

<!-- IMAGE: Figure 15 — Correction B13 : NOOP répond correctement +OK -->

---

### 0.3.9 Anomalie B14 — QUIT applique les suppressions sans vérifier l'état

**Référence :** RFC 1939, Section 6.

```java
private void handleQuit() {
    // ERREUR : suppression dans tous les etats
    for (int i = deletionFlags.size() - 1; i >= 0; i--) {
        if (deletionFlags.get(i)) { emails.get(i).delete(); }
    }
    out.println("+OK POP3 server signing off");
}
```
> *Listing 25 : Code original — B14*

**Correction :**

```java
private void handleQuit() {
    if (state == Pop3State.TRANSACTION) {
        for (int i = deletionFlags.size() - 1; i >= 0; i--) {
            if (deletionFlags.get(i)) { emails.get(i).delete(); }
        }
        out.println("+OK POP3 server signing off (messages deleted)");
    } else {
        out.println("+OK POP3 server signing off (maildrop intact)");
    }
}
```
> *Listing 26 : Code corrigé — B14*

<!-- IMAGE: Figure 16 — Correction B14 : QUIT vérifie l'état avant suppression -->

---

## 0.4 Tests fonctionnels {#04-tests-partie1}

### 0.4.1 Scénario 1 — Envoi complet via SMTP

```telnet
$ telnet localhost 2525
220 smtp.example.com Service Ready
HELO client-test
250 Hello client-test, pleased to meet you
MAIL FROM:<alice@example.com>
250 OK
RCPT TO:<bob@example.com>
250 OK
DATA
354 Start mail input; end with <CRLF>.<CRLF>
Subject: Premier message de test
Bonjour Bob, ceci est un message de verification.
.
250 OK: Message accepted for delivery
QUIT
221 smtp.example.com Service closing transmission channel
```
> *Listing 27 : Session Telnet SMTP complète*

<!-- IMAGE: Figure 17 — Test Telnet : envoi complet d'un email via SMTP -->

---

### 0.4.2 Scénario 2 — Lecture via POP3

```telnet
$ telnet localhost 1100
+OK POP3 server ready
USER bob
+OK bob is a valid mailbox
PASS motdepasse
+OK bob's maildrop has 1 messages (290 octets)
STAT
+OK 1 290
LIST
+OK 1 messages (290 octets)
1 290
.
RETR 1
+OK 290 octets
From: alice@example.com
To: bob@example.com
Subject: Premier message de test
.
DELE 1
+OK Message 1 deleted
QUIT
+OK POP3 server signing off (messages deleted)
```
> *Listing 28 : Session Telnet POP3 complète*

<!-- IMAGE: Figure 18 — Test Telnet : lecture et suppression d'un email via POP3 -->

---

### 0.4.3 Scénario 3 — Authentification invalide

Le serveur rejette correctement l'utilisateur inconnu avec `-ERR User not found`.

<!-- IMAGE: Figure 19 — Test d'authentification invalide : utilisateur inexistant -->

---

### 0.4.4 Scénario 4 — Commandes hors séquence

L'automate à états finis bloque toutes les commandes réservées à l'état `TRANSACTION` lorsque le client n'est pas authentifié.

<!-- IMAGE: Figure 20 — Test des commandes hors séquence : réponses d'erreur correctes -->

---

## 0.5 Conclusion de la Partie 1 {#05-conclusion-partie1}

Cette première partie du TP a permis d'analyser en profondeur un système de messagerie distribué implémenté en Java. Au total, **14 anomalies** ont été identifiées et corrigées : 7 dans le serveur SMTP et 8 dans le serveur POP3. La mise en conformité avec les RFC 5321 et RFC 1939 a constitué l'enjeu central de cette étape.

---

# Partie 2 : Développement du serveur IMAP {#partie-2}

## 0.6 Introduction {#06-intro-imap}

Ce rapport présente le développement d'un serveur IMAP intégré au système de messagerie distribué existant. Contrairement à POP3, IMAP conserve les messages sur le serveur, supporte la gestion des dossiers, la lecture partielle et la gestion des états. L'implémentation respecte la **RFC 9051** et utilise un automate à états finis.

---

## 0.7 Définitions et rappels théoriques {#07-theorie}

### 0.7.1 Le protocole IMAP

IMAP (Internet Message Access Protocol) est défini dans la RFC 9051. Ses caractéristiques :

- Conservation des messages sur le serveur (accès multi-appareils)
- Gestion de dossiers : `INBOX`, `SENT`, `TRASH`, etc.
- Flags : `\Seen`, `\Deleted`, `\Flagged`
- Lecture partielle : en-têtes sans télécharger le corps
- Recherche avec la commande `SEARCH`

### 0.7.2 Comparaison IMAP et POP3

| Critère | POP3 (Port 110) | IMAP (Port 143) |
|---------|----------------|----------------|
| Stockage messages | Téléchargés localement | Conservés sur le serveur |
| Gestion dossiers | Non | Oui (INBOX, SENT, TRASH) |
| Flags | Non | `\Seen`, `\Deleted` |
| Lecture partielle | Non | Oui (`BODY[HEADER]`, `BODY[TEXT]`) |
| Recherche | Non | Oui (`SEARCH`) |
| Multi-appareils | Non | Oui |
| États FSM | 2 états | 4 états |

> *Table 4 : Comparaison des protocoles POP3 et IMAP*

### 0.7.3 Automate à états finis (FSM)

| État | Commandes autorisées | Transition |
|------|---------------------|-----------|
| `NOT_AUTHENTICATED` | `CAPABILITY`, `LOGIN`, `LOGOUT` | LOGIN OK → AUTHENTICATED |
| `AUTHENTICATED` | `SELECT`, `LIST`, `LOGOUT` | SELECT OK → SELECTED |
| `SELECTED` | `FETCH`, `STORE`, `SEARCH`, `CLOSE`, `LOGOUT` | LOGOUT → fin |
| `LOGOUT` | Aucune | Fin de session |

> *Table 5 : États de la session IMAP*

### 0.7.4 Format des réponses IMAP

| Code | Signification | Exemple |
|------|--------------|---------|
| `TAG OK` | Commande réussie | `a001 OK LOGIN completed` |
| `TAG NO` | Commande refusée | `a002 NO [NONEXISTENT] ...` |
| `TAG BAD` | Erreur / état invalide | `a003 BAD Déjà authentifié` |
| `* (untagged)` | Données | `* 2 EXISTS` |
| `BYE` | Déconnexion | `* BYE logging out` |

> *Table 6 : Codes de réponse IMAP (RFC 9051)*

---

## 0.8 Implémentation {#08-implementation}

### 0.8.1 Structure du projet

```
mail-system/
  smtp-server/src/main/java/org/emp/smtp/
    SmtpServer.java          (port 2525 - corrigé)
    SmtpSession.java
    gui/SmtpServerGui.java
  pop3-server/src/main/java/org/emp/pop3/
    Pop3Server.java          (port 1110 - corrigé)
    Pop3Session.java
    gui/Pop3ServerGui.java
  imap-server/src/main/java/org/emp/imap/
    ImapServer.java          (port 1143 - nouveau)
    ImapSession.java
    gui/ImapServerGui.java
  common/src/main/java/org/emp/common/
    Message.java
    User.java
  mailserver/
    <utilisateur>/
```
> *Listing 29 : Structure du projet*

Classe principale [`ImapServer.java`](mail-system/imap-server/src/main/java/org/emp/imap/ImapServer.java) :

```java
public class ImapServer {
    public static final int DEFAULT_PORT = 1143;
    public static final String SERVER_DOMAIN = "imap.emp.org";
    private static final int THREAD_POOL = 50;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService pool;

    private ImapLogListener logListener;
    private ImapAuthenticator authenticator;
    private ImapMailStorage mailStorage;

    public void start() {
        if (running) return;
        if (authenticator == null) authenticator = new FileAuthenticator();
        if (mailStorage == null)   mailStorage   = new FileMailStorage();
        pool = Executors.newFixedThreadPool(THREAD_POOL);
        // ...
    }
}
```

### 0.8.2 Commandes implémentées

| Commande | État requis | Description |
|----------|------------|-------------|
| `CAPABILITY` | Tout état | Capacités du serveur |
| `LOGIN` | `NOT_AUTHENTICATED` | Authentification |
| `SELECT` | `AUTHENTICATED` | Sélectionner INBOX |
| `LIST` | `AUTHENTICATED` | Lister les boîtes |
| `FETCH` | `SELECTED` | Lire un message ou ses en-têtes |
| `STORE` | `SELECTED` | Modifier les flags |
| `SEARCH` | `SELECTED` | Rechercher des messages |
| `LOGOUT` | Tout état | Terminer la session |

> *Table 7 : Commandes IMAP implémentées*

Dispatcher de commandes dans [`ImapSession.java`](mail-system/imap-server/src/main/java/org/emp/imap/ImapSession.java) :

```java
private void dispatch(String line) {
    String[] parts   = line.split(" ", 3);
    String   tag     = parts[0];
    String   command = parts.length > 1 ? parts[1].toUpperCase() : "";
    String   args    = parts.length > 2 ? parts[2] : "";

    // Commands valid in any state
    switch (command) {
        case "CAPABILITY": handleCapability(tag); return;
        case "NOOP":       handleNoop(tag);       return;
        case "LOGOUT":     handleLogout(tag);     return;
    }

    if (state == ImapState.NOT_AUTHENTICATED) {
        switch (command) {
            case "LOGIN": handleLogin(tag, args); return;
            default:
                sendTagged(tag, "BAD Command not permitted in NOT AUTHENTICATED state");
        }
    }
    // ...
}
```

### 0.8.3 Démarrage des serveurs

<!-- IMAGE: Figure 21 — Démarrage du serveur SMTP sur le port 25 -->
<!-- IMAGE: Figure 22 — Démarrage du serveur POP3 sur le port 110 -->
<!-- IMAGE: Figure 23 — Démarrage du serveur IMAP sur le port 143 -->

---

## 0.9 Tests du protocole IMAP {#09-tests-imap}

### 0.9.1 Scénario 1 — Connexion de base complète

**Objectif :** Valider le cycle complet `LOGIN → SELECT → FETCH → LOGOUT`.

```imap
a001 CAPABILITY
a002 LOGIN user1 password
a003 SELECT INBOX
a004 FETCH 1 (FLAGS BODY[HEADER])
a005 LOGOUT
```
> *Listing 30 : Commandes Telnet — Scénario 1*

<!-- IMAGE: Figure 24 — Scénario 1 : Échange Telnet — SELECT + FETCH + LOGOUT -->
<!-- IMAGE: Figure 25 — Scénario 1 : Logs serveur IMAP — session complète -->

**Analyse :** La session suit correctement l'automate FSM. `SELECT` retourne `* 2 EXISTS`, `* 0 RECENT` et `* OK [UNSEEN 2]`. `FETCH` retourne les en-têtes sans le corps. Le `LOGOUT` termine proprement.

Implémentation de `handleSelect()` dans [`ImapSession.java`](mail-system/imap-server/src/main/java/org/emp/imap/ImapSession.java) :

```java
private void handleSelect(String tag, String args, boolean examineMode) {
    String mailboxName = unquote(args.trim());
    ImapMailbox mailbox = mailStorage.getMailbox(username, mailboxName);
    if (mailbox == null) {
        state = ImapState.AUTHENTICATED;
        sendTagged(tag, "NO [NONEXISTENT] No such mailbox");
        return;
    }
    messages    = mailStorage.loadMessages(username, mailboxName);
    uidValidity = mailbox.getUidValidity();
    uidNext     = mailbox.getUidNext();
    state       = ImapState.SELECTED;

    sendUntagged(messages.size() + " EXISTS");
    sendUntagged("OK [UIDVALIDITY " + uidValidity + "] UIDs valid");
    sendUntagged("OK [UIDNEXT " + uidNext + "] Predicted next UID");
    sendUntagged("FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)");
    sendUntagged("OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Permanent flags");
    sendUntagged("LIST () \"/\" " + quoteMailbox(mailboxName));

    if (examineMode) sendTagged(tag, "OK [READ-ONLY] EXAMINE completed");
    else             sendTagged(tag, "OK [READ-WRITE] SELECT completed");
}
```

---

### 0.9.2 Scénario 2 — Sélection d'une boîte inexistante

**Objectif :** Vérifier que `SELECT` sur une boîte inconnue retourne `NO [NONEXISTENT]`.

```imap
a001 LOGIN user1 password
a002 SELECT UNKNOWN  --> NO [NONEXISTENT]
a003 SELECT SENT     --> NO [NONEXISTENT]
a004 SELECT TRASH    --> NO [NONEXISTENT]
a005 LOGOUT
```
> *Listing 31 : Commandes — Scénario 2*

<!-- IMAGE: Figure 26 — Scénario 2 : Réponses NO [NONEXISTENT] pour UNKNOWN, SENT, TRASH -->
<!-- IMAGE: Figure 27 — Scénario 2 : Logs serveur — rejets boîtes inexistantes -->

---

### 0.9.3 Scénario 3 — Lecture partielle (en-têtes uniquement)

**Objectif :** Vérifier que `BODY[HEADER]` retourne uniquement les en-têtes.

```imap
a003 FETCH 1 (BODY[HEADER])   -- en-tetes seulement
a004 FETCH 1 (BODY[TEXT])     -- corps seulement
a005 FETCH 1 (BODY[])         -- message complet
```
> *Listing 32 : Commandes — Scénario 3*

<!-- IMAGE: Figure 28 — Scénario 3a : FETCH BODY[HEADER] — From, To, Subject, Date uniquement -->
<!-- IMAGE: Figure 29 — Scénario 3b : FETCH BODY[TEXT] — corps du message -->
<!-- IMAGE: Figure 30 — Scénario 3c : FETCH BODY[] — message complet (headers + corps) -->

**Analyse :** La lecture partielle fonctionne. Avantage IMAP sur POP3 : consultation des en-têtes sans télécharger le message entier.

---

### 0.9.4 Scénario 4 — Gestion des flags (`\Seen`)

**Objectif :** Marquer un message comme lu via `STORE +FLAGS`, puis annuler.

```imap
a003 FETCH 1 (FLAGS)                    --> FLAGS ()          (non lu)
a004 STORE 1 +FLAGS (\Seen)             --> FLAGS (\Seen)     (marque lu)
a005 FETCH 1 (FLAGS)                    --> FLAGS (\Seen)     (confirme)
-- Suppression :
a006 STORE 1 -FLAGS (\Seen)             --> FLAGS ()          (repasse non lu)
```
> *Listing 33 : Commandes — Scénario 4*

<!-- IMAGE: Figure 31 — Scénario 4a : STORE +FLAGS — message marqué comme lu -->
<!-- IMAGE: Figure 32 — Scénario 4b : STORE -FLAGS — message repassé non lu -->

**Limitation :** les flags sont en RAM, ils ne persistent pas après déconnexion.

---

### 0.9.5 Scénario 5 — Envoi SMTP et recherche SEARCH

**Objectif :** Envoyer un email via SMTP puis filtrer avec `SEARCH`.

**Étape 1 — Envoi SMTP :**

```smtp
telnet localhost 25
EHLO test
MAIL FROM:<alice@example.com>
RCPT TO:<user1@example.com>
DATA
Subject: Reunion demain
Bonjour, reunion a 10h.
.
QUIT
```
> *Listing 34 : Envoi SMTP — étape 1*

<!-- IMAGE: Figure 33 — Envoi email via SMTP — 250 OK Message accepted for delivery -->
<!-- IMAGE: Figure 34 — Logs SMTP — email reçu et stocké dans mailserver/user1/ -->
<!-- IMAGE: Figure 35 — Fichier .txt stocké — contenu de l'email -->

**Étape 2 — Recherche IMAP :**

```imap
a003 SEARCH ALL       --> * SEARCH 1 2 3 4
a004 SEARCH UNSEEN    --> * SEARCH 1 2 3 4
a005 SEARCH SEEN      --> * SEARCH
a006 SEARCH FROM alice --> * SEARCH 3 4
a007 SEARCH SUBJECT reunion --> * SEARCH
```
> *Listing 35 : Recherche IMAP — étape 2*

<!-- IMAGE: Figure 36 — Scénario 5 : Résultats SEARCH ALL, UNSEEN, SEEN, FROM, SUBJECT -->

**Analyse :** `SEARCH ALL` retourne 4 messages. `SEARCH FROM alice` retourne les messages 3 et 4. `SEARCH SUBJECT reunion` ne trouve pas « Réunion » avec accent — limitation encodage UTF-8.

---

### 0.9.6 Scénario 6 — Commandes dans le mauvais état (FSM)

**Objectif :** Vérifier que l'automate FSM rejette les commandes hors état.

<!-- IMAGE: Figure 37 — Scénario 6a : FETCH et SELECT refusés avant LOGIN -->
<!-- IMAGE: Figure 38 — Scénario 6b : FETCH, STORE, SEARCH refusés après LOGIN sans SELECT -->
<!-- IMAGE: Figure 39 — Scénario 6c : Double LOGIN — BAD Déjà authentifié -->

**Analyse :** L'automate FSM fonctionne correctement. Le double `LOGIN` retourne `BAD`, conforme RFC 9051.

---

## 0.10 Conclusion de la Partie 2 {#010-conclusion-partie2}

Ce TP a permis d'implémenter un serveur IMAP fonctionnel en Java. Le serveur gère les 4 états de session via un automate à états finis, traite les commandes essentielles et retourne les codes de réponse conformes à la RFC 9051. Les six scénarios de test ont validé le fonctionnement complet du protocole.

---

# Partie 3 : Interfaces de supervision des serveurs {#partie-3}

## 0.11 Introduction {#011-intro-gui}

La Partie 3 a pour objectif de doter chacun des trois serveurs (SMTP, POP3, IMAP) d'une **interface graphique de supervision (GUI)**. Ces interfaces permettent de démarrer et arrêter proprement chaque serveur, de visualiser en temps réel tous les échanges, et de surveiller le nombre de clients connectés.

---

## 0.12 Architecture générale {#012-architecture-gui}

### 0.12.1 Principe de conception

Chaque interface graphique est **indépendante** et encapsule son propre serveur. Le serveur s'exécute dans un thread dédié, tandis que l'interface Swing tourne sur l'**Event Dispatch Thread (EDT)**. La communication se fait via `SwingUtilities.invokeLater()`.

### 0.12.2 Structure du projet

<!-- IMAGE: Figure 40 — Structure du projet IntelliJ IDEA — package org.example -->

| Fichier | Rôle | Statut |
|---------|------|--------|
| `ImapServer.java` | Serveur IMAP original | Existant |
| `Pop3Server.java` | Serveur POP3 original | Existant |
| `SmtpServer.java` | Serveur SMTP original | Existant |
| `SmtpServerGui.java` | Interface supervision SMTP | Nouveau |
| `Pop3ServerGui.java` | Interface supervision POP3 | Nouveau |
| `ImapServerGui.java` | Interface supervision IMAP | Nouveau |

> *Table 8 : Fichiers du projet — existants et nouvellement créés*

---

## 0.13 Implémentation des interfaces graphiques {#013-implementation-gui}

### 0.13.1 Fonctionnalités implémentées

| Fonctionnalité | Description | Catégorie |
|---------------|-------------|-----------|
| Démarrer le serveur | Lance le `ServerSocket` dans un thread daemon | Minimale |
| Arrêter le serveur | Ferme le `ServerSocket`, libère le port | Minimale |
| Historique commandes | Affiche tous les échanges en temps réel | Minimale |
| Compteur de clients | Affiche le nombre de connexions TCP actives | Bonus |
| Journalisation | CONNECT / DISCONNECT / ERROR distingués | Bonus |
| Horodatage | Chaque ligne porte un timestamp `[HH:mm:ss]` | Bonus |
| Effacer les logs | Bouton pour vider l'historique | Bonus |

> *Table 9 : Fonctionnalités implémentées par interface de supervision*

### 0.13.2 Méthode `appendLog()` — affichage en temps réel

```java
private void appendLog(String actor, String message) {
    String time = LocalTime.now().format(TIME_FMT);
    boolean isClient = actor.toUpperCase().contains("CLIENT");
    boolean isSystem = actor.equals("SYSTEM");

    Color actorColor = isClient ? CLIENT_CLR
                     : isSystem ? INFO_CLR
                     : SERVER_CLR;

    // Append to JTextPane with styled colors
    appendStyled(time + "  ",   makeStyle("time",  TEXT_DIM,   false));
    appendStyled(String.format("%-14s  ", actor), makeStyle("actor", actorColor, true));
    appendStyled(message + "\n", makeStyle("msg",   TEXT_PRIMARY, false));

    // Auto-scroll
    logPane.setCaretPosition(doc.getLength());
}
```
> *Listing 36 : SmtpServerGUI.java — `appendLog()`*

### 0.13.3 Démarrage du serveur dans un thread daemon

Extrait de [`SmtpServerGui.java`](mail-system/smtp-server/src/main/java/org/emp/smtp/gui/SmtpServerGui.java) :

```java
private void startServer() {
    if (server != null && server.isRunning()) return;
    int port = (Integer) portSpinner.getValue();
    server = new SmtpServer(port);

    server.setLogListener((actor, message) ->
            SwingUtilities.invokeLater(() -> appendLog(actor, message)));

    // Etape 4 — inject RMI authenticator if checkbox is enabled
    if (chkRmi.isSelected()) {
        String rmiHost = txtRmiHost.getText().trim();
        int rmiPort = (Integer) spnRmiPort.getValue();
        server.setAuthenticator(new SmtpRMIAuthenticator(rmiHost, rmiPort));
        appendLog("SYSTEM", "RMI Auth enabled → " + rmiHost + ":" + rmiPort);
    }

    server.start();
    btnStart.setEnabled(false);
    btnStop.setEnabled(true);
    lblStatus.setText("● RUNNING");
    lblStatus.setForeground(GREEN);
    appendLog("SYSTEM", "Server started on port " + port);
}
```
> *Listing 37 : SmtpServerGui.java — `startServer()`*

### 0.13.4 Session instrumentée — interception des échanges

```java
@Override
public void run() {
    String addr = socket.getInetAddress().getHostAddress();
    try {
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
        serverSend("220 smtp.example.com Service Ready");
        String line;
        while ((line = in.readLine()) != null) {
            gui.appendLog("CLIENT", line);
        }
    } finally {
        gui.updateClientCount(-1);
        gui.appendLog("DISCONNECT", addr + " deconnecte.");
    }
}

private void serverSend(String msg) {
    gui.appendLog("SERVER", msg);
    out.println(msg);
}
```
> *Listing 38 : SmtpSessionGUI.java — `run()` et `serverSend()`*

---

## 0.14 Scénarios de test et résultats {#014-tests-gui}

### 0.14.1 Scénario 0 — Lancement des interfaces de supervision

<!-- IMAGE: Figure 41 — Les trois interfaces de supervision au démarrage — état ARRETE -->
<!-- IMAGE: Figure 42 — Les trois interfaces après démarrage — état EN COURS (badge vert) -->

| Serveur | Port TCP | Protocole | État |
|---------|---------|-----------|------|
| SMTP | 25 | RFC 5321 | EN COURS |
| POP3 | 110 | RFC 1939 | EN COURS |
| IMAP | 143 | RFC 9051 | EN COURS |

> *Table 10 : État des trois serveurs après démarrage*

---

### 0.14.2 Scénario 1 — Test du serveur SMTP

```telnet
telnet localhost 25
EHLO eoc.dz
MAIL FROM:<alice@eoc.dz>
RCPT TO:<bob@eoc.dz>
DATA
Subject: Test TP
Ceci est un message de test.
.
QUIT
```
> *Listing 39 : Session Telnet SMTP (port 25)*

<!-- IMAGE: Figure 43 — GUI SMTP — connexion établie, compteur = 1 client, log CONNECT visible -->
<!-- IMAGE: Figure 44 — Session Telnet SMTP — réponses du serveur (250 Hello, 250 OK, 354, 250 OK) -->
<!-- IMAGE: Figure 45 — GUI SMTP — historique complet : EHLO, MAIL FROM, RCPT TO, DATA, email stocké -->

---

### 0.14.3 Scénario 2 — Test du serveur POP3

```telnet
telnet localhost 110
USER bob
PASS bob
STAT
LIST
RETR 1
QUIT
```
> *Listing 40 : Session Telnet POP3 (port 110)*

<!-- IMAGE: Figure 46 — Session Telnet POP3 — (a) USER/PASS/STAT/LIST  (b) RETR 1 — contenu du message -->
<!-- IMAGE: Figure 47 — GUI POP3 — historique de la session (USER, PASS, STAT, LIST, RETR, QUIT) -->

---

### 0.14.4 Scénario 3 — Test du serveur IMAP

```telnet
telnet localhost 143
a1 CAPABILITY
a2 LOGIN bob bob
a3 SELECT INBOX
a4 FETCH 1 (FLAGS BODY[HEADER])
a5 SEARCH ALL
a6 STORE 1 +FLAGS (\Seen)
a7 LOGOUT
```
> *Listing 41 : Session Telnet IMAP (port 143)*

<!-- IMAGE: Figure 48 — Session Telnet IMAP — CAPABILITY, LOGIN, SELECT, FETCH, SEARCH, STORE -->
<!-- IMAGE: Figure 49 — GUI IMAP — historique détaillé de la session avec authentification et gestion des flags -->

---

### 0.14.5 Scénario 4 — Commande dans un état invalide (FSM)

Le serveur rejette la commande avec `NO SELECT requis`, prouvant que l'automate fonctionne correctement.

<!-- IMAGE: Figure 50 — Scénario automate à états — commande FETCH sans authentification rejetée -->
<!-- IMAGE: Figure 51 — Diagramme de séquence — session SMTP supervisée par la GUI -->

---

### 0.14.6 Scénario 5 — Arrêt propre du serveur

Un clic sur **Arrêter** déclenche la fermeture du `ServerSocket`, la libération du port réseau, et la remise à zéro du compteur de clients.

<!-- IMAGE: Figure 52 — GUI IMAP après arrêt — badge ARRETE (rouge), compteur 0 clients -->

---

## 0.15 Discussion et analyse {#015-discussion}

### 0.15.1 Conformité avec les exigences de l'énoncé

| Exigence | Statut | Remarque |
|----------|--------|---------|
| Démarrer le serveur | ✅ OK | Thread daemon dédié |
| Arrêter proprement | ✅ OK | `ServerSocket.close()` |
| Historique en temps réel | ✅ OK | `appendLog()` + EDT |
| Interfaces indépendantes | ✅ OK | 3 fichiers `.java` séparés |
| Compteur clients (bonus) | ✅ OK | `updateClientCount()` |
| Journalisation (bonus) | ✅ OK | CONNECT/DISCONNECT/ERROR |
| Horodatage (bonus) | ✅ OK | `SimpleDateFormat HH:mm:ss` |

> *Table 11 : Vérification de la conformité avec l'énoncé du TP*

### 0.15.2 Avantages de l'architecture choisie

- **Séparation GUI / logique serveur :** le thread serveur et l'EDT Swing ne se bloquent pas mutuellement grâce à `SwingUtilities.invokeLater()`.
- **Modularité :** chaque GUI est autonome et peut être déployée sur une machine différente.
- **Observabilité :** les échanges client/serveur sont visibles en temps réel avec horodatage.
- **Compatibilité :** les nouvelles classes GUI coexistent avec les classes originales sans modification.

### 0.15.3 Limites et améliorations possibles

- **Persistance des logs :** les historiques ne sont pas sauvegardés sur disque.
- **Coloration dans la zone de log :** utiliser `JTextPane` à la place de `JTextArea`.
- **Ports privilégiés :** utiliser des ports alternatifs en développement (2525, 1110, 1143).
- **TLS/STARTTLS :** sécuriser les communications pour un déploiement en production.

---

## 0.16 Conclusion de la Partie 3 {#016-conclusion-partie3}

La Partie 3 a permis de développer trois interfaces graphiques de supervision, une par protocole, offrant un contrôle complet et une visibilité en temps réel sur les serveurs de messagerie. L'ensemble des fonctionnalités minimales et bonus a été implémenté et validé.

---

# Conclusion générale {#conclusion-generale}

Ce rapport de travaux pratiques a présenté trois étapes successives du développement d'un système de messagerie distribuée en Java.

La **Partie 1** a permis d'identifier et de corriger **14 anomalies** dans les serveurs SMTP et POP3, assurant la conformité avec les RFC 5321 et RFC 1939.

La **Partie 2** a étendu le système avec un serveur IMAP conforme à la RFC 9051, offrant la conservation des messages, la gestion des flags, la lecture partielle et la recherche.

La **Partie 3** a doté chaque serveur d'une interface graphique de supervision indépendante, permettant de visualiser en temps réel tous les échanges et de contrôler le cycle de vie du serveur.

L'ensemble constitue une base solide pour les extensions futures : **authentification RMI**, **persistance MySQL**, **interface Web** et **équilibrage de charge NGINX**.

---

*École Militaire Polytechnique — Département de l'Informatique — TP Systèmes Distribués 2025/2026*

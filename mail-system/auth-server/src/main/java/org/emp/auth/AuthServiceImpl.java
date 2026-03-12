package org.emp.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * RMI server implementation of AuthService (Étape 4).
 *
 * Persistence:
 *   Users are stored in auth-server/data/users.json (configurable via
 *   system property auth.data.dir).  The file is read on every mutating
 *   call and written back atomically (write-to-temp + rename) to avoid
 *   corruption on crash.
 *
 *   JSON structure:
 *   [
 *     { "username":"alice", "passwordHash":"sha256hex", "email":"alice@emp.org", "active":true },
 *     ...
 *   ]
 *
 * Password hashing:
 *   SHA-256 hex digest.  No salt — sufficient for this TP context.
 *   (Étape 5: migrate to BCrypt when moving to MySQL.)
 *
 * Thread safety:
 *   All public methods are synchronized — the RMI runtime may invoke them
 *   from multiple threads concurrently.
 */
public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    private static final Logger log = Logger.getLogger(AuthServiceImpl.class.getName());
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path usersFile;

    // ── Constructor ────────────────────────────────────────────────────────

    public AuthServiceImpl() throws RemoteException {
        super();  // export this object on an anonymous port
        String dataDir = System.getProperty("auth.data.dir", "auth-server/data");
        usersFile = Paths.get(dataDir, "users.json");
        initFile();
        log.info("AuthServiceImpl ready — users file: " + usersFile.toAbsolutePath());
    }

    // ── AuthService implementation ─────────────────────────────────────────

    @Override
    public synchronized boolean authenticate(String username, String password)
            throws RemoteException {
        if (username == null || password == null) return false;
        List<UserRecord> users = load();
        return users.stream().anyMatch(u ->
                u.username.equalsIgnoreCase(username)
                && u.active
                && u.passwordHash.equals(hash(password)));
    }

    @Override
    public synchronized boolean createUser(String username, String password, String email)
            throws RemoteException {
        if (username == null || password == null) return false;
        List<UserRecord> users = load();
        boolean exists = users.stream()
                .anyMatch(u -> u.username.equalsIgnoreCase(username));
        if (exists) {
            log.warning("createUser: username already taken: " + username);
            return false;
        }
        UserRecord r = new UserRecord();
        r.username     = username.toLowerCase();
        r.passwordHash = hash(password);
        r.email        = email != null ? email : username + "@emp.org";
        r.active       = true;
        users.add(r);
        save(users);
        log.info("createUser: created " + username);
        return true;
    }

    @Override
    public synchronized boolean updateUser(String username, String newPassword, String newEmail)
            throws RemoteException {
        List<UserRecord> users = load();
        for (UserRecord r : users) {
            if (r.username.equalsIgnoreCase(username)) {
                if (newPassword != null && !newPassword.isBlank())
                    r.passwordHash = hash(newPassword);
                if (newEmail != null && !newEmail.isBlank())
                    r.email = newEmail;
                save(users);
                log.info("updateUser: updated " + username);
                return true;
            }
        }
        log.warning("updateUser: user not found: " + username);
        return false;
    }

    @Override
    public synchronized boolean deleteUser(String username) throws RemoteException {
        List<UserRecord> users = load();
        boolean removed = users.removeIf(u -> u.username.equalsIgnoreCase(username));
        if (removed) {
            save(users);
            log.info("deleteUser: deleted " + username);
        } else {
            log.warning("deleteUser: user not found: " + username);
        }
        return removed;
    }

    @Override
    public synchronized boolean setActive(String username, boolean active) throws RemoteException {
        List<UserRecord> users = load();
        for (UserRecord r : users) {
            if (r.username.equalsIgnoreCase(username)) {
                r.active = active;
                save(users);
                log.info("setActive: " + username + " → " + active);
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized List<UserDTO> listUsers() throws RemoteException {
        return load().stream()
                .map(r -> new UserDTO(r.username, r.email, r.active))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean userExists(String username) throws RemoteException {
        return load().stream().anyMatch(u -> u.username.equalsIgnoreCase(username));
    }

    // ── JSON persistence ───────────────────────────────────────────────────

    /** Load the users list from disk. Returns empty list if file is missing/empty. */
    private List<UserRecord> load() {
        if (!Files.exists(usersFile)) return new ArrayList<>();
        try {
            String json = Files.readString(usersFile);
            if (json.isBlank()) return new ArrayList<>();
            Type listType = new TypeToken<List<UserRecord>>() {}.getType();
            List<UserRecord> list = GSON.fromJson(json, listType);
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            log.severe("Failed to load users.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Atomically write the users list to disk (temp file + rename). */
    private void save(List<UserRecord> users) {
        try {
            Path tmp = usersFile.resolveSibling("users.json.tmp");
            Files.writeString(tmp, GSON.toJson(users));
            Files.move(tmp, usersFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.severe("Failed to save users.json: " + e.getMessage());
        }
    }

    /** Create the data directory and an empty users.json if they don't exist. */
    private void initFile() {
        try {
            Files.createDirectories(usersFile.getParent());
            if (!Files.exists(usersFile)) {
                Files.writeString(usersFile, "[]");
                log.info("Created empty users.json at " + usersFile.toAbsolutePath());
            }
        } catch (IOException e) {
            log.severe("Cannot initialize users.json: " + e.getMessage());
        }
    }

    // ── Password hashing ───────────────────────────────────────────────────

    static String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Internal record (not exposed over RMI) ─────────────────────────────

    /** POJO serialized to/from JSON by Gson. */
    static class UserRecord {
        String  username;
        String  passwordHash;
        String  email;
        boolean active = true;
    }
}
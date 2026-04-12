package org.emp.auth;

import org.emp.common.AuthService;
import org.emp.common.UserRepository;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed authentication service implementation.
 * Replaces JSON file storage with database via UserRepository.
 */
public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {
    private static final Logger log = Logger.getLogger(AuthServiceImpl.class.getName());
    private final UserRepository userRepo;

    public AuthServiceImpl() throws RemoteException {
        super();
        this.userRepo = new UserRepository();
        log.info("AuthServiceImpl ready — PostgreSQL backend");
    }

    @Override
    public boolean authenticate(String username, String password) throws RemoteException {
        if (username == null || password == null) return false;
        boolean result = userRepo.authenticate(username.toLowerCase(), hash(password));
        log.fine("authenticate: " + username + " → " + result);
        return result;
    }

    @Override
    public boolean createUser(String username, String password, String email) throws RemoteException {
        if (username == null || password == null) return false;
        boolean result = userRepo.createUser(
            username.toLowerCase(),
            hash(password),
            email != null ? email : username.toLowerCase() + "@emp.org"
        );
        return result;
    }

    @Override
    public boolean updateUser(String username, String newPassword, String newEmail) throws RemoteException {
        String newHash = (newPassword != null && !newPassword.isBlank()) ? hash(newPassword) : null;
        return userRepo.updateUser(username.toLowerCase(), newHash, newEmail);
    }

    @Override
    public boolean deleteUser(String username) throws RemoteException {
        return userRepo.deleteUser(username.toLowerCase());
    }

    @Override
    public boolean setActive(String username, boolean active) throws RemoteException {
        return userRepo.setActive(username.toLowerCase(), active);
    }

    @Override
    public List listUsers() throws RemoteException {
        return userRepo.listUsers().stream()
                .map(u -> new UserDTO(u.getUsername(), u.getEmail(), u.isActive()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean userExists(String username) throws RemoteException {
        return userRepo.userExists(username.toLowerCase());
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
}
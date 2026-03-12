package org.emp.auth;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * RMI Remote Interface — Authentication Service (Étape 4).
 *
 * This interface is the ONLY contract shared between:
 *   • auth-server  — implements it (AuthServiceImpl)
 *   • smtp-server  — calls authenticate() before accepting MAIL FROM
 *   • pop3-server  — calls authenticate() in PASS command handler
 *   • imap-server  — calls authenticate() in LOGIN/AUTHENTICATE handler
 *   • admin GUI    — calls createUser / updateUser / deleteUser / listUsers
 *
 * All methods declare RemoteException as required by java.rmi.Remote.
 */
public interface AuthService extends Remote {

    /** RMI registry binding name */
    String BINDING_NAME = "AuthService";

    /** Default RMI registry port */
    int RMI_PORT = 1099;

    // ── Authentication ────────────────────────────────────────────────────

    /**
     * Verify username + plaintext password.
     * The server hashes the password internally before comparing.
     *
     * @return true if credentials are valid AND account is active
     */
    boolean authenticate(String username, String password) throws RemoteException;

    // ── User management ───────────────────────────────────────────────────

    /**
     * Create a new user account.
     * Fails if username already exists.
     *
     * @param username  login name (must be unique, lowercase)
     * @param password  plaintext — hashed by the server before storing
     * @param email     full email address (e.g. alice@emp.org)
     * @return true on success, false if username already taken
     */
    boolean createUser(String username, String password, String email) throws RemoteException;

    /**
     * Update an existing user's password and/or email.
     * Pass null to leave a field unchanged.
     *
     * @return true if user was found and updated
     */
    boolean updateUser(String username, String newPassword, String newEmail) throws RemoteException;

    /**
     * Delete a user account permanently.
     *
     * @return true if user existed and was deleted
     */
    boolean deleteUser(String username) throws RemoteException;

    /**
     * Enable or disable a user account without deleting it.
     * Disabled users fail authenticate() even with correct credentials.
     */
    boolean setActive(String username, boolean active) throws RemoteException;

    /**
     * Return all user accounts (passwords redacted — passwordHash is null).
     * Used by the admin GUI to populate the user list.
     */
    List<UserDTO> listUsers() throws RemoteException;

    /**
     * Check whether a username exists (regardless of active/inactive).
     */
    boolean userExists(String username) throws RemoteException;
}
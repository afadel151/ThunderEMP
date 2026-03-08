package org.emp.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Shared database connection utility.
 * Used by SMTP, POP3, IMAP, and Auth servers (Étape 5).
 */
public class DBConnection {

    private static String url;
    private static String user;
    private static String password;

    static {
        try (InputStream input = DBConnection.class
                .getClassLoader().getResourceAsStream("db.properties")) {
            Properties props = new Properties();
            props.load(input);
            url      = props.getProperty("db.url");
            user     = props.getProperty("db.user");
            password = props.getProperty("db.password");
        } catch (Exception e) {
            throw new RuntimeException("Cannot load db.properties", e);
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}

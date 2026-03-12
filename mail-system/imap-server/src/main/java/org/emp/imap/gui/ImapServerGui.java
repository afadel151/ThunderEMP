package org.emp.imap.gui;

import org.emp.imap.ImapServer;
import org.emp.imap.ImapRMIAuthenticator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Interface graphique de supervision du serveur IMAP (Étape 3).
 *
 * Fonctionnalités :
 *  • Démarrer / Arrêter le serveur IMAP
 *  • Afficher en temps réel les commandes clients et réponses serveur
 *  • Changer le port d'écoute avant démarrage
 *  • Effacer l'historique
 *  • Compteur de connexions, d'authentifications et de FETCH
 *  • Coloration syntaxique par état IMAP (LOGIN, SELECT, FETCH, STORE…)
 *
 * Lancement autonome :
 *   java -cp target/imap-server-1.0.0.jar org.emp.imap.gui.ImapServerGui
 */
public class ImapServerGui extends JFrame {

    // ── Palette "terminal teal" ────────────────────────────────────────────
    private static final Color BG_DARK      = new Color(0x08, 0x10, 0x10);
    private static final Color BG_PANEL     = new Color(0x0C, 0x18, 0x18);
    private static final Color BG_CARD      = new Color(0x12, 0x22, 0x22);
    private static final Color ACCENT       = new Color(0x00, 0xD4, 0xAA); // teal
    private static final Color ACCENT_DIM   = new Color(0x00, 0x7A, 0x62);
    private static final Color GREEN        = new Color(0x39, 0xD3, 0x53);
    private static final Color RED_STOP     = new Color(0xFF, 0x3B, 0x3B);
    private static final Color YELLOW       = new Color(0xFF, 0xD7, 0x6E);
    private static final Color TEXT_PRIMARY = new Color(0xDF, 0xEC, 0xEC);
    private static final Color TEXT_DIM     = new Color(0x4A, 0x60, 0x60);
    private static final Color CLIENT_CLR   = new Color(0x7FFFD4);        // aquamarine
    private static final Color SERVER_CLR   = new Color(0xFF, 0xC8, 0x6E);
    private static final Color INFO_CLR     = new Color(0x90, 0xFF, 0xBF);
    private static final Color CMD_SELECT   = new Color(0xB0, 0xFF, 0xFF);
    private static final Color CMD_FETCH    = new Color(0xA0, 0xE0, 0xFF);
    private static final Color CMD_STORE    = new Color(0xFF, 0xA0, 0xC0);
    private static final Color CMD_LOGIN    = new Color(0xA0, 0xFF, 0xA0);
    private static final Color BORDER_CLR   = new Color(0x1A, 0x30, 0x30);

    private static final Font  MONO         = new Font("Courier New", Font.PLAIN, 12);
    private static final Font  UI_FONT      = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font  UI_BOLD      = new Font("Segoe UI", Font.BOLD,  13);
    private static final Font  TITLE_FONT   = new Font("Segoe UI", Font.BOLD,  18);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── State ──────────────────────────────────────────────────────────────
    private ImapServer server;
    private int        connectionCount = 0;
    private int        authCount       = 0;
    private int        fetchCount      = 0;

    // ── RMI auth fields (Étape 4) ──────────────────────────────────────────
    private JCheckBox  chkRmi;
    private JTextField txtRmiHost;
    private JSpinner   spnRmiPort;

    // ── UI ─────────────────────────────────────────────────────────────────
    private JTextPane  logPane;
    private StyledDocument doc;
    private JButton    btnStart, btnStop, btnClear;
    private JSpinner   portSpinner;
    private JLabel     lblStatus, lblConnections, lblAuth, lblFetch, lblPort;
    private Timer      pulseTimer;
    private boolean    pulsed = false;

    public ImapServerGui() {
        super("IMAP Server — Supervision Console");
        buildUi();
        setupPulse();
    }

    // ── UI construction ────────────────────────────────────────────────────

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setPreferredSize(new Dimension(1100, 720));
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_CLR),
                new EmptyBorder(14, 20, 14, 20)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel badge = new JLabel("IMAP4rev2");
        badge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        badge.setForeground(BG_DARK);
        badge.setBackground(ACCENT);
        badge.setOpaque(true);
        badge.setBorder(new EmptyBorder(3, 8, 3, 8));

        JLabel title = new JLabel("Server Supervision Console");
        title.setFont(TITLE_FONT);
        title.setForeground(TEXT_PRIMARY);

        JLabel rfc = new JLabel("RFC 9051");
        rfc.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        rfc.setForeground(TEXT_DIM);

        left.add(badge);
        left.add(title);
        left.add(Box.createHorizontalStrut(8));
        left.add(rfc);

        lblStatus = new JLabel("● STOPPED");
        lblStatus.setFont(UI_BOLD);
        lblStatus.setForeground(RED_STOP);

        p.add(left, BorderLayout.WEST);
        p.add(lblStatus, BorderLayout.EAST);
        return p;
    }

    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(16, 16, 0, 16));
        p.add(buildControlCard(), BorderLayout.NORTH);
        p.add(buildLogPanel(),    BorderLayout.CENTER);
        return p;
    }

    private JPanel buildControlCard() {
        JPanel card = new JPanel(new BorderLayout(20, 0));
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                new EmptyBorder(12, 16, 12, 16)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel portLabel = new JLabel("Port:");
        portLabel.setFont(UI_FONT);
        portLabel.setForeground(TEXT_DIM);

        portSpinner = new JSpinner(new SpinnerNumberModel(143, 1, 65535, 1));
        portSpinner.setFont(MONO);
        portSpinner.setPreferredSize(new Dimension(80, 30));
        styleSpinner(portSpinner);

        btnStart = makeButton("▶  Start", GREEN,    BG_CARD);
        btnStop  = makeButton("■  Stop",  RED_STOP, BG_CARD);
        btnClear = makeButton("⊘  Clear", TEXT_DIM, BG_CARD);
        btnStop.setEnabled(false);

        btnStart.addActionListener(e -> startServer());
        btnStop.addActionListener(e  -> stopServer());
        btnClear.addActionListener(e -> clearLog());

        left.add(portLabel);
        left.add(portSpinner);
        left.add(Box.createHorizontalStrut(6));
        left.add(btnStart);
        left.add(btnStop);
        left.add(Box.createHorizontalStrut(12));
        left.add(btnClear);

        // RMI auth controls (Étape 4)
        left.add(Box.createHorizontalStrut(16));
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));
        sep.setForeground(BORDER_CLR);
        left.add(sep);
        left.add(Box.createHorizontalStrut(12));

        chkRmi = new JCheckBox("RMI Auth");
        chkRmi.setFont(UI_FONT);
        chkRmi.setForeground(TEXT_DIM);
        chkRmi.setBackground(BG_CARD);
        chkRmi.setFocusPainted(false);

        JLabel rmiHostLbl = new JLabel("Host:");
        rmiHostLbl.setFont(UI_FONT);
        rmiHostLbl.setForeground(TEXT_DIM);

        txtRmiHost = new JTextField("localhost", 8);
        txtRmiHost.setBackground(BG_DARK);
        txtRmiHost.setForeground(TEXT_PRIMARY);
        txtRmiHost.setCaretColor(ACCENT);
        txtRmiHost.setFont(MONO);
        txtRmiHost.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        txtRmiHost.setEnabled(false);

        JLabel rmiPortLbl = new JLabel("Port:");
        rmiPortLbl.setFont(UI_FONT);
        rmiPortLbl.setForeground(TEXT_DIM);

        spnRmiPort = new JSpinner(new SpinnerNumberModel(1099, 1, 65535, 1));
        spnRmiPort.setPreferredSize(new Dimension(72, 28));
        styleSpinner(spnRmiPort);
        spnRmiPort.setEnabled(false);

        chkRmi.addActionListener(e -> {
            boolean on = chkRmi.isSelected();
            chkRmi.setForeground(on ? ACCENT : TEXT_DIM);
            txtRmiHost.setEnabled(on);
            spnRmiPort.setEnabled(on);
        });

        left.add(chkRmi);
        left.add(rmiHostLbl);
        left.add(txtRmiHost);
        left.add(rmiPortLbl);
        left.add(spnRmiPort);

        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 0));
        stats.setOpaque(false);
        lblPort        = makeStatLabel("Port",        "—");
        lblConnections = makeStatLabel("Connections", "0");
        lblAuth        = makeStatLabel("Logins",      "0");
        lblFetch       = makeStatLabel("FETCH ops",   "0");
        stats.add(lblPort);
        stats.add(makeDivider());
        stats.add(lblConnections);
        stats.add(makeDivider());
        stats.add(lblAuth);
        stats.add(makeDivider());
        stats.add(lblFetch);

        card.add(left,  BorderLayout.WEST);
        card.add(stats, BorderLayout.EAST);
        return card;
    }

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(12, 0, 0, 0));

        // Legend row
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        legend.setBackground(BG_PANEL);
        legend.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, BORDER_CLR),
                new EmptyBorder(0, 6, 0, 6)));

        JLabel lh = new JLabel("TIME      ACTOR             MESSAGE");
        lh.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lh.setForeground(TEXT_DIM);
        legend.add(lh, BorderLayout.WEST);

        for (String[] kv : new String[][]{
                {"LOGIN", String.format("#%06X", CMD_LOGIN.getRGB() & 0xFFFFFF)},
                {"SELECT", String.format("#%06X", CMD_SELECT.getRGB() & 0xFFFFFF)},
                {"FETCH", String.format("#%06X", CMD_FETCH.getRGB() & 0xFFFFFF)},
                {"STORE", String.format("#%06X", CMD_STORE.getRGB() & 0xFFFFFF)}}) {
            JLabel lbl = new JLabel("■ " + kv[0]);
            lbl.setFont(new Font("Segoe UI", Font.BOLD, 10));
            lbl.setForeground(Color.decode(kv[1]));
            legend.add(lbl);
        }

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(BG_PANEL);
        logPane.setBorder(new EmptyBorder(6, 10, 6, 10));
        logPane.setFont(MONO);
        doc = logPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(logPane);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        styleScrollBar(scroll.getVerticalScrollBar());

        p.add(legend, BorderLayout.NORTH);
        p.add(scroll,  BorderLayout.CENTER);
        return p;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_CLR));
        for (String[] kv : new String[][]{{"F5","Start"},{"F6","Stop"},{"Ctrl+L","Clear"}}) {
            JLabel k = new JLabel(kv[0]);
            k.setFont(new Font("Segoe UI", Font.BOLD, 11));
            k.setForeground(ACCENT);
            k.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT_DIM),
                    new EmptyBorder(1, 5, 1, 5)));
            JLabel v = new JLabel(kv[1]);
            v.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            v.setForeground(TEXT_DIM);
            p.add(k); p.add(v);
        }
        getRootPane().registerKeyboardAction(e -> startServer(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> stopServer(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> clearLog(),
                KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        return p;
    }

    // ── Server lifecycle ───────────────────────────────────────────────────

    private void startServer() {
        if (server != null && server.isRunning()) return;
        int port = (Integer) portSpinner.getValue();
        server = new ImapServer(port);

        server.setLogListener((actor, message) ->
                SwingUtilities.invokeLater(() -> appendLog(actor, message)));

        // Étape 4 — inject RMI authenticator if checkbox is enabled
        if (chkRmi.isSelected()) {
            String rmiHost = txtRmiHost.getText().trim();
            int rmiPort = (Integer) spnRmiPort.getValue();
            server.setAuthenticator(new ImapRMIAuthenticator(rmiHost, rmiPort));
            appendLog("SYSTEM", "RMI Auth enabled → " + rmiHost + ":" + rmiPort);
        }

        server.start();

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        portSpinner.setEnabled(false);
        lblStatus.setText("● RUNNING");
        lblStatus.setForeground(GREEN);
        lblPort.setText("Port  " + port);
        appendLog("SYSTEM", "IMAP4rev2 server started on port " + port);
    }

    private void stopServer() {
        if (server == null || !server.isRunning()) return;
        server.stop();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        portSpinner.setEnabled(true);
        lblStatus.setText("● STOPPED");
        lblStatus.setForeground(RED_STOP);
        appendLog("SYSTEM", "Server stopped.");
    }

    private void clearLog() {
        try { doc.remove(0, doc.getLength()); }
        catch (BadLocationException ignored) {}
    }

    // ── Log rendering with IMAP-aware coloring ─────────────────────────────

    private void appendLog(String actor, String message) {
        try {
            String time = LocalTime.now().format(TIME_FMT);
            boolean isClient = actor.toUpperCase().contains("CLIENT");
            boolean isSystem = actor.equals("SYSTEM");

            // Update stats
            if (message.contains("Connection from")) {
                connectionCount++;
                SwingUtilities.invokeLater(() -> lblConnections.setText("Connections  " + connectionCount));
            }
            if (isClient) {
                String upper = message.trim().toUpperCase();
                // Strip tag prefix (e.g. "A001 LOGIN" → "LOGIN")
                String cmd = upper.contains(" ") ? upper.substring(upper.indexOf(' ') + 1) : upper;
                if (cmd.startsWith("LOGIN") || cmd.startsWith("AUTHENTICATE")) {
                    authCount++;
                    SwingUtilities.invokeLater(() -> lblAuth.setText("Logins  " + authCount));
                }
                if (cmd.startsWith("FETCH") || cmd.startsWith("UID FETCH")) {
                    fetchCount++;
                    SwingUtilities.invokeLater(() -> lblFetch.setText("FETCH ops  " + fetchCount));
                }
            }

            // Determine message color based on IMAP command
            Color msgColor = resolveMessageColor(actor, message, isSystem, isClient);
            Color actorColor = isClient ? CLIENT_CLR : isSystem ? INFO_CLR : SERVER_CLR;

            appendStyled(time + "  ",                              makeStyle(TEXT_DIM,   false));
            appendStyled(String.format("%-18s", actor) + "  ",    makeStyle(actorColor, true));
            appendStyled(message + "\n",                           makeStyle(msgColor,   false));

            logPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {}
    }

    /** Pick a highlight color based on IMAP command keyword in the message. */
    private Color resolveMessageColor(String actor, String msg, boolean isSystem, boolean isClient) {
        if (isSystem) return TEXT_DIM;
        String upper = msg.toUpperCase();
        // Strip tag to get bare command
        String bare = upper.contains(" ") ? upper.substring(upper.indexOf(' ') + 1) : upper;

        if (isClient) {
            if (bare.startsWith("LOGIN") || bare.startsWith("AUTHENTICATE")) return CMD_LOGIN;
            if (bare.startsWith("SELECT") || bare.startsWith("EXAMINE"))     return CMD_SELECT;
            if (bare.startsWith("FETCH")  || bare.startsWith("UID FETCH"))   return CMD_FETCH;
            if (bare.startsWith("STORE")  || bare.startsWith("UID STORE"))   return CMD_STORE;
            if (bare.startsWith("LOGOUT"))                                    return RED_STOP;
            return CLIENT_CLR;
        } else {
            // Server responses
            if (upper.contains("BYE"))            return RED_STOP;
            if (upper.contains("OK LOGIN") || upper.contains("OK AUTHENTICATE")) return CMD_LOGIN;
            if (upper.contains("OK SELECT") || upper.contains("OK EXAMINE"))     return CMD_SELECT;
            if (upper.contains("EXISTS"))          return YELLOW;
            if (upper.contains("NO ") || upper.contains("[NO"))                  return RED_STOP;
            return SERVER_CLR;
        }
    }

    private void appendStyled(String text, AttributeSet style) {
        try { doc.insertString(doc.getLength(), text, style); }
        catch (BadLocationException ignored) {}
    }

    private AttributeSet makeStyle(Color color, boolean bold) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        StyleConstants.setFontFamily(s, "Courier New");
        StyleConstants.setFontSize(s, 12);
        return s;
    }

    private void setupPulse() {
        pulseTimer = new Timer(800, e -> {
            if (server != null && server.isRunning()) {
                pulsed = !pulsed;
                lblStatus.setForeground(pulsed ? GREEN : GREEN.darker().darker());
            }
        });
        pulseTimer.start();
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private JButton makeButton(String text, Color fg, Color bg) {
        JButton b = new JButton(text);
        b.setFont(UI_BOLD);
        b.setForeground(fg);
        b.setBackground(BG_CARD);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker()),
                new EmptyBorder(6, 14, 6, 14)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(fg.darker().darker()); }
            public void mouseExited (MouseEvent e) { b.setBackground(BG_CARD); }
        });
        return b;
    }

    private JLabel makeStatLabel(String label, String value) {
        JLabel l = new JLabel(label + "  " + value);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        l.setForeground(TEXT_DIM);
        return l;
    }

    private JSeparator makeDivider() {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 16));
        sep.setForeground(BORDER_CLR);
        return sep;
    }

    private void styleSpinner(JSpinner sp) {
        sp.setBackground(BG_DARK);
        JComponent editor = sp.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
            tf.setBackground(BG_DARK);
            tf.setForeground(TEXT_PRIMARY);
            tf.setCaretColor(ACCENT);
            tf.setFont(MONO);
            tf.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        }
    }

    private void styleScrollBar(JScrollBar sb) {
        sb.setBackground(BG_PANEL);
        sb.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            protected void configureScrollBarColors() {
                this.thumbColor = BORDER_CLR;
                this.trackColor = BG_PANEL;
            }
            protected JButton createDecreaseButton(int o) { return zeroButton(); }
            protected JButton createIncreaseButton(int o) { return zeroButton(); }
            private JButton zeroButton() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });
    }

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new ImapServerGui().setVisible(true));
    }
}
package org.emp.smtp.gui;

import org.emp.smtp.SmtpServer;
import org.emp.smtp.SmtpRMIAuthenticator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Interface graphique de supervision du serveur SMTP (Étape 3).
 *
 * Fonctionnalités :
 *  • Démarrer / Arrêter le serveur SMTP
 *  • Afficher en temps réel les commandes clients et réponses serveur
 *  • Changer le port d'écoute avant démarrage
 *  • Effacer l'historique
 *  • Compteur de connexions actives
 *
 * Lancement autonome :
 *   java -cp target/smtp-server-1.0.0.jar dz.eoc.smtp.gui.SmtpServerGui
 */
public class SmtpServerGui extends JFrame {

    // ── Palette "terminal orange" ──────────────────────────────────────────
    private static final Color BG_DARK      = new Color(0x0D, 0x0F, 0x12);
    private static final Color BG_PANEL     = new Color(0x13, 0x16, 0x1A);
    private static final Color BG_CARD      = new Color(0x1A, 0x1E, 0x24);
    private static final Color ACCENT       = new Color(0xFF, 0x6B, 0x00); // orange
    private static final Color ACCENT_DIM   = new Color(0x99, 0x40, 0x00);
    private static final Color GREEN        = new Color(0x39, 0xD3, 0x53);
    private static final Color RED_STOP     = new Color(0xFF, 0x3B, 0x3B);
    private static final Color TEXT_PRIMARY = new Color(0xE8, 0xEA, 0xED);
    private static final Color TEXT_DIM     = new Color(0x6B, 0x72, 0x80);
    private static final Color CLIENT_CLR   = new Color(0x5B, 0xD3, 0xFF);
    private static final Color SERVER_CLR   = new Color(0xFC, 0xC4, 0x6E);
    private static final Color INFO_CLR     = new Color(0x8B, 0xFF, 0xB4);
    private static final Color BORDER_CLR   = new Color(0x2A, 0x2F, 0x38);

    private static final Font  MONO         = new Font("JetBrains Mono", Font.PLAIN, 12);
    private static final Font  MONO_BOLD    = new Font("JetBrains Mono", Font.BOLD,  12);
    private static final Font  UI_FONT      = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font  UI_BOLD      = new Font("Segoe UI", Font.BOLD,  13);
    private static final Font  TITLE_FONT   = new Font("Segoe UI", Font.BOLD,  18);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── State ──────────────────────────────────────────────────────────────
    private SmtpServer       server;
    private int              connectionCount = 0;
    private int              messageCount    = 0;

    // ── RMI auth fields ────────────────────────────────────────────────────
    private JCheckBox        chkRmi;
    private JTextField       txtRmiHost;
    private JSpinner         spnRmiPort;

    // ── UI components ──────────────────────────────────────────────────────
    private JTextPane        logPane;
    private StyledDocument   doc;
    private JButton          btnStart;
    private JButton          btnStop;
    private JButton          btnClear;
    private JSpinner         portSpinner;
    private JLabel           lblStatus;
    private JLabel           lblConnections;
    private JLabel           lblMessages;
    private JLabel           lblPort;
    private Timer            pulseTimer;
    private boolean          pulsed = false;

    // ── Constructor ────────────────────────────────────────────────────────

    public SmtpServerGui() {
        super("SMTP Server — Supervision Console");
        buildUi();
        setupPulse();
    }

    // ── UI construction ────────────────────────────────────────────────────

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setPreferredSize(new Dimension(1050, 700));
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildCenter(),  BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    /** Top header bar with title and status indicator. */
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_CLR),
                new EmptyBorder(14, 20, 14, 20)));

        // Left: protocol badge + title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel badge = new JLabel("SMTP");
        badge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        badge.setForeground(BG_DARK);
        badge.setBackground(ACCENT);
        badge.setOpaque(true);
        badge.setBorder(new EmptyBorder(3, 8, 3, 8));

        JLabel title = new JLabel("Server Supervision Console");
        title.setFont(TITLE_FONT);
        title.setForeground(TEXT_PRIMARY);

        left.add(badge);
        left.add(title);

        // Right: status pill
        lblStatus = new JLabel("● STOPPED");
        lblStatus.setFont(UI_BOLD);
        lblStatus.setForeground(RED_STOP);

        p.add(left, BorderLayout.WEST);
        p.add(lblStatus, BorderLayout.EAST);
        return p;
    }

    /** Center: controls card + log pane. */
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(16, 16, 0, 16));

        p.add(buildControlCard(), BorderLayout.NORTH);
        p.add(buildLogPanel(),    BorderLayout.CENTER);
        return p;
    }

    /** Control row: port selector, start/stop, stats. */
    private JPanel buildControlCard() {
        JPanel card = new JPanel(new BorderLayout(20, 0));
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                new EmptyBorder(12, 16, 12, 16)));

        // Left group: port + buttons
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel portLabel = new JLabel("Port:");
        portLabel.setFont(UI_FONT);
        portLabel.setForeground(TEXT_DIM);

        portSpinner = new JSpinner(new SpinnerNumberModel(25, 1, 65535, 1));
        portSpinner.setFont(MONO);
        portSpinner.setPreferredSize(new Dimension(80, 30));
        styleSpinner(portSpinner);

        btnStart = makeButton("▶  Start", GREEN, BG_DARK);
        btnStop  = makeButton("■  Stop",  RED_STOP, BG_DARK);
        btnClear = makeButton("⊘  Clear Log", TEXT_DIM, BG_CARD);
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

        // RMI auth controls
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

        // Right group: live stats
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 0));
        stats.setOpaque(false);

        lblPort        = makeStatLabel("Port", "—");
        lblConnections = makeStatLabel("Connections", "0");
        lblMessages    = makeStatLabel("Messages", "0");

        stats.add(lblPort);
        stats.add(makeDivider());
        stats.add(lblConnections);
        stats.add(makeDivider());
        stats.add(lblMessages);

        card.add(left,  BorderLayout.WEST);
        card.add(stats, BorderLayout.EAST);
        return card;
    }

    /** Log area with column headers. */
    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(12, 0, 0, 0));

        // Column header bar
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
        header.setBackground(BG_PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, BORDER_CLR),
                new EmptyBorder(0, 6, 0, 0)));

        JLabel h1 = new JLabel("TIME");
        JLabel h2 = new JLabel("ACTOR");
        JLabel h3 = new JLabel("MESSAGE");
        for (JLabel h : new JLabel[]{h1, h2, h3}) {
            h.setFont(new Font("Segoe UI", Font.BOLD, 10));
            h.setForeground(TEXT_DIM);
            header.add(h);
        }

        // Log pane
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

        p.add(header, BorderLayout.NORTH);
        p.add(scroll,  BorderLayout.CENTER);
        return p;
    }

    /** Footer: keyboard shortcuts hint. */
    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_CLR));

        for (String[] kv : new String[][]{
                {"F5", "Start"}, {"F6", "Stop"}, {"Ctrl+L", "Clear log"}}) {
            JLabel k = new JLabel(kv[0]);
            k.setFont(new Font("Segoe UI", Font.BOLD, 11));
            k.setForeground(ACCENT);
            k.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT_DIM),
                    new EmptyBorder(1, 5, 1, 5)));
            JLabel v = new JLabel(kv[1]);
            v.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            v.setForeground(TEXT_DIM);
            p.add(k);
            p.add(v);
        }

        // Wire keyboard shortcuts
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
        server = new SmtpServer(port);

        server.setLogListener((actor, message) ->
                SwingUtilities.invokeLater(() -> appendLog(actor, message)));

        // Étape 4 — inject RMI authenticator if checkbox is enabled
        if (chkRmi.isSelected()) {
            String rmiHost = txtRmiHost.getText().trim();
            int rmiPort = (Integer) spnRmiPort.getValue();
            server.setAuthenticator(new SmtpRMIAuthenticator(rmiHost, rmiPort));
            appendLog("SYSTEM", "RMI Auth enabled → " + rmiHost + ":" + rmiPort);
        }

        server.start();

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        portSpinner.setEnabled(false);
        lblStatus.setText("● RUNNING");
        lblStatus.setForeground(GREEN);
        lblPort.setText("Port  " + port);
        appendLog("SYSTEM", "Server started on port " + port);
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

    // ── Log rendering ──────────────────────────────────────────────────────

    private void appendLog(String actor, String message) {
        try {
            String time = LocalTime.now().format(TIME_FMT);
            boolean isClient = actor.toUpperCase().contains("CLIENT");
            boolean isServer = actor.toUpperCase().contains("SERVER") && !actor.equals("SYSTEM");
            boolean isSystem = actor.equals("SYSTEM");

            Color actorColor = isClient ? CLIENT_CLR
                             : isSystem ? INFO_CLR
                             : SERVER_CLR;
            Color msgColor   = isSystem ? TEXT_DIM : TEXT_PRIMARY;

            // Bump stats
            if (isClient && message.toUpperCase().startsWith("MAIL FROM")) messageCount++;
            if (message.contains("Connection from")) {
                connectionCount++;
                SwingUtilities.invokeLater(() -> {
                    lblConnections.setText("Connections  " + connectionCount);
                    lblMessages.setText("Messages  " + messageCount);
                });
            }
            if (isClient && message.toUpperCase().startsWith("MAIL FROM")) {
                SwingUtilities.invokeLater(() -> lblMessages.setText("Messages  " + messageCount));
            }

            // Time
            appendStyled(time + "  ", makeStyle("time",   TEXT_DIM,   false));
            // Actor padded to 14 chars
            String actorStr = String.format("%-14s", actor);
            appendStyled(actorStr + "  ", makeStyle("actor", actorColor, true));
            // Message
            appendStyled(message + "\n", makeStyle("msg",   msgColor,   false));

            // Auto-scroll
            logPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {}
    }

    private void appendStyled(String text, AttributeSet style) {
        try { doc.insertString(doc.getLength(), text, style); }
        catch (BadLocationException ignored) {}
    }

    private AttributeSet makeStyle(String name, Color color, boolean bold) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        StyleConstants.setFontFamily(s, "JetBrains Mono");
        StyleConstants.setFontSize(s, 12);
        return s;
    }

    // ── Pulse animation on status dot ──────────────────────────────────────

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
        sp.setForeground(TEXT_PRIMARY);
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
                this.thumbColor         = BORDER_CLR;
                this.thumbHighlightColor = ACCENT_DIM;
                this.trackColor         = BG_PANEL;
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
        // Apply dark title bar on supported platforms
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SmtpServerGui gui = new SmtpServerGui();
            gui.setVisible(true);
        });
    }
}
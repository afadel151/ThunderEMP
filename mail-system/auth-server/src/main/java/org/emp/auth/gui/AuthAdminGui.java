package org.emp.auth.gui;

import org.emp.auth.AuthService;
import org.emp.auth.UserDTO;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Vector;

/**
 * RMI Admin Client GUI (Étape 4).
 *
 * Connects to the AuthService RMI server and allows an administrator to:
 *   • Add a user
 *   • Edit a user (password, email, active/disabled)
 *   • Delete a user
 *   • Refresh the user list
 *
 * Launch:
 *   java -jar auth-server/target/auth-server.jar org.emp.auth.gui.AuthAdminGui
 *   # or with custom RMI host/port:
 *   java -Drmi.host=192.168.1.10 -Drmi.port=1099 \
 *        -cp auth-server/target/auth-server.jar org.emp.auth.gui.AuthAdminGui
 */
public class AuthAdminGui extends JFrame {

    // ── Palette "dark purple admin" ────────────────────────────────────────
    private static final Color BG_DARK    = new Color(0x0E, 0x0B, 0x18);
    private static final Color BG_PANEL   = new Color(0x16, 0x12, 0x24);
    private static final Color BG_CARD    = new Color(0x1E, 0x19, 0x30);
    private static final Color ACCENT     = new Color(0xA7, 0x6F, 0xFF); // purple
    private static final Color ACCENT_DIM = new Color(0x5C, 0x3C, 0x99);
    private static final Color GREEN      = new Color(0x39, 0xD3, 0x53);
    private static final Color RED        = new Color(0xFF, 0x3B, 0x3B);
    private static final Color YELLOW     = new Color(0xFF, 0xD7, 0x6E);
    private static final Color TEXT_PRI   = new Color(0xE8, 0xE4, 0xF4);
    private static final Color TEXT_DIM   = new Color(0x68, 0x60, 0x88);
    private static final Color BORDER     = new Color(0x2C, 0x26, 0x44);

    private static final Font UI       = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font UI_BOLD  = new Font("Segoe UI", Font.BOLD,  13);
    private static final Font TITLE    = new Font("Segoe UI", Font.BOLD,  17);
    private static final Font MONO     = new Font("Consolas", Font.PLAIN, 12);

    // ── State ──────────────────────────────────────────────────────────────
    private AuthService          stub;
    private DefaultTableModel    tableModel;

    // ── Connection panel ───────────────────────────────────────────────────
    private JTextField  txtHost;
    private JSpinner    spnPort;
    private JButton     btnConnect;
    private JLabel      lblConnStatus;

    // ── Table ──────────────────────────────────────────────────────────────
    private JTable      table;

    // ── Buttons ────────────────────────────────────────────────────────────
    private JButton     btnAdd, btnEdit, btnDelete, btnToggle, btnRefresh;

    // ── Status bar ─────────────────────────────────────────────────────────
    private JLabel      lblStatus;

    public AuthAdminGui() {
        super("RMI Auth Server — Admin Console");
        buildUi();
    }

    // ── UI construction ────────────────────────────────────────────────────

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(820, 580));
        setMinimumSize(new Dimension(700, 480));
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),     BorderLayout.NORTH);
        add(buildCenter(),     BorderLayout.CENTER);
        add(buildStatusBar(),  BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(12, 20, 12, 20)));

        // Badge + title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        JLabel badge = new JLabel("RMI");
        badge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        badge.setForeground(BG_DARK);
        badge.setBackground(ACCENT);
        badge.setOpaque(true);
        badge.setBorder(new EmptyBorder(3, 8, 3, 8));
        JLabel title = new JLabel("Authentication Server — Admin Console");
        title.setFont(TITLE);
        title.setForeground(TEXT_PRI);
        left.add(badge);
        left.add(title);

        // Connection row
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);

        JLabel hostLabel = new JLabel("Host:");
        hostLabel.setFont(UI);
        hostLabel.setForeground(TEXT_DIM);

        txtHost = new JTextField(System.getProperty("rmi.host", "localhost"), 10);
        styleTextField(txtHost);

        JLabel portLabel = new JLabel("Port:");
        portLabel.setFont(UI);
        portLabel.setForeground(TEXT_DIM);

        spnPort = new JSpinner(new SpinnerNumberModel(
                Integer.parseInt(System.getProperty("rmi.port", "1099")), 1, 65535, 1));
        spnPort.setPreferredSize(new Dimension(70, 28));
        styleSpinner(spnPort);

        btnConnect = makeButton("Connect", ACCENT, BG_CARD);
        btnConnect.addActionListener(e -> connect());

        lblConnStatus = new JLabel("○ Disconnected");
        lblConnStatus.setFont(UI_BOLD);
        lblConnStatus.setForeground(RED);

        right.add(hostLabel); right.add(txtHost);
        right.add(portLabel); right.add(spnPort);
        right.add(btnConnect);
        right.add(Box.createHorizontalStrut(12));
        right.add(lblConnStatus);

        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(16, 16, 16, 16));

        p.add(buildToolbar(), BorderLayout.NORTH);
        p.add(buildTable(),   BorderLayout.CENTER);
        return p;
    }

    private JPanel buildToolbar() {
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tb.setBackground(BG_CARD);
        tb.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, BORDER),
                new EmptyBorder(8, 12, 8, 12)));

        btnAdd     = makeButton("＋  Add User",    GREEN,  BG_CARD);
        btnEdit    = makeButton("✎  Edit",         YELLOW, BG_CARD);
        btnDelete  = makeButton("✕  Delete",       RED,    BG_CARD);
        btnToggle  = makeButton("⏸  Toggle Active", ACCENT, BG_CARD);
        btnRefresh = makeButton("⟳  Refresh",      TEXT_DIM, BG_CARD);

        for (JButton b : new JButton[]{btnAdd, btnEdit, btnDelete, btnToggle, btnRefresh})
            b.setEnabled(false);

        btnAdd.addActionListener(e    -> showAddDialog());
        btnEdit.addActionListener(e   -> showEditDialog());
        btnDelete.addActionListener(e -> deleteSelected());
        btnToggle.addActionListener(e -> toggleSelected());
        btnRefresh.addActionListener(e -> refreshTable());

        tb.add(btnAdd); tb.add(btnEdit); tb.add(btnDelete);
        tb.add(makeSep());
        tb.add(btnToggle);
        tb.add(makeSep());
        tb.add(btnRefresh);
        return tb;
    }

    private JScrollPane buildTable() {
        tableModel = new DefaultTableModel(
                new String[]{"Username", "Email", "Status"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel);
        table.setBackground(BG_PANEL);
        table.setForeground(TEXT_PRI);
        table.setFont(MONO);
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(ACCENT_DIM);
        table.setSelectionForeground(TEXT_PRI);
        table.getTableHeader().setBackground(BG_CARD);
        table.getTableHeader().setForeground(TEXT_DIM);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        table.getTableHeader().setBorder(BorderFactory.createLineBorder(BORDER));

        // Color active/disabled rows
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                setBackground(sel ? ACCENT_DIM : BG_PANEL);
                String status = (String) tableModel.getValueAt(row, 2);
                if (!sel) setForeground("disabled".equals(status) ? TEXT_DIM : TEXT_PRI);
                if (col == 2) setForeground("active".equals(status) ? GREEN : RED);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        });

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(BG_PANEL);
        styleScrollBar(scroll.getVerticalScrollBar());
        return scroll;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 5));
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        lblStatus = new JLabel("Not connected — enter host/port and click Connect.");
        lblStatus.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblStatus.setForeground(TEXT_DIM);
        p.add(lblStatus);
        return p;
    }

    // ── Connection ─────────────────────────────────────────────────────────

    private void connect() {
        String host = txtHost.getText().trim();
        int port    = (Integer) spnPort.getValue();
        try {
            Registry registry = LocateRegistry.getRegistry(host, port);
            stub = (AuthService) registry.lookup(AuthService.BINDING_NAME);
            lblConnStatus.setText("● Connected");
            lblConnStatus.setForeground(GREEN);
            for (JButton b : new JButton[]{btnAdd,btnEdit,btnDelete,btnToggle,btnRefresh})
                b.setEnabled(true);
            setStatus("Connected to " + host + ":" + port);
            refreshTable();
        } catch (Exception ex) {
            stub = null;
            lblConnStatus.setText("○ Disconnected");
            lblConnStatus.setForeground(RED);
            setStatus("Connection failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to RMI server at " + host + ":" + port + "\n" + ex.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Table operations ───────────────────────────────────────────────────

    private void refreshTable() {
        if (stub == null) return;
        try {
            List<UserDTO> users = stub.listUsers();
            tableModel.setRowCount(0);
            for (UserDTO u : users)
                tableModel.addRow(new Object[]{u.getUsername(), u.getEmail(),
                        u.isActive() ? "active" : "disabled"});
            setStatus(users.size() + " user(s) loaded.");
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Select a user first."); return; }
        String username = (String) tableModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete user \"" + username + "\"?  This cannot be undone.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            boolean ok = stub.deleteUser(username);
            setStatus(ok ? "Deleted: " + username : "User not found: " + username);
            refreshTable();
        } catch (Exception e) { setStatus("Error: " + e.getMessage()); }
    }

    private void toggleSelected() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Select a user first."); return; }
        String username = (String) tableModel.getValueAt(row, 0);
        boolean nowActive = "active".equals(tableModel.getValueAt(row, 2));
        try {
            stub.setActive(username, !nowActive);
            setStatus(username + " → " + (!nowActive ? "active" : "disabled"));
            refreshTable();
        } catch (Exception e) { setStatus("Error: " + e.getMessage()); }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    private void showAddDialog() {
        JTextField fUser  = new JTextField(16);
        JPasswordField fPass = new JPasswordField(16);
        JTextField fEmail = new JTextField(24);
        styleTextField(fUser); styleTextField(fEmail);
        fPass.setBackground(BG_DARK); fPass.setForeground(TEXT_PRI);
        fPass.setCaretColor(ACCENT); fPass.setFont(MONO);
        fPass.setBorder(BorderFactory.createLineBorder(BORDER));

        JPanel form = buildForm(new String[]{"Username:", "Password:", "Email:"},
                new JComponent[]{fUser, fPass, fEmail});

        int res = JOptionPane.showConfirmDialog(this, form, "Add User",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        String u = fUser.getText().trim();
        String p = new String(fPass.getPassword());
        String e = fEmail.getText().trim();

        if (u.isEmpty() || p.isEmpty()) { setStatus("Username and password are required."); return; }
        try {
            boolean ok = stub.createUser(u, p, e.isEmpty() ? null : e);
            setStatus(ok ? "Created user: " + u : "Username already exists: " + u);
            refreshTable();
        } catch (Exception ex) { setStatus("Error: " + ex.getMessage()); }
    }

    private void showEditDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Select a user first."); return; }
        String username  = (String) tableModel.getValueAt(row, 0);
        String currEmail = (String) tableModel.getValueAt(row, 1);

        JPasswordField fPass  = new JPasswordField(16);
        JTextField     fEmail = new JTextField(currEmail, 24);
        styleTextField(fEmail);
        fPass.setBackground(BG_DARK); fPass.setForeground(TEXT_PRI);
        fPass.setCaretColor(ACCENT); fPass.setFont(MONO);
        fPass.setBorder(BorderFactory.createLineBorder(BORDER));

        JLabel hint = new JLabel("Leave password blank to keep unchanged.");
        hint.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        hint.setForeground(TEXT_DIM);

        JPanel form = buildForm(
                new String[]{"New Password:", "Email:"},
                new JComponent[]{fPass, fEmail});
        form.add(hint);

        int res = JOptionPane.showConfirmDialog(this, form, "Edit: " + username,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        String np = new String(fPass.getPassword());
        String ne = fEmail.getText().trim();
        try {
            boolean ok = stub.updateUser(username,
                    np.isEmpty() ? null : np,
                    ne.isEmpty() ? null : ne);
            setStatus(ok ? "Updated: " + username : "User not found: " + username);
            refreshTable();
        } catch (Exception ex) { setStatus("Error: " + ex.getMessage()); }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private JPanel buildForm(String[] labels, JComponent[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(BG_CARD);
        p.setBorder(new EmptyBorder(12, 16, 12, 16));
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 10);
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 0, 4, 0);
        for (int i = 0; i < labels.length; i++) {
            lc.gridy = fc.gridy = i;
            lc.gridx = 0; fc.gridx = 1;
            JLabel lbl = new JLabel(labels[i]);
            lbl.setFont(UI);
            lbl.setForeground(TEXT_DIM);
            p.add(lbl, lc);
            p.add(fields[i], fc);
        }
        return p;
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText(msg));
    }

    private JButton makeButton(String text, Color fg, Color bg) {
        JButton b = new JButton(text);
        b.setFont(UI_BOLD);
        b.setForeground(fg);
        b.setBackground(BG_CARD);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker()),
                new EmptyBorder(5, 12, 5, 12)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { if (b.isEnabled()) b.setBackground(fg.darker().darker()); }
            public void mouseExited (MouseEvent e) { b.setBackground(BG_CARD); }
        });
        return b;
    }

    private JSeparator makeSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 20));
        s.setForeground(BORDER);
        return s;
    }

    private void styleTextField(JTextField tf) {
        tf.setBackground(BG_DARK);
        tf.setForeground(TEXT_PRI);
        tf.setCaretColor(ACCENT);
        tf.setFont(MONO);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(3, 6, 3, 6)));
    }

    private void styleSpinner(JSpinner sp) {
        JComponent editor = sp.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
            tf.setBackground(BG_DARK); tf.setForeground(TEXT_PRI);
            tf.setCaretColor(ACCENT); tf.setFont(MONO);
            tf.setBorder(BorderFactory.createLineBorder(BORDER));
        }
    }

    private void styleScrollBar(JScrollBar sb) {
        sb.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            protected void configureScrollBarColors() {
                this.thumbColor = BORDER; this.trackColor = BG_PANEL;
            }
            protected JButton createDecreaseButton(int o) { return zero(); }
            protected JButton createIncreaseButton(int o) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
        });
    }

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new AuthAdminGui().setVisible(true));
    }
}
package dev.tokenwidget;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

public final class UsageWidget extends JFrame {
    /** 위젯에 표시할 하나의 에이전트 소스 정의. */
    public record ProviderSource(String name, Color accent, Supplier<ProviderUsage> supplier) {
    }

    private static final String UI_FONT = Font.DIALOG;
    private static final Color BACKGROUND = new Color(26, 30, 41);
    private static final Color CARD = new Color(40, 46, 61);
    private static final Color BORDER = new Color(84, 96, 118);
    private static final Color TRACK = new Color(58, 66, 84);
    private static final Color TEXT = new Color(238, 241, 247);
    private static final Color MUTED = new Color(148, 157, 176);
    /** 사용률 상태색: 여유(초록) → 주의(주황) → 위험(빨강). */
    private static final Color STATUS_OK = new Color(74, 222, 128);
    private static final Color STATUS_WARN = new Color(251, 191, 36);
    private static final Color STATUS_DANGER = new Color(248, 113, 113);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("M/d HH:mm")
            .withZone(ZoneId.systemDefault());

    private static Color statusColor(double percent) {
        if (percent >= 80) return STATUS_DANGER;
        if (percent >= 50) return STATUS_WARN;
        return STATUS_OK;
    }

    private final List<ProviderSource> sources;
    private final Map<String, UsageCard> cards = new LinkedHashMap<>();
    private final Preferences preferences = Preferences.userNodeForPackage(UsageWidget.class);
    private final JPanel cardsPanel = new JPanel();
    private final JLabel emptyLabel = new JLabel("조회 가능한 사용량이 아직 없습니다", SwingConstants.CENTER);
    private final Map<String, MenuItem> trayUsageItems = new LinkedHashMap<>();
    private PopupMenu trayMenu;
    private MenuItem trayToggleItem;
    private MenuItem trayShowItem;
    private MenuItem trayExitItem;
    private JButton compactToggle;
    private boolean compact;
    private List<String> lastVisible = List.of();
    private Point dragOffset;
    private GraphicsConfiguration activeDisplay;
    private double activeScaleX = -1;
    private double activeScaleY = -1;

    public UsageWidget(List<ProviderSource> sources) {
        super("AI Account Usage Widget");
        this.sources = List.copyOf(sources);
        this.compact = preferences.getBoolean("view.compact", false);
        for (ProviderSource source : this.sources) {
            cards.put(source.name(), new UsageCard(source.name(), source.accent()));
        }
        configureWindow();
        installWindowIcons();
        buildUi();
        restoreWindowLocation();
        installDisplayTracking();
        installTray();
        new Timer(2_000, event -> {
            refresh();
            updateDisplayMetrics();
            ensureVisibleOnCurrentDisplay();
        }).start();
        refresh();
    }

    private void configureWindow() {
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        // Opaque windows repaint reliably when crossing monitors with different DPI.
        setBackground(BACKGROUND);
        installHoverOpacity();
    }

    /**
     * 평소에는 살짝 투명하게 떠 있다가 마우스가 올라오면 선명해진다.
     * 자식 컴포넌트의 enter/exit 이벤트에 흔들리지 않도록 포인터 위치를 주기적으로 확인한다.
     */
    private void installHoverOpacity() {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        if (!device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
            return;
        }
        float restingOpacity = 0.93f;
        setOpacity(restingOpacity);
        new Timer(200, event -> {
            java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
            if (pointer == null) return;
            boolean hovering = isShowing() && getBounds().contains(pointer.getLocation());
            float target = hovering ? 1.0f : restingOpacity;
            if (Math.abs(getOpacity() - target) > 0.01f) {
                setOpacity(target);
            }
        }).start();
    }

    private void buildUi() {
        JPanel root = new RoundedPanel(BACKGROUND, 24);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(12, 14, 14, 14)));

        JPanel titleBar = new JPanel();
        titleBar.setOpaque(false);
        titleBar.setLayout(new BoxLayout(titleBar, BoxLayout.X_AXIS));
        titleBar.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel("AI USAGE");
        title.setForeground(MUTED);
        title.setFont(new Font(UI_FONT, Font.BOLD, 12)
                .deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f)));
        compactToggle = textButton(compact ? "펼치기" : "간략히", this::toggleCompact);
        JButton hideButton = textButton("—", () -> setVisible(false));
        JButton closeButton = textButton("×", () -> System.exit(0));
        titleBar.add(title);
        titleBar.add(Box.createHorizontalGlue());
        titleBar.add(compactToggle);
        titleBar.add(Box.createHorizontalStrut(2));
        titleBar.add(hideButton);
        titleBar.add(closeButton);
        installDragging(titleBar);
        installDragging(title);

        cardsPanel.setOpaque(false);
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        cardsPanel.setAlignmentX(LEFT_ALIGNMENT);

        emptyLabel.setForeground(MUTED);
        emptyLabel.setFont(new Font(UI_FONT, Font.PLAIN, 12));
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(18, 10, 14, 10));
        emptyLabel.setAlignmentX(LEFT_ALIGNMENT);
        emptyLabel.setPreferredSize(new Dimension(358, 52));
        emptyLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        root.add(titleBar);
        root.add(Box.createVerticalStrut(10));
        root.add(cardsPanel);
        setContentPane(root);
        rebuildCards(List.of());
        pack();
    }

    private void toggleCompact() {
        compact = !compact;
        preferences.putBoolean("view.compact", compact);
        compactToggle.setText(compact ? "펼치기" : "간략히");
        if (trayToggleItem != null) {
            trayToggleItem.setLabel(compact ? "펼쳐 보기" : "간략히 보기");
        }
        for (UsageCard card : cards.values()) {
            card.setCompact(compact);
        }
        rebuildCards(lastVisible);
        pack();
        ensureVisibleOnCurrentDisplay();
    }

    private void refresh() {
        List<String> visible = new ArrayList<>();
        for (ProviderSource source : sources) {
            ProviderUsage usage = source.supplier().get();
            UsageCard card = cards.get(source.name());
            if (usage != null && usage.hasData()) {
                card.update(usage);
                visible.add(source.name());
                updateTrayUsageItem(source.name(), usage);
            }
        }
        if (!visible.equals(lastVisible)) {
            rebuildCards(visible);
            rebuildTrayMenu();
            pack();
            ensureVisibleOnCurrentDisplay();
        }
    }

    private void updateTrayUsageItem(String name, ProviderUsage usage) {
        MenuItem item = trayUsageItems.get(name);
        if (item == null) return;
        Double primary = usage.primaryPercent();
        item.setLabel(primary == null ? name
                : "%s  %d%%".formatted(name, Math.round(primary)));
    }

    private void rebuildCards(List<String> visible) {
        lastVisible = List.copyOf(visible);
        cardsPanel.removeAll();
        if (visible.isEmpty()) {
            cardsPanel.add(emptyLabel);
        } else {
            boolean first = true;
            for (String name : visible) {
                if (!first) {
                    cardsPanel.add(Box.createVerticalStrut(compact ? 6 : 10));
                }
                UsageCard card = cards.get(name);
                card.setCompact(compact);
                card.setAlignmentX(LEFT_ALIGNMENT);
                cardsPanel.add(card);
                first = false;
            }
        }
        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    private void restoreWindowLocation() {
        int x = preferences.getInt("window.x", Integer.MIN_VALUE);
        int y = preferences.getInt("window.y", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || !isPointOnAnyDisplay(x, y)) {
            setLocationRelativeTo(null);
        } else {
            setLocation(x, y);
        }
        ensureVisibleOnCurrentDisplay();
    }

    private void installDisplayTracking() {
        updateDisplayMetrics();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent event) {
                updateDisplayMetrics();
            }

            @Override
            public void componentShown(ComponentEvent event) {
                repackForDisplay();
            }
        });
    }

    private void updateDisplayMetrics() {
        GraphicsConfiguration current = getGraphicsConfiguration();
        if (current == null) return;
        double scaleX = current.getDefaultTransform().getScaleX();
        double scaleY = current.getDefaultTransform().getScaleY();
        if (current == activeDisplay
                && Math.abs(scaleX - activeScaleX) < 0.001
                && Math.abs(scaleY - activeScaleY) < 0.001) {
            return;
        }
        activeDisplay = current;
        activeScaleX = scaleX;
        activeScaleY = scaleY;
        javax.swing.SwingUtilities.invokeLater(this::repackForDisplay);
    }

    private void repackForDisplay() {
        invalidate();
        pack();
        ensureVisibleOnCurrentDisplay();
        revalidate();
        repaint();
    }

    private void ensureVisibleOnCurrentDisplay() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) return;
        Rectangle usable = usableBounds(configuration);
        int x = Math.max(usable.x, Math.min(getX(), usable.x + usable.width - getWidth()));
        int y = Math.max(usable.y, Math.min(getY(), usable.y + usable.height - getHeight()));
        if (x != getX() || y != getY()) setLocation(x, y);
        preferences.putInt("window.x", getX());
        preferences.putInt("window.y", getY());
    }

    private boolean isPointOnAnyDisplay(int x, int y) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (usableBounds(device.getDefaultConfiguration()).contains(x, y)) return true;
        }
        return false;
    }

    private Rectangle usableBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private JButton textButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setForeground(MUTED);
        button.setBackground(BACKGROUND);
        button.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(new Font(UI_FONT, Font.BOLD, text.length() > 2 ? 10 : 16));
        button.addActionListener(event -> action.run());
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                button.setForeground(TEXT);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                button.setForeground(MUTED);
            }
        });
        return button;
    }

    private void installDragging(java.awt.Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragOffset = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Point screen = event.getLocationOnScreen();
                setLocation(screen.x - dragOffset.x, screen.y - dragOffset.y);
                preferences.putInt("window.x", getX());
                preferences.putInt("window.y", getY());
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    /** 작업 표시줄·알트탭 등에 표시될 애플리케이션 아이콘을 assets 리소스에서 로드한다. */
    private void installWindowIcons() {
        List<Image> icons = new ArrayList<>();
        for (String name : new String[] {
                "/assets/tray-favicon-pink-16.png",
                "/assets/tray-favicon-pink-32.png",
                "/assets/tray-favicon-pink-48.png",
                "/assets/app-icon-pink-128.png",
                "/assets/app-icon-pink-256.png"}) {
            Image image = loadImageResource(name);
            if (image != null) icons.add(image);
        }
        if (!icons.isEmpty()) setIconImages(icons);
    }

    private static Image loadImageResource(String path) {
        java.net.URL url = UsageWidget.class.getResource(path);
        if (url == null) return null;
        try {
            return javax.imageio.ImageIO.read(url);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        trayMenu = new PopupMenu();
        for (ProviderSource source : sources) {
            MenuItem item = new MenuItem(source.name());
            item.setEnabled(false);
            trayUsageItems.put(source.name(), item);
        }
        trayToggleItem = new MenuItem(compact ? "펼쳐 보기" : "간략히 보기");
        trayToggleItem.addActionListener(event -> toggleCompact());
        trayShowItem = new MenuItem("위젯 보이기/숨기기");
        trayShowItem.addActionListener(event -> setVisible(!isVisible()));
        trayExitItem = new MenuItem("종료");
        trayExitItem.addActionListener(event -> System.exit(0));
        rebuildTrayMenu();

        TrayIcon icon = new TrayIcon(trayImage(), "AI Account Usage", trayMenu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> setVisible(!isVisible()));
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException ignored) {
            // The widget remains usable without a tray icon.
            trayMenu = null;
        }
    }

    /**
     * 트레이(맥 메뉴 막대) 메뉴 구성: 조회되는 에이전트의 사용량 요약(텍스트) +
     * 간략히/펼치기 전환 + 위젯 보이기 + 종료. 그래프는 위젯 창에서 확인한다.
     */
    private void rebuildTrayMenu() {
        if (trayMenu == null) return;
        trayMenu.removeAll();
        if (lastVisible.isEmpty()) {
            MenuItem none = new MenuItem("조회 가능한 사용량 없음");
            none.setEnabled(false);
            trayMenu.add(none);
        } else {
            for (String name : lastVisible) {
                MenuItem item = trayUsageItems.get(name);
                if (item != null) trayMenu.add(item);
            }
        }
        trayMenu.addSeparator();
        trayMenu.add(trayToggleItem);
        trayMenu.add(trayShowItem);
        trayMenu.addSeparator();
        trayMenu.add(trayExitItem);
    }

    /** 트레이 아이콘: assets 리소스를 우선 사용하고, 없으면 기존 그리기 방식으로 대체한다. */
    private Image trayImage() {
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        int target = Math.max(16, Math.min(traySize.width, traySize.height));
        String resource = target <= 16 ? "/assets/tray-favicon-pink-16.png"
                : target <= 32 ? "/assets/tray-favicon-pink-32.png"
                : target <= 48 ? "/assets/tray-favicon-pink-48.png"
                : "/assets/tray-favicon-pink-64.png";
        Image image = loadImageResource(resource);
        return image != null ? image : createTrayImage();
    }

    private Image createTrayImage() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(32, 32,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(new java.awt.GradientPaint(0, 0, new Color(96, 165, 250),
                32, 32, new Color(86, 202, 162)));
        graphics.fillRoundRect(2, 2, 28, 28, 10, 10);
        graphics.setColor(new Color(20, 24, 33));
        graphics.setFont(new Font(UI_FONT, Font.BOLD, 14));
        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        String label = "AI";
        int x = (32 - metrics.stringWidth(label)) / 2;
        int y = (32 + metrics.getAscent() - metrics.getDescent()) / 2;
        graphics.drawString(label, x, y);
        graphics.dispose();
        return image;
    }

    private static final class UsageCard extends RoundedPanel {
        private final JLabel provider = new JLabel();
        private final JLabel detail = new JLabel(" ", SwingConstants.RIGHT);
        private final JLabel compactPercent = new JLabel(" ", SwingConstants.RIGHT);
        private final JPanel top;
        private final JPanel rowsPanel = new JPanel();
        private final List<LimitRow> rows = new ArrayList<>();
        private final List<String> rowLabels = new ArrayList<>();
        private final Color accent;
        private boolean compact;

        UsageCard(String name, Color accent) {
            super(CARD, 18);
            this.accent = accent;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(LEFT_ALIGNMENT);

            top = row();
            provider.setText(name);
            provider.setForeground(accent);
            detail.setForeground(MUTED);
            detail.setFont(new Font(UI_FONT, Font.PLAIN, 11));
            compactPercent.setForeground(TEXT);
            compactPercent.setFont(new Font(UI_FONT, Font.BOLD, 15));

            rowsPanel.setOpaque(false);
            rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
            rowsPanel.setAlignmentX(LEFT_ALIGNMENT);

            applyMode();
        }

        void setCompact(boolean compact) {
            if (this.compact == compact) return;
            this.compact = compact;
            applyMode();
        }

        /**
         * BoxLayout은 숨겨진 컴포넌트도 공간을 차지하므로,
         * 모드 전환 시 컴포넌트를 실제로 추가/제거해서 레이아웃을 다시 구성한다.
         */
        private void applyMode() {
            provider.setFont(new Font(UI_FONT, Font.BOLD, compact ? 14 : 17));
            top.removeAll();
            top.add(provider);
            top.add(Box.createHorizontalGlue());
            top.add(compact ? compactPercent : detail);

            removeAll();
            add(top);
            if (!compact) {
                add(rowsPanel);
            }
            setBorder(BorderFactory.createEmptyBorder(
                    compact ? 8 : 13, 20, compact ? 8 : 12, 14));
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            // 카드 왼쪽에 에이전트 고유색 스트라이프를 그려 정체성을 유지한다.
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(accent);
            int inset = compact ? 9 : 12;
            g2.fillRoundRect(8, inset, 4, getHeight() - inset * 2, 4, 4);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            return new Dimension(Math.max(358, preferred.width), preferred.height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        void update(ProviderUsage usage) {
            detail.setForeground(MUTED);
            detail.setText(trim(usage.detail(), 46));
            Double primary = usage.primaryPercent();
            compactPercent.setText(primary == null ? "—" : "%d%%".formatted(Math.round(primary)));
            compactPercent.setForeground(primary == null || primary < 50
                    ? TEXT : statusColor(primary));

            List<String> labels = usage.limits().stream().map(UsageLimit::label).toList();
            if (!labels.equals(rowLabels)) {
                rebuildRows(labels);
            }
            for (int i = 0; i < rows.size(); i++) {
                UsageLimit limit = usage.limits().get(i);
                rows.get(i).update(limit.percent(), limit.resetAt());
            }
        }

        private void rebuildRows(List<String> labels) {
            rowLabels.clear();
            rowLabels.addAll(labels);
            rows.clear();
            rowsPanel.removeAll();
            rowsPanel.add(Box.createVerticalStrut(8));
            boolean first = true;
            for (String label : labels) {
                if (!first) {
                    rowsPanel.add(Box.createVerticalStrut(7));
                }
                LimitRow row = new LimitRow(label, accent);
                rows.add(row);
                rowsPanel.add(row);
                first = false;
            }
            rowsPanel.revalidate();
            revalidate();
        }

        private JPanel row() {
            JPanel panel = new JPanel();
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
            panel.setAlignmentX(LEFT_ALIGNMENT);
            return panel;
        }

        private String trim(String value, int max) {
            if (value == null || value.isBlank()) return "연결 대기 중";
            String normalized = value.replaceAll("\\s+", " ").trim();
            return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
        }
    }

    private static final class LimitRow extends JPanel {
        private final JLabel label = new JLabel();
        private final JLabel percentage = new JLabel("—", SwingConstants.RIGHT);
        private final JLabel reset = new JLabel(" ", SwingConstants.RIGHT);
        private final JProgressBar bar;

        LimitRow(String name, Color accent) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(LEFT_ALIGNMENT);
            bar = new SlimProgressBar(accent);

            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new BoxLayout(labels, BoxLayout.X_AXIS));
            labels.setAlignmentX(LEFT_ALIGNMENT);
            label.setText(name);
            label.setForeground(TEXT);
            label.setFont(new Font(UI_FONT, Font.BOLD, 12));
            percentage.setForeground(TEXT);
            percentage.setFont(new Font(UI_FONT, Font.BOLD, 12));
            reset.setForeground(MUTED);
            reset.setFont(new Font(UI_FONT, Font.PLAIN, 10));
            labels.add(label);
            labels.add(Box.createHorizontalStrut(8));
            labels.add(reset);
            labels.add(Box.createHorizontalGlue());
            labels.add(percentage);

            bar.setPreferredSize(new Dimension(330, 8));
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
            bar.setBorderPainted(false);
            add(labels);
            add(Box.createVerticalStrut(4));
            add(bar);
        }

        void update(Double percent, Instant resetAt) {
            if (percent == null) {
                percentage.setText("—");
                percentage.setForeground(TEXT);
                reset.setText("데이터 없음");
                bar.setValue(0);
                return;
            }
            percentage.setText("%.1f%%".formatted(percent));
            percentage.setForeground(percent < 50 ? TEXT : statusColor(percent));
            reset.setText(resetLabel(resetAt));
            bar.setValue((int) Math.round(Math.max(0, Math.min(100, percent)) * 10));
        }

        private String resetLabel(Instant resetAt) {
            if (resetAt != null) {
                Duration remaining = Duration.between(Instant.now(), resetAt);
                if (!remaining.isNegative()) {
                    long days = remaining.toDays();
                    long hours = remaining.minusDays(days).toHours();
                    if (days > 0) return "%d일 %d시간 후".formatted(days, hours);
                    long minutes = remaining.minusHours(remaining.toHours()).toMinutes();
                    return "%d시간 %d분 후".formatted(remaining.toHours(), minutes);
                }
                return CLOCK.format(resetAt);
            }
            return " ";
        }
    }

    private static final class SlimProgressBar extends JProgressBar {
        SlimProgressBar(Color accent) {
            super(0, 1000);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int height = Math.max(5, getHeight());
            int arc = height;
            g2.setColor(TRACK);
            g2.fillRoundRect(0, 0, getWidth(), height, arc, arc);
            int fill = (int) Math.round(getWidth() * getPercentComplete());
            if (fill > 0) {
                // 사용률 상태색으로 채우되, 왼쪽을 살짝 어둡게 한 그라데이션으로 입체감을 준다.
                Color base = statusColor(getPercentComplete() * 100.0);
                Color deep = base.darker();
                g2.setPaint(new java.awt.GradientPaint(0, 0, deep, Math.max(fill, 1), 0, base));
                g2.fillRoundRect(0, 0, fill, height, arc, arc);
            }
            g2.dispose();
        }
    }

    private static class RoundedPanel extends JPanel {
        private final Color fill;
        private final int radius;

        RoundedPanel(Color fill, int radius) {
            this.fill = fill;
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }
}

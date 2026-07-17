package dev.tokenwidget;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가상 디스플레이에서 위젯을 띄워 카드 표시/숨김과 간략히 모드를 검증하는 수동 스모크 테스트.
 * CI/컨테이너 검증용이며 build.ps1 기본 테스트에는 포함되지 않는다.
 */
public final class WidgetSmokeTest {
    public static void main(String[] args) throws Exception {
        // 사용률 색상 코딩 확인용: 초록(<50)·노랑(50~79)·빨강(80+)이 모두 나오도록 값 배치.
        AtomicReference<ProviderUsage> claude = new AtomicReference<>(
                new ProviderUsage("Claude",
                        List.of(new UsageLimit("5시간", 23.5, Instant.now().plusSeconds(3600)),
                                new UsageLimit("주간", 86.0, Instant.now().plusSeconds(86400))),
                        "5시간 23.5% · 주간 86%", Instant.now(), null));
        AtomicReference<ProviderUsage> codex = new AtomicReference<>(
                new ProviderUsage("Codex",
                        List.of(new UsageLimit("5시간", 12.5, Instant.now().plusSeconds(7200)),
                                new UsageLimit("주간", 32.5, Instant.now().plusSeconds(200000))),
                        "plus · 계정 한도", Instant.now(), null));
        AtomicReference<ProviderUsage> antigravity = new AtomicReference<>(
                ProviderUsage.waiting("Antigravity", "앱 실행 대기 중"));
        AtomicReference<ProviderUsage> cursor = new AtomicReference<>(
                ProviderUsage.waiting("Cursor", "확장 프로그램 연결 대기 중"));
        AtomicReference<ProviderUsage> copilot = new AtomicReference<>(
                ProviderUsage.waiting("Copilot", "로그인 정보 없음"));

        AtomicReference<UsageWidget> widgetRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            UsageWidget widget = new UsageWidget(List.of(
                    new UsageWidget.ProviderSource("Claude", new Color(213, 129, 93), claude::get),
                    new UsageWidget.ProviderSource("Codex", new Color(86, 202, 162), codex::get),
                    new UsageWidget.ProviderSource("Antigravity", new Color(66, 133, 244), antigravity::get),
                    new UsageWidget.ProviderSource("Cursor", new Color(180, 156, 255), cursor::get),
                    new UsageWidget.ProviderSource("Copilot", new Color(240, 200, 90), copilot::get)));
            widget.setVisible(true);
            widgetRef.set(widget);
        });

        Thread.sleep(1200);
        int twoCardHeight = capture(widgetRef.get(), "widget-2-agents.png");

        // Antigravity가 조회되기 시작하면 카드가 추가되어야 한다.
        antigravity.set(new ProviderUsage("Antigravity",
                List.of(new UsageLimit("주간", 28.0, Instant.now().plusSeconds(500000))),
                "Gemini Models · 주간 28.0%", Instant.now(), null));
        cursor.set(new ProviderUsage("Cursor",
                List.of(new UsageLimit("월간", 52.0, Instant.now().plusSeconds(1500000))),
                "pro · 월간 52%", Instant.now(), null));
        Thread.sleep(2600);
        int fourCardHeight = capture(widgetRef.get(), "widget-4-agents.png");
        if (fourCardHeight <= twoCardHeight) {
            throw new AssertionError("카드 추가 후 위젯이 커져야 함: " + twoCardHeight + " -> " + fourCardHeight);
        }

        // 간략히 모드 토글.
        SwingUtilities.invokeAndWait(() -> click(widgetRef.get(), "간략히"));
        Thread.sleep(600);
        int compactHeight = capture(widgetRef.get(), "widget-compact.png");
        if (compactHeight >= fourCardHeight) {
            throw new AssertionError("간략히 모드는 더 작아야 함: " + fourCardHeight + " -> " + compactHeight);
        }

        // 다시 펼치기.
        SwingUtilities.invokeAndWait(() -> click(widgetRef.get(), "펼치기"));
        Thread.sleep(600);
        int expandedHeight = capture(widgetRef.get(), "widget-expanded.png");
        if (Math.abs(expandedHeight - fourCardHeight) > 4) {
            throw new AssertionError("펼치기 후 원래 높이로 복귀해야 함: " + fourCardHeight + " -> " + expandedHeight);
        }

        System.out.println("Widget smoke test passed. heights=" + twoCardHeight + "/"
                + fourCardHeight + "/" + compactHeight + "/" + expandedHeight);
        System.exit(0);
    }

    private static void click(java.awt.Container root, String text) {
        javax.swing.JButton button = findButton(root, text);
        if (button == null) throw new AssertionError("버튼을 찾지 못함: " + text);
        button.doClick();
    }

    private static javax.swing.JButton findButton(java.awt.Container container, String text) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof javax.swing.JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container child) {
                javax.swing.JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int capture(UsageWidget widget, String fileName) throws Exception {
        Robot robot = new Robot();
        Rectangle bounds = widget.getBounds();
        BufferedImage image = robot.createScreenCapture(bounds);
        ImageIO.write(image, "png",
                new File(System.getProperty("java.io.tmpdir"), fileName));
        System.out.println(fileName + " -> " + bounds.width + "x" + bounds.height);
        return bounds.height;
    }
}

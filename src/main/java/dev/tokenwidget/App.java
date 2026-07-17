package dev.tokenwidget;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class App {
    private static final Color CLAUDE = new Color(213, 129, 93);
    private static final Color CODEX = new Color(86, 202, 162);
    private static final Color ANTIGRAVITY = new Color(66, 133, 244);
    private static final Color CURSOR = new Color(180, 156, 255);
    private static final Color COPILOT = new Color(240, 200, 90);

    private App() {
    }

    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--smoke-test")) {
            Class.forName("java.net.http.HttpClient");
            Class.forName("com.sun.net.httpserver.HttpServer");
            return;
        }

        // 위젯은 한 번에 하나만 뜨도록 제한한다. 이미 실행 중이면 기존 창을 앞으로
        // 불러온 뒤 이 프로세스는 종료한다(중복 창·:32145 포트 충돌 방지).
        if (!acquireSingleInstanceLock()) {
            activateRunningInstance();
            return;
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        CodexUsageReader codexReader = new CodexUsageReader();
        AntigravityUsageReader antigravityReader = new AntigravityUsageReader();
        CopilotUsageReader copilotReader = new CopilotUsageReader();
        AtomicReference<ProviderUsage> codex = new AtomicReference<>(
                ProviderUsage.waiting("Codex", "세션 검색 중"));
        AtomicReference<ProviderUsage> antigravity = new AtomicReference<>(
                ProviderUsage.waiting("Antigravity", "앱 검색 중"));
        AtomicReference<ProviderUsage> copilot = new AtomicReference<>(
                ProviderUsage.waiting("Copilot", "로그인 정보 검색 중"));
        ScheduledExecutorService poller = Executors.newScheduledThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "local-usage-poller");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(() -> codex.set(codexReader.readLatest()), 0, 2, TimeUnit.SECONDS);
        poller.scheduleWithFixedDelay(() -> antigravity.set(antigravityReader.readLatest()),
                0, 15, TimeUnit.SECONDS);
        poller.scheduleWithFixedDelay(() -> copilot.set(copilotReader.readLatest()),
                0, 300, TimeUnit.SECONDS);

        BrowserUsageServer browserUsageServer = new BrowserUsageServer();
        try {
            browserUsageServer.start();
        } catch (Exception e) {
            System.err.println("Browser usage receiver could not start: " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            poller.shutdownNow();
            browserUsageServer.close();
            releaseSingleInstanceLock();
        }));

        List<UsageWidget.ProviderSource> sources = List.of(
                new UsageWidget.ProviderSource("Claude", CLAUDE, browserUsageServer::latestClaude),
                new UsageWidget.ProviderSource("Codex", CODEX, codex::get),
                new UsageWidget.ProviderSource("Antigravity", ANTIGRAVITY, antigravity::get),
                new UsageWidget.ProviderSource("Cursor", CURSOR, browserUsageServer::latestCursor),
                new UsageWidget.ProviderSource("Copilot", COPILOT, copilot::get));

        SwingUtilities.invokeLater(() -> {
            UsageWidget widget = new UsageWidget(sources);
            widget.setVisible(true);
            // 재실행 시 기존 창을 화면 앞으로 불러오는 동작을 연결한다.
            browserUsageServer.setActivateCallback(() -> SwingUtilities.invokeLater(() -> {
                widget.setVisible(true);
                widget.toFront();
                widget.requestFocus();
            }));
        });
    }

    private static java.nio.channels.FileChannel lockChannel;
    private static java.nio.channels.FileLock instanceLock;

    /**
     * 단일 인스턴스 보장을 위한 파일 잠금을 시도한다. 다른 프로세스가 이미 잠금을
     * 쥐고 있으면 false를 반환한다. 잠금은 JVM 종료 시 OS가 자동 해제하므로
     * 비정상 종료 후에도 잠금이 남지 않는다.
     */
    private static boolean acquireSingleInstanceLock() {
        try {
            java.nio.file.Path lockFile = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "ai-usage-widget.lock");
            lockChannel = java.nio.channels.FileChannel.open(lockFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            if (instanceLock == null) {
                lockChannel.close();
                lockChannel = null;
                return false;
            }
            return true;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (java.io.IOException e) {
            // 잠금 자체가 실패하면 단일 인스턴스 보장은 포기하되 실행은 계속한다.
            return true;
        }
    }

    /** 이미 실행 중인 인스턴스의 위젯 창을 앞으로 불러온다(응답이 없어도 조용히 종료). */
    private static void activateRunningInstance() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            "http://127.0.0.1:" + BrowserUsageServer.PORT + "/activate"))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .GET()
                    .build();
            client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // 기존 인스턴스가 응답하지 않아도 새 창을 만들지 않고 그대로 종료한다.
        }
    }

    private static void releaseSingleInstanceLock() {
        try {
            if (instanceLock != null) {
                instanceLock.release();
                instanceLock = null;
            }
            if (lockChannel != null) {
                lockChannel.close();
                lockChannel = null;
            }
        } catch (java.io.IOException ignored) {
            // 종료 중 잠금 해제 실패는 무시한다(OS가 정리한다).
        }
    }
}

package dev.tokenwidget;

import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Font;

public final class UsageParserTest {
    public static void main(String[] args) throws Exception {
        testCodexPayload();
        testCodexSelectsRealEvent();
        testCodexExpiredBucketIsHidden();
        testCodexAllBucketsExpired();
        testCodexStaleSnapshotShowsObservedAge();
        testCodexFallsBackToPreviousSessionFile();
        testKoreanFontFallback();
        testClaudePayload();
        testAntigravityPayload();
        testCursorPayload();
        testCopilotPayload();
        testCopilotUnlimitedPlan();
        testProviderVisibilityRules();
        testBrowserHttpServer();
        testCachedUsageSurvivesRestart();
        System.out.println("All parser tests passed.");
    }

    /** 테스트 기준 시각. resets_at이 이 시각보다 미래인지/과거인지로 만료를 판정한다. */
    private static final Instant CODEX_NOW = Instant.parse("2026-07-15T00:00:00Z");

    private static String codexTokenLine(long primaryResetEpoch, long secondaryResetEpoch) {
        return ("{\"timestamp\":\"2026-07-14T00:00:00Z\",\"type\":\"event_msg\",\"payload\":"
                + "{\"type\":\"token_count\",\"info\":{\"model_context_window\":200000},"
                + "\"rate_limits\":{\"primary\":{\"used_percent\":12.5,\"window_minutes\":300,"
                + "\"resets_at\":%d},\"secondary\":{\"used_percent\":32.5,"
                + "\"window_minutes\":10080,\"resets_at\":%d},\"plan_type\":\"plus\"}}}")
                .formatted(primaryResetEpoch, secondaryResetEpoch);
    }

    private static void testCodexPayload() {
        long primaryReset = CODEX_NOW.plusSeconds(3600).getEpochSecond();
        long secondaryReset = CODEX_NOW.plusSeconds(4 * 24 * 3600).getEpochSecond();
        String json = codexTokenLine(primaryReset, secondaryReset);
        ProviderUsage usage = CodexUsageReader.parse(json, CODEX_NOW, CODEX_NOW);
        UsageLimit fiveHour = usage.limit("5시간");
        UsageLimit weekly = usage.limit("주간");
        assert Math.abs(fiveHour.percent() - 12.5) < 0.001;
        assert fiveHour.resetAt().equals(Instant.ofEpochSecond(primaryReset));
        assert Math.abs(weekly.percent() - 32.5) < 0.001;
        assert weekly.resetAt().equals(Instant.ofEpochSecond(secondaryReset));
        assert usage.detail().contains("plus");
        assert !usage.detail().contains("기록") : "방금 관찰한 값에는 시점 표기가 없어야 함";
        assert usage.hasData();
        assert Math.abs(usage.primaryPercent() - 32.5) < 0.001 : "대표값은 더 높은 한도";
    }

    private static void testCodexSelectsRealEvent() throws Exception {
        Path sessions = Files.createTempDirectory("codex-widget-test");
        Path day = Files.createDirectories(sessions.resolve("2026/07/14"));
        Path log = day.resolve("rollout-test.jsonl");
        long weeklyReset = Instant.now().plusSeconds(3 * 24 * 3600).getEpochSecond();
        String actual = ("{\"timestamp\":\"2026-07-14T00:00:00Z\",\"type\":\"event_msg\","
                + "\"payload\":{\"type\":\"token_count\",\"info\":{},\"rate_limits\":"
                + "{\"primary\":{\"used_percent\":32.5,\"window_minutes\":10080,"
                + "\"resets_at\":%d},\"plan_type\":\"plus\"}}}").formatted(weeklyReset);
        String misleadingLaterLine = """
                {"timestamp":"2026-07-14T00:00:01Z","type":"response_item","payload":{"input":"escaped \\\"type\\\":\\\"event_msg\\\",\\\"payload\\\":{\\\"type\\\":\\\"token_count\\\""}}
                """.trim();
        Files.writeString(log, actual + System.lineSeparator() + misleadingLaterLine, StandardCharsets.UTF_8);

        CodexUsageReader reader = new CodexUsageReader(sessions);
        ProviderUsage usage = reader.readLatest();
        assert usage.error() == null : usage.error();
        assert Math.abs(usage.limit("주간").percent() - 32.5) < 0.001;
        assert usage.limit("5시간") == null : "제공되지 않는 한도 구간은 행 자체가 없어야 함";
        assert usage.limits().size() == 1;
        assert usage.updatedAt().equals(Instant.parse("2026-07-14T00:00:00Z"))
                : "관찰 시각은 파일 mtime이 아니라 이벤트 timestamp여야 함";
    }

    /** 초기화 시각이 이미 지난 한도 구간(예: 며칠 전 5시간 한도)은 표시하지 않는다. */
    private static void testCodexExpiredBucketIsHidden() {
        long expiredPrimary = CODEX_NOW.minusSeconds(3 * 24 * 3600).getEpochSecond();
        long validSecondary = CODEX_NOW.plusSeconds(3 * 24 * 3600).getEpochSecond();
        String json = codexTokenLine(expiredPrimary, validSecondary);
        ProviderUsage usage = CodexUsageReader.parse(json,
                CODEX_NOW.minusSeconds(4 * 24 * 3600), CODEX_NOW);
        assert usage.limit("5시간") == null : "만료된 5시간 한도는 숨겨야 함";
        assert usage.limit("주간") != null;
        assert Math.abs(usage.limit("주간").percent() - 32.5) < 0.001;
        assert usage.hasData();
    }

    /** 모든 구간이 만료된 스냅샷은 오래된 값을 보여주는 대신 카드를 숨긴다. */
    private static void testCodexAllBucketsExpired() {
        long expired = CODEX_NOW.minusSeconds(24 * 3600).getEpochSecond();
        String json = codexTokenLine(expired, expired);
        ProviderUsage usage = CodexUsageReader.parse(json,
                CODEX_NOW.minusSeconds(9 * 24 * 3600), CODEX_NOW);
        assert !usage.hasData() : "전부 만료된 스냅샷은 표시 대상이 아님";
        assert usage.error() == null;
    }

    /** 오래된 스냅샷에는 관찰 시점을 함께 표시해 다른 기기 사용량 미반영을 알린다. */
    private static void testCodexStaleSnapshotShowsObservedAge() {
        long validSecondary = CODEX_NOW.plusSeconds(3 * 24 * 3600).getEpochSecond();
        String json = codexTokenLine(CODEX_NOW.plusSeconds(3600).getEpochSecond(), validSecondary);
        ProviderUsage stale = CodexUsageReader.parse(json,
                CODEX_NOW.minusSeconds(4 * 24 * 3600), CODEX_NOW);
        assert stale.detail().contains("4일 전 기록") : stale.detail();
        ProviderUsage fresh = CodexUsageReader.parse(json,
                CODEX_NOW.minusSeconds(120), CODEX_NOW);
        assert !fresh.detail().contains("기록") : fresh.detail();
        assert "3시간 전".equals(CodexUsageReader.observedAgeLabel(
                CODEX_NOW.minusSeconds(3 * 3600 + 60), CODEX_NOW));
        assert "15분 전".equals(CodexUsageReader.observedAgeLabel(
                CODEX_NOW.minusSeconds(15 * 60), CODEX_NOW));
    }

    /** 가장 최근 파일에 token_count가 없으면 이전 세션 파일에서 스냅샷을 찾는다. */
    private static void testCodexFallsBackToPreviousSessionFile() throws Exception {
        Path sessions = Files.createTempDirectory("codex-widget-fallback");
        Path day = Files.createDirectories(sessions.resolve("2026/07/15"));
        long weeklyReset = Instant.now().plusSeconds(3 * 24 * 3600).getEpochSecond();
        Path older = day.resolve("rollout-older.jsonl");
        Files.writeString(older, ("{\"timestamp\":\"2026-07-15T00:00:00Z\",\"type\":\"event_msg\","
                + "\"payload\":{\"type\":\"token_count\",\"info\":{},\"rate_limits\":"
                + "{\"secondary\":{\"used_percent\":41.0,\"window_minutes\":10080,"
                + "\"resets_at\":%d},\"plan_type\":\"plus\"}}}").formatted(weeklyReset),
                StandardCharsets.UTF_8);
        Path newest = day.resolve("rollout-newest.jsonl");
        Files.writeString(newest,
                "{\"timestamp\":\"2026-07-15T01:00:00Z\",\"type\":\"session_meta\",\"payload\":{}}",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 60_000));
        Files.setLastModifiedTime(newest, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis()));

        ProviderUsage usage = new CodexUsageReader(sessions).readLatest();
        assert usage.error() == null : usage.error();
        assert usage.limit("주간") != null : "이전 파일의 스냅샷으로 폴백해야 함";
        assert Math.abs(usage.limit("주간").percent() - 41.0) < 0.001;
    }

    private static void testKoreanFontFallback() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
        assert font.canDisplayUpTo("연결 대기 중 · 오류 · 초기화") == -1
                : "Java Dialog font cannot display Korean on this machine";
    }

    private static void testClaudePayload() {
        String json = """
                {"sessionPercent":23.5,"sessionResetAt":"2026-07-14T05:00:00Z","weeklyPercent":41,"weeklyResetAt":"2026-07-20T05:00:00Z","sourceUrl":"claude-internal-api"}
                """;
        ProviderUsage usage = BrowserUsageServer.parse("Claude", json);
        assert Math.abs(usage.limit("5시간").percent() - 23.5) < 0.001;
        assert usage.limit("5시간").resetAt().equals(Instant.parse("2026-07-14T05:00:00Z"));
        assert Math.abs(usage.limit("주간").percent() - 41.0) < 0.001;
        assert usage.limit("주간").resetAt().equals(Instant.parse("2026-07-20T05:00:00Z"));
        assert usage.detail().contains("주간 41%");
    }

    private static void testAntigravityPayload() {
        String json = """
                {"response":{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-weekly","displayName":"Weekly Limit","window":"weekly","remainingFraction":0.72,"resetTime":"2026-07-21T06:02:17Z"}]},{"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-weekly","remainingFraction":0.11,"resetTime":"2026-07-21T06:02:17Z"}]}]}}
                """;
        ProviderUsage usage = AntigravityUsageReader.parse(json);
        assert usage.provider().equals("Antigravity");
        assert usage.limit("5시간") == null : "Antigravity는 주간 한도만 노출";
        assert Math.abs(usage.limit("주간").percent() - 28.0) < 0.001;
        assert usage.limit("주간").resetAt().equals(Instant.parse("2026-07-21T06:02:17Z"));
    }

    private static void testCursorPayload() {
        String json = """
                {"monthlyPercent":34.2,"monthlyResetAt":"2026-08-01T00:00:00Z","plan":"pro","observedAt":"2026-07-14T02:00:00Z"}
                """;
        ProviderUsage usage = BrowserUsageServer.parse("Cursor", json);
        assert usage.provider().equals("Cursor");
        assert Math.abs(usage.limit("월간").percent() - 34.2) < 0.001;
        assert usage.limit("월간").resetAt().equals(Instant.parse("2026-08-01T00:00:00Z"));
        assert usage.detail().contains("pro");
        assert usage.detail().contains("월간 34.2%");
        assert usage.hasData();
    }

    private static void testCopilotPayload() {
        String json = """
                {"access_type_sku":"copilot_pro","copilot_plan":"individual_pro","quota_reset_date":"2026-08-01","quota_snapshots":{"chat":{"unlimited":true},"completions":{"unlimited":true},"premium_interactions":{"entitlement":1500,"remaining":1327.5,"percent_remaining":88.5,"unlimited":false,"overage_permitted":false}}}
                """;
        ProviderUsage usage = CopilotUsageReader.parse(json);
        assert usage.provider().equals("Copilot");
        assert Math.abs(usage.limit("월간").percent() - 11.5) < 0.001 : "사용률 = 100 - 잔여율";
        assert usage.limit("월간").resetAt().equals(Instant.parse("2026-08-01T00:00:00Z"));
        assert usage.detail().contains("individual_pro");
        assert usage.hasData();
    }

    private static void testCopilotUnlimitedPlan() {
        String json = """
                {"copilot_plan":"business","quota_snapshots":{"premium_interactions":{"unlimited":true}}}
                """;
        ProviderUsage usage = CopilotUsageReader.parse(json);
        assert !usage.hasData() : "무제한 요금제는 표시 대상이 아님";
        assert usage.error() == null;
    }

    private static void testProviderVisibilityRules() {
        assert !ProviderUsage.waiting("Antigravity", "앱 실행 대기 중").hasData()
                : "대기 상태는 위젯에서 숨겨져야 함";
        assert !ProviderUsage.error("Copilot", "API HTTP 500").hasData()
                : "오류 상태는 위젯에서 숨겨져야 함";
        ProviderUsage partial = new ProviderUsage("Codex",
                java.util.List.of(new UsageLimit("5시간", null, null),
                        new UsageLimit("주간", 12.0, null)),
                "plus", Instant.now(), null);
        assert partial.hasData() : "한 구간만 조회되어도 표시";
        assert Math.abs(partial.primaryPercent() - 12.0) < 0.001;
    }

    private static void testBrowserHttpServer() throws Exception {
        try (BrowserUsageServer server = new BrowserUsageServer(0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                    .GET()
                    .build();
            HttpResponse<String> health = client.send(healthRequest, HttpResponse.BodyHandlers.ofString());
            assert health.statusCode() == 200;
            assert health.body().contains("\"status\":\"up\"");
            assert health.body().contains("\"startedAt\":\"") : "재시작 감지용 startedAt 누락";

            String body = """
                    {"sessionPercent":18,"sessionResetAt":"2026-07-14T05:00:00Z","weeklyPercent":33,"weeklyResetAt":"2026-07-20T05:00:00Z","sourceUrl":"claude-internal-api"}
                    """;
            HttpRequest usageRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/claude-usage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(usageRequest, HttpResponse.BodyHandlers.ofString());
            assert response.statusCode() == 200;
            assert Math.abs(server.latestClaude().limit("5시간").percent() - 18.0) < 0.001;
            assert Math.abs(server.latestClaude().limit("주간").percent() - 33.0) < 0.001;

            String cursorBody = """
                    {"monthlyPercent":52,"monthlyResetAt":"2026-08-01T00:00:00Z","plan":"pro"}
                    """;
            HttpRequest cursorRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/cursor-usage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cursorBody))
                    .build();
            HttpResponse<String> cursorResponse = client.send(cursorRequest, HttpResponse.BodyHandlers.ofString());
            assert cursorResponse.statusCode() == 200;
            assert Math.abs(server.latestCursor().limit("월간").percent() - 52.0) < 0.001;
            assert server.latestCursor().hasData();
        }
    }

    /** 위젯 재시작(서버 재생성) 후에도 마지막 수신 사용량이 즉시 복원되어야 한다. */
    private static void testCachedUsageSurvivesRestart() throws Exception {
        try (BrowserUsageServer server = new BrowserUsageServer(0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String body = """
                    {"monthlyPercent":77,"monthlyResetAt":"2026-08-01T00:00:00Z","plan":"pro"}
                    """;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/cursor-usage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            assert client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        }

        BrowserUsageServer restarted = new BrowserUsageServer(0);
        ProviderUsage cached = restarted.latestCursor();
        assert cached.hasData() : "재시작 직후 캐시가 복원되어야 함";
        assert Math.abs(cached.limit("월간").percent() - 77.0) < 0.001;
    }
}

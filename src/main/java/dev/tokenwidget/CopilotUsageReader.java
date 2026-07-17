package dev.tokenwidget;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Copilot 프리미엄 요청 잔여량을 읽는다.
 *
 * Copilot CLI/JetBrains/Vim 계열이 저장하는 로컬 OAuth 토큰
 * (%LOCALAPPDATA%\github-copilot\apps.json 또는 hosts.json)을 찾아
 * VS Code가 쓰는 것과 같은 내부 API(copilot_internal/user)를 호출한다.
 * 토큰 파일이 없으면 조회 불가로 처리되어 위젯에서 숨겨진다.
 */
public final class CopilotUsageReader {
    private static final Pattern OAUTH_TOKEN = Pattern.compile(
            "\\\"oauth_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern PREMIUM_BLOCK = Pattern.compile(
            "\\\"premium_interactions\\\"\\s*:\\s*(\\{[^{}]*})");
    private static final Pattern PERCENT_REMAINING = Pattern.compile(
            "\\\"percent_remaining\\\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern UNLIMITED = Pattern.compile(
            "\\\"unlimited\\\"\\s*:\\s*(true|false)");
    private static final Pattern QUOTA_RESET_DATE = Pattern.compile(
            "\\\"quota_reset_date(?:_utc)?\\\"\\s*:\\s*\\\"(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern COPILOT_PLAN = Pattern.compile(
            "\\\"copilot_plan\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final String API_URL = "https://api.github.com/copilot_internal/user";

    private final Path configDirectory;
    private final HttpClient client;

    public CopilotUsageReader() {
        this(defaultConfigDirectory(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build());
    }

    CopilotUsageReader(Path configDirectory, HttpClient client) {
        this.configDirectory = configDirectory;
        this.client = client;
    }

    public ProviderUsage readLatest() {
        String token = findToken();
        if (token == null) {
            return ProviderUsage.waiting("Copilot", "로컬 Copilot 로그인 정보 없음");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", "GitHubCopilotChat/0.26")
                    .header("Editor-Version", "vscode/1.96.0")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ProviderUsage.waiting("Copilot", "Copilot 토큰 만료 · 재로그인 필요");
            }
            if (response.statusCode() != 200) {
                return ProviderUsage.error("Copilot", "API HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderUsage.error("Copilot", "조회 중단");
        } catch (IOException | IllegalArgumentException e) {
            return ProviderUsage.error("Copilot", "한도 읽기 실패: " + concise(e.getMessage()));
        }
    }

    static ProviderUsage parse(String json) {
        Matcher block = PREMIUM_BLOCK.matcher(json);
        if (!block.find()) {
            return ProviderUsage.waiting("Copilot", "프리미엄 요청 한도 정보 없음");
        }
        String premium = block.group(1);
        Matcher unlimited = UNLIMITED.matcher(premium);
        if (unlimited.find() && "true".equals(unlimited.group(1))) {
            return ProviderUsage.waiting("Copilot", "프리미엄 요청 무제한 요금제");
        }
        Matcher remaining = PERCENT_REMAINING.matcher(premium);
        if (!remaining.find()) {
            return ProviderUsage.waiting("Copilot", "잔여율 정보 없음");
        }
        double usedPercent = Math.max(0, Math.min(100, 100.0 - Double.parseDouble(remaining.group(1))));
        Instant resetAt = parseResetDate(json);
        String plan = firstMatch(COPILOT_PLAN, json, "copilot");
        return new ProviderUsage("Copilot",
                java.util.List.of(new UsageLimit("월간", usedPercent, resetAt)),
                plan + " · 프리미엄 요청", Instant.now(), null);
    }

    private static Instant parseResetDate(String json) {
        Matcher matcher = QUOTA_RESET_DATE.matcher(json);
        if (!matcher.find()) return null;
        try {
            return LocalDate.parse(matcher.group(1)).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findToken() {
        Path[] directories = {
                configDirectory,
                Path.of(System.getProperty("user.home"), ".config", "github-copilot")
        };
        for (Path directory : directories) {
            for (String name : new String[] {"apps.json", "hosts.json"}) {
                Path file = directory.resolve(name);
                if (!Files.isRegularFile(file)) continue;
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher matcher = OAUTH_TOKEN.matcher(content);
                    if (matcher.find()) return matcher.group(1);
                } catch (IOException ignored) {
                    // 다음 후보 파일을 시도한다.
                }
            }
        }
        return null;
    }

    private static String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static String concise(String message) {
        if (message == null || message.isBlank()) return "알 수 없는 오류";
        return message.length() <= 45 ? message : message.substring(0, 44) + "…";
    }

    /** OS별 Copilot 토큰 저장 경로를 런타임에 감지한다. (~/.config는 findToken의 공통 폴백) */
    private static Path defaultConfigDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "github-copilot");
            }
            return Path.of(home, "AppData", "Local", "github-copilot");
        }
        return Path.of(home, ".config", "github-copilot");
    }
}

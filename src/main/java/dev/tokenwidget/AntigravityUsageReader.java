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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AntigravityUsageReader {
    private static final Pattern CSRF_TOKEN = Pattern.compile("--csrf_token\\s+([^\\s]+)");
    private static final Pattern HTTP_PORT = Pattern.compile("listening on random port at (\\d+) for HTTP");
    private static final Pattern REMAINING_FRACTION = Pattern.compile(
            "\\\"remainingFraction\\\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern RESET_TIME = Pattern.compile(
            "\\\"resetTime\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String RPC_PATH =
            "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary";

    private final Path mainLog;
    private final Path languageServerLog;
    private final HttpClient client;

    public AntigravityUsageReader() {
        this(defaultLogsDirectory(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    AntigravityUsageReader(Path logsDirectory, HttpClient client) {
        this.mainLog = logsDirectory.resolve("main.log");
        this.languageServerLog = logsDirectory.resolve("language_server.log");
        this.client = client;
    }

    public ProviderUsage readLatest() {
        if (!Files.isRegularFile(mainLog) || !Files.isRegularFile(languageServerLog)) {
            return ProviderUsage.waiting("Antigravity", "Antigravity 설치 또는 실행 기록 없음");
        }
        try {
            String token = lastMatch(CSRF_TOKEN, Files.readString(mainLog, StandardCharsets.UTF_8));
            String port = lastMatch(HTTP_PORT, Files.readString(languageServerLog, StandardCharsets.UTF_8));
            if (token == null || port == null) {
                return ProviderUsage.waiting("Antigravity", "로컬 language server 연결 정보 없음");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + RPC_PATH))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("x-codeium-csrf-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ProviderUsage.error("Antigravity", "로컬 API HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (java.net.ConnectException e) {
            return ProviderUsage.waiting("Antigravity", "Antigravity 앱 실행 대기 중");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderUsage.error("Antigravity", "조회 중단");
        } catch (IOException | IllegalArgumentException e) {
            return ProviderUsage.error("Antigravity", "한도 읽기 실패: " + concise(e.getMessage()));
        }
    }

    static ProviderUsage parse(String json) {
        String bucket = bucketObject(json, "gemini-weekly");
        if (bucket == null) {
            throw new IllegalArgumentException("gemini-weekly 버킷 없음");
        }
        Double remaining = optionalDouble(REMAINING_FRACTION, bucket);
        if (remaining == null) {
            throw new IllegalArgumentException("남은 비율 없음");
        }
        double usedPercent = Math.max(0, Math.min(100, (1.0 - remaining) * 100.0));
        Instant resetAt = optionalInstant(RESET_TIME, bucket);
        return new ProviderUsage("Antigravity",
                java.util.List.of(new UsageLimit("주간", usedPercent, resetAt)),
                "Gemini Models · 주간 %.1f%%".formatted(usedPercent), Instant.now(), null);
    }

    private static String bucketObject(String json, String bucketId) {
        int id = json.indexOf("\"bucketId\"");
        while (id >= 0) {
            int objectStart = json.lastIndexOf('{', id);
            int objectEnd = json.indexOf('}', id);
            if (objectStart >= 0 && objectEnd > objectStart) {
                String candidate = json.substring(objectStart, objectEnd + 1);
                if (candidate.matches("(?s).*\\\"bucketId\\\"\\s*:\\s*\\\""
                        + Pattern.quote(bucketId) + "\\\".*")) {
                    return candidate;
                }
            }
            id = json.indexOf("\"bucketId\"", id + 1);
        }
        return null;
    }

    private static String lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String result = null;
        while (matcher.find()) result = matcher.group(1);
        return result;
    }

    private static Double optionalDouble(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private static Instant optionalInstant(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        try {
            return Instant.parse(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String concise(String message) {
        if (message == null || message.isBlank()) return "알 수 없는 오류";
        return message.length() <= 45 ? message : message.substring(0, 44) + "…";
    }

    /** OS별 Antigravity 앱 데이터 경로를 런타임에 감지한다. */
    private static Path defaultLogsDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Antigravity", "logs");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) {
                return Path.of(home, "AppData", "Roaming", "Antigravity", "logs");
            }
            return Path.of(appData, "Antigravity", "logs");
        }
        return Path.of(home, ".config", "Antigravity", "logs");
    }
}

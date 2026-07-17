package dev.tokenwidget;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Codex가 `~/.codex/sessions`(모든 OS 공통)에 남기는 세션 로그에서 계정 한도를 읽는다.
 *
 * 주의: 이 값은 실시간 조회가 아니라 "이 컴퓨터에서 마지막으로 실행된 Codex 세션이
 * 마지막으로 보고한 시점"의 계정 스냅샷이다. 같은 계정을 다른 컴퓨터(예: 회사 PC)에서
 * 사용한 양은 이 컴퓨터에서 새 Codex 세션이 돌기 전까지 반영되지 않는다.
 * 그래서 스냅샷이 오래됐으면 관찰 시점을 함께 표시하고, 이미 초기화 시각이 지난
 * 한도 구간은 값이 무의미하므로 표시하지 않는다.
 */
public final class CodexUsageReader {
    private static final int TAIL_BYTES = 1_048_576;
    /** 최신 파일에 token_count가 아직 없을 때 대신 살펴볼 이전 세션 파일 수 상한. */
    private static final int MAX_FILES_TO_SCAN = 10;
    /** 이 시간 이상 지난 스냅샷에는 관찰 시점을 함께 표시한다. */
    private static final Duration STALE_BADGE_AFTER = Duration.ofMinutes(10);
    private static final Pattern LIMIT_BUCKET = Pattern.compile(
            "\\\"(primary|secondary)\\\"\\s*:\\s*(null|\\{[^{}]*})");
    private static final Pattern WINDOW_MINUTES = numberPattern("window_minutes");
    private static final Pattern RESET_AT = numberPattern("resets_at");
    private static final Pattern USED_PERCENT = decimalPattern("used_percent");
    private static final Pattern PLAN_TYPE = stringPattern("plan_type");
    private static final Pattern EVENT_TIMESTAMP = Pattern.compile(
            "^\\{\\\"timestamp\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final Path sessionsRoot;

    public CodexUsageReader() {
        this(Path.of(System.getProperty("user.home"), ".codex", "sessions"));
    }

    CodexUsageReader(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    public ProviderUsage readLatest() {
        try {
            List<Path> candidates = recentSessionFiles();
            if (candidates.isEmpty()) {
                return ProviderUsage.waiting("Codex", "Codex 세션을 찾지 못했습니다");
            }
            // 가장 최근 파일에 token_count가 아직 없으면(방금 시작한 세션 등)
            // 그다음 최근 파일까지 거슬러 올라가 마지막 스냅샷을 찾는다.
            int scanned = 0;
            for (Path file : candidates) {
                if (++scanned > MAX_FILES_TO_SCAN) break;
                String line = findLastTokenCountLine(file);
                if (line != null) {
                    Instant observedAt = eventTimestamp(line);
                    if (observedAt == null) {
                        observedAt = Files.getLastModifiedTime(file).toInstant();
                    }
                    return parse(line, observedAt);
                }
            }
            return ProviderUsage.waiting("Codex", "현재 작업의 첫 응답을 기다리는 중");
        } catch (Exception e) {
            return ProviderUsage.error("Codex", "로그 읽기 실패: " + e.getMessage());
        }
    }

    /** 세션 로그(jsonl)를 수정 시각 내림차순으로 반환한다. */
    List<Path> recentSessionFiles() throws IOException {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    String findLastTokenCountLine(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Keep reading until the requested tail is filled.
            }
            buffer.flip();
            String tail = StandardCharsets.UTF_8.decode(buffer).toString();
            String[] lines = tail.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].contains("\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\"")
                        && lines[i].contains("\"rate_limits\"")) {
                    return lines[i];
                }
            }
            return null;
        }
    }

    /** 로그 줄 맨 앞의 이벤트 시각을 읽는다. 없거나 형식이 다르면 null. */
    static Instant eventTimestamp(String jsonLine) {
        Matcher matcher = EVENT_TIMESTAMP.matcher(jsonLine);
        if (!matcher.find()) return null;
        try {
            return Instant.parse(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    static ProviderUsage parse(String jsonLine, Instant updatedAt) {
        return parse(jsonLine, updatedAt, Instant.now());
    }

    static ProviderUsage parse(String jsonLine, Instant updatedAt, Instant now) {
        LimitValue fiveHour = null;
        LimitValue weekly = null;
        Matcher buckets = LIMIT_BUCKET.matcher(jsonLine);
        while (buckets.find()) {
            if ("null".equals(buckets.group(2))) continue;
            String object = buckets.group(2);
            Long windowMinutes = optionalLongObject(WINDOW_MINUTES, object);
            Double usedPercent = optionalDouble(USED_PERCENT, object);
            Long resetEpoch = optionalLongObject(RESET_AT, object);
            if (windowMinutes == null || usedPercent == null) continue;
            Instant resetAt = resetEpoch == null ? null : Instant.ofEpochSecond(resetEpoch);
            // 초기화 시각이 이미 지난 구간은 창이 리셋되어 스냅샷의 퍼센트가 더 이상
            // 유효하지 않다(예: 며칠 전 마지막으로 관찰된 5시간 한도). 표시하지 않는다.
            if (resetAt != null && resetAt.isBefore(now)) continue;
            LimitValue value = new LimitValue(
                    Math.min(100.0, Math.max(0.0, usedPercent)), resetAt);
            if (windowMinutes <= 360) {
                fiveHour = value;
            } else if (windowMinutes >= 7 * 24 * 60) {
                weekly = value;
            }
        }
        String plan = optionalString(PLAN_TYPE, jsonLine, "unknown");
        String detail = plan + " · 계정 한도";
        String age = observedAgeLabel(updatedAt, now);
        if (age != null) {
            detail += " · " + age + " 기록";
        }

        // 페이로드에 실제로 존재하고 아직 만료되지 않은 한도 구간만 표시한다.
        java.util.List<UsageLimit> limits = new java.util.ArrayList<>();
        if (fiveHour != null) {
            limits.add(new UsageLimit("5시간", fiveHour.percent(), fiveHour.resetAt()));
        }
        if (weekly != null) {
            limits.add(new UsageLimit("주간", weekly.percent(), weekly.resetAt()));
        }
        if (limits.isEmpty()) {
            // 남은 구간이 없으면(마지막 스냅샷의 모든 창이 이미 초기화됨) 오래된 값을
            // 보여주는 대신 카드를 숨기고, 새 세션이 값을 보고하면 자동으로 복구된다.
            return ProviderUsage.waiting("Codex",
                    "마지막 기록이 초기화 시점을 지남 · 새 Codex 세션에서 갱신");
        }

        return new ProviderUsage("Codex", limits, detail, updatedAt, null);
    }

    /** 관찰 시점이 오래됐을 때 붙일 상대 시각 라벨. 최근 값이면 null. */
    static String observedAgeLabel(Instant observedAt, Instant now) {
        if (observedAt == null) return null;
        Duration age = Duration.between(observedAt, now);
        if (age.compareTo(STALE_BADGE_AFTER) < 0) return null;
        if (age.toHours() < 1) return age.toMinutes() + "분 전";
        if (age.toDays() < 1) return age.toHours() + "시간 전";
        return age.toDays() + "일 전";
    }

    private static Pattern numberPattern(String key) {
        return Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)");
    }

    private static Pattern decimalPattern(String key) {
        return Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    }

    private static Pattern stringPattern(String key) {
        return Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    }

    private static Long optionalLongObject(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static Double optionalDouble(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private static String optionalString(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private record LimitValue(double percent, Instant resetAt) {
    }
}

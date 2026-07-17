package dev.tokenwidget;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BrowserUsageServer implements AutoCloseable {
    public static final int PORT = 32145;
    private static final Pattern SESSION_PERCENT = numberPattern("sessionPercent");
    private static final Pattern WEEKLY_PERCENT = numberPattern("weeklyPercent");
    private static final Pattern SESSION_RESET = stringPattern("sessionResetAt");
    private static final Pattern WEEKLY_RESET = stringPattern("weeklyResetAt");
    private static final Pattern SOURCE_URL = stringPattern("sourceUrl");
    private static final Pattern MONTHLY_PERCENT = numberPattern("monthlyPercent");
    private static final Pattern MONTHLY_RESET = stringPattern("monthlyResetAt");
    private static final Pattern PLAN = stringPattern("plan");
    // 로그아웃 등으로 세션이 사라졌을 때 확장이 보내는 카드 제거 신호.
    private static final Pattern CLEAR_SIGNAL =
            Pattern.compile("\\\"(?:authExpired|cleared)\\\"\\s*:\\s*true");

    private static final long CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;

    private final java.util.prefs.Preferences cachePrefs =
            java.util.prefs.Preferences.userNodeForPackage(BrowserUsageServer.class);
    private final AtomicReference<ProviderUsage> claude;
    private final AtomicReference<ProviderUsage> cursor;
    private final int requestedPort;
    private HttpServer server;
    private Instant startedAt = Instant.now();
    // 이미 실행 중인 위젯에 /activate 요청이 오면 창을 앞으로 불러오기 위한 콜백.
    private volatile Runnable onActivate = () -> { };

    public BrowserUsageServer() {
        this(PORT);
    }

    BrowserUsageServer(int requestedPort) {
        this.requestedPort = requestedPort;
        // 위젯을 껐다 켜도 확장 프로그램의 다음 전송을 기다리지 않도록,
        // 마지막으로 수신한 사용량을 캐시에서 즉시 복원한다.
        this.claude = new AtomicReference<>(restoreCached("Claude", "claude"));
        this.cursor = new AtomicReference<>(restoreCached("Cursor", "cursor"));
    }

    private ProviderUsage restoreCached(String provider, String key) {
        String body = cachePrefs.get("cache." + key, null);
        long savedAt = cachePrefs.getLong("cache." + key + ".at", 0L);
        long age = System.currentTimeMillis() - savedAt;
        if (body != null && savedAt > 0 && age >= 0 && age < CACHE_MAX_AGE_MILLIS) {
            try {
                ProviderUsage parsed = parse(provider, body);
                return new ProviderUsage(provider, parsed.limits(), parsed.detail(),
                        Instant.ofEpochMilli(savedAt), null);
            } catch (RuntimeException ignored) {
                // 캐시가 손상되었으면 대기 상태로 시작한다.
            }
        }
        return ProviderUsage.waiting(provider, "확장 프로그램 연결 대기 중");
    }

    private void persistCache(String key, String body) {
        try {
            cachePrefs.put("cache." + key, body);
            cachePrefs.putLong("cache." + key + ".at", System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // 캐시 저장 실패는 치명적이지 않다.
        }
    }

    private void clearCache(String key) {
        try {
            cachePrefs.remove("cache." + key);
            cachePrefs.remove("cache." + key + ".at");
        } catch (RuntimeException ignored) {
            // 캐시 삭제 실패는 치명적이지 않다.
        }
    }

    public void start() throws IOException {
        startedAt = Instant.now();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.createContext("/claude-usage", exchange -> handleUsage(exchange, "Claude", claude));
        server.createContext("/cursor-usage", exchange -> handleUsage(exchange, "Cursor", cursor));
        server.createContext("/health", this::handleHealth);
        server.createContext("/activate", this::handleActivate);
        server.setExecutor(Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "browser-usage-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    int port() {
        return server == null ? requestedPort : server.getAddress().getPort();
    }

    public ProviderUsage latestClaude() {
        return latest(claude.get());
    }

    public ProviderUsage latestCursor() {
        return latest(cursor.get());
    }

    private ProviderUsage latest(ProviderUsage value) {
        if (value.updatedAt() != null && value.updatedAt().isBefore(Instant.now().minusSeconds(7 * 60))) {
            String detail = value.detail() == null ? "갱신 지연" : value.detail() + " · 갱신 지연";
            return new ProviderUsage(value.provider(), value.limits(), detail,
                    value.updatedAt(), value.error());
        }
        return value;
    }

    private void handleUsage(HttpExchange exchange, String provider,
            AtomicReference<ProviderUsage> destination) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (CLEAR_SIGNAL.matcher(body).find()) {
            // 세션이 사라졌다는 신호. 카드를 즉시 내리고(데이터 없음) 캐시도 지워
            // 위젯을 재시작해도 옛 값이 되살아나지 않게 한다.
            destination.set(ProviderUsage.waiting(provider, "로그아웃됨"));
            clearCache(provider.toLowerCase(java.util.Locale.ROOT));
            send(exchange, 200, "{\"ok\":true,\"cleared\":true}");
            return;
        }
        try {
            destination.set(parse(provider, body));
            persistCache(provider.toLowerCase(java.util.Locale.ROOT), body);
            send(exchange, 200, "{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"invalid payload\"}");
        }
    }

    /**
     * startedAt은 위젯 프로세스의 (재)시작 시각이다. 확장 프로그램이 이 값의 변화를 보고
     * 위젯이 재시작되었음을 감지해 캐시된 사용량을 즉시 재전송한다.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        addCors(exchange);
        send(exchange, 200, "{\"status\":\"up\",\"startedAt\":\"" + startedAt + "\"}");
    }

    /** 다른 인스턴스가 실행을 시도할 때 기존 위젯 창을 앞으로 불러오는 콜백을 등록한다. */
    public void setActivateCallback(Runnable callback) {
        this.onActivate = callback == null ? () -> { } : callback;
    }

    /**
     * 이미 실행 중인 인스턴스가 있을 때, 새로 실행된 프로세스가 이 엔드포인트를 호출해
     * 기존 위젯 창을 다시 화면 앞으로 불러온다(중복 창 대신 기존 창을 활성화).
     */
    private void handleActivate(HttpExchange exchange) throws IOException {
        addCors(exchange);
        try {
            onActivate.run();
        } catch (RuntimeException ignored) {
            // 창 활성화 실패가 응답을 막지는 않는다.
        }
        send(exchange, 200, "{\"ok\":true}");
    }

    static ProviderUsage parse(String provider, String json) {
        Double monthly = optionalDouble(MONTHLY_PERCENT, json);
        if (monthly != null) {
            return parseMonthly(provider, json, monthly);
        }
        Double session = optionalDouble(SESSION_PERCENT, json);
        Double weekly = optionalDouble(WEEKLY_PERCENT, json);
        if (session == null && weekly == null) {
            throw new IllegalArgumentException("usage percentage missing");
        }
        Instant sessionReset = parseInstant(optionalString(SESSION_RESET, json));
        Instant weeklyReset = parseInstant(optionalString(WEEKLY_RESET, json));
        String source = optionalString(SOURCE_URL, json);

        StringBuilder detail = new StringBuilder();
        if (session != null) detail.append("5시간 ").append(formatPercent(session));
        if (weekly != null) {
            if (!detail.isEmpty()) detail.append(" · ");
            detail.append("주간 ").append(formatPercent(weekly));
        }
        if (source != null && !source.isBlank()) detail.append(" · web");
        return new ProviderUsage(provider,
                java.util.List.of(
                        new UsageLimit("5시간", session, sessionReset),
                        new UsageLimit("주간", weekly, weeklyReset)),
                detail.toString(), Instant.now(), null);
    }

    /** 월간 한도만 제공하는 에이전트(Cursor 등)의 페이로드. */
    private static ProviderUsage parseMonthly(String provider, String json, double monthly) {
        Instant monthlyReset = parseInstant(optionalString(MONTHLY_RESET, json));
        String plan = optionalString(PLAN, json);
        StringBuilder detail = new StringBuilder();
        if (plan != null && !plan.isBlank()) detail.append(plan).append(" · ");
        detail.append("월간 ").append(formatPercent(monthly));
        return new ProviderUsage(provider,
                java.util.List.of(new UsageLimit("월간", monthly, monthlyReset)),
                detail.toString(), Instant.now(), null);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatPercent(double value) {
        return Math.rint(value) == value ? "%d%%".formatted((int) value) : "%.1f%%".formatted(value);
    }

    private static Pattern numberPattern(String key) {
        return Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(null|\\d+(?:\\.\\d+)?)");
    }

    private static Pattern stringPattern(String key) {
        return Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(null|\\\"((?:\\\\.|[^\\\"])*)\\\")");
    }

    private static Double optionalDouble(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) return null;
        return Double.parseDouble(matcher.group(1));
    }

    private static String optionalString(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) return null;
        return matcher.group(2).replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS, GET");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
    }
}

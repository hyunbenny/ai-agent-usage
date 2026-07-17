package dev.tokenwidget;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 하나의 AI 에이전트가 보고한 사용 한도 묶음.
 * limits는 표시 순서를 유지하며, 값이 없는 구간은 percent가 null이다.
 */
public record ProviderUsage(
        String provider,
        List<UsageLimit> limits,
        String detail,
        Instant updatedAt,
        String error) {

    public ProviderUsage {
        limits = limits == null ? List.of() : List.copyOf(limits);
    }

    public static ProviderUsage waiting(String provider, String detail) {
        return new ProviderUsage(provider, List.of(), detail, Instant.now(), null);
    }

    public static ProviderUsage error(String provider, String message) {
        return new ProviderUsage(provider, List.of(), null, Instant.now(), message);
    }

    /** 실제로 표시할 수 있는 사용률 값이 하나라도 있는지 여부. */
    public boolean hasData() {
        return error == null && limits.stream().anyMatch(UsageLimit::hasValue);
    }

    /** 간략히 모드에서 보여줄 대표 사용률(가장 높은 = 가장 임박한 한도). */
    public Double primaryPercent() {
        return limits.stream()
                .map(UsageLimit::percent)
                .filter(Objects::nonNull)
                .max(Double::compare)
                .orElse(null);
    }

    /** 라벨로 한도 구간을 찾는다. 없으면 null. */
    public UsageLimit limit(String label) {
        return limits.stream()
                .filter(limit -> limit.label().equals(label))
                .findFirst()
                .orElse(null);
    }
}

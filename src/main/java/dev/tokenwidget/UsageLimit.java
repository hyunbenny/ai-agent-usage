package dev.tokenwidget;

import java.time.Instant;

/**
 * 하나의 사용 한도 구간(예: 5시간, 주간, 월간)의 사용률과 초기화 시각.
 */
public record UsageLimit(String label, Double percent, Instant resetAt) {

    public static UsageLimit of(String label, Double percent, Instant resetAt) {
        return new UsageLimit(label, percent, resetAt);
    }

    public boolean hasValue() {
        return percent != null;
    }
}

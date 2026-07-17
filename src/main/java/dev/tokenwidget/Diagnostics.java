package dev.tokenwidget;

public final class Diagnostics {
    private Diagnostics() {
    }

    public static void main(String[] args) {
        print(new CodexUsageReader().readLatest());
        print(new AntigravityUsageReader().readLatest());
        print(new CopilotUsageReader().readLatest());
    }

    private static void print(ProviderUsage usage) {
        System.out.println("provider=" + usage.provider());
        for (UsageLimit limit : usage.limits()) {
            System.out.println("limit[" + limit.label() + "] percent=" + limit.percent()
                    + " resetAt=" + limit.resetAt());
        }
        System.out.println("detail=" + usage.detail());
        System.out.println("error=" + usage.error());
        System.out.println("visible=" + usage.hasData());
        System.out.println();
    }
}

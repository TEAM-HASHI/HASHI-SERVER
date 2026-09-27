package org.sopt.hashi.shared.migration;

/** 한 실행에서 연속된 실패만 센다. 실패 분류와 중단 처리는 호출자가 결정한다. */
public final class ConsecutiveFailureGuard {

    private final int limit;
    private int consecutiveFailures;

    public ConsecutiveFailureGuard(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("failure limit must be positive");
        }
        this.limit = limit;
    }

    public boolean record(boolean failed) {
        consecutiveFailures = failed ? (int) Math.min(consecutiveFailures + 1L, limit) : 0;
        return consecutiveFailures >= limit;
    }
}

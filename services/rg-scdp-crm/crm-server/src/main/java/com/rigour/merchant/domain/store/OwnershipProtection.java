package com.rigour.merchant.domain.store;

import java.time.Instant;

/** 只使用当前归属批次的有效事实；时间均为 UTC，自然日按 24 小时计算。 */
public final class OwnershipProtection {
    private OwnershipProtection() {}
    public record Policy(int firstVisitHours, int followupDays, int cooperatingDays, int cooldownHours) {}
    public record Window(String phase, Instant deadline) {
        public boolean expired(Instant now) { return !now.isBefore(deadline); }
        public String expiryReason() {
            return switch (phase) {
                case "FIRST_VISIT" -> "FIRST_VISIT_TIMEOUT";
                case "FOLLOW_UP" -> "FOLLOWUP_TIMEOUT";
                case "COOPERATING" -> "COOPERATION_TIMEOUT";
                default -> throw new IllegalStateException("Unknown protection phase");
            };
        }
    }
    public static Window calculate(Policy p, Instant started, Instant visit, Instant order) {
        if (visit != null && visit.isBefore(started) || order != null && order.isBefore(started))
            throw new IllegalArgumentException("事实不属于当前归属期间");
        if (order != null) {
            Instant latest = visit != null && visit.isAfter(order) ? visit : order;
            return new Window("COOPERATING", latest.plusSeconds(p.cooperatingDays() * 86400L));
        }
        if (visit != null) return new Window("FOLLOW_UP", visit.plusSeconds(p.followupDays() * 86400L));
        return new Window("FIRST_VISIT", started.plusSeconds(p.firstVisitHours() * 3600L));
    }
}

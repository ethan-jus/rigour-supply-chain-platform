package com.rigour.merchant.api.v1.model;

import java.time.Instant;

public final class StorePoolPolicyModels {
    private StorePoolPolicyModels() {}
    public record Policy(String id, int version, int firstVisitHours, int followupDays,
            int cooperatingDays, int returnCooldownHours, int activityDays, int dueSoonHours,
            String description, Instant publishedAt, String publishedBy) {}
    public record Publish(int expectedVersion, String requestId, int firstVisitHours, int followupDays,
            int cooperatingDays, int returnCooldownHours, int activityDays, int dueSoonHours, String description) {}
}

package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class StoreVisitQueryModels {
    private StoreVisitQueryModels() {}
    public record Row(String id,String storeId,String captureId,String name,String address,
            String status,Instant visitedAt,Instant checkedOutAt,String reviewReason) {}
    public record Summary(long totalVisitCount,long effectiveVisitCount,long firstVisitCount) {}
    public record Page(List<Row> items,long total,Summary summary,Row active) {}
}

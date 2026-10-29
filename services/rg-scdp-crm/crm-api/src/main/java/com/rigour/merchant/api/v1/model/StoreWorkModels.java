package com.rigour.merchant.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class StoreWorkModels {
    private StoreWorkModels() {}
    public record CheckIn(String requestId, String ownershipId, BigDecimal longitude,
            BigDecimal latitude, String coordinateSystem, BigDecimal accuracyMeters, String captureRef) {
        public CheckIn(String requestId,String ownershipId,BigDecimal longitude,BigDecimal latitude,String coordinateSystem,BigDecimal accuracyMeters){this(requestId,ownershipId,longitude,latitude,coordinateSystem,accuracyMeters,null);}
    }
    public record SubmitVisit(long revision, String resultSummary, List<String> photoRefs) {}
    public record CancelVisit(long revision,String reason) {}
    public record ReviewVisit(long revision, String decision, String reason) {}
    public record Visit(String id, String storeId, String ownershipId, String salespersonId,
            String status, Instant visitedAt, Instant submittedAt, Instant qualifiedAt,
            String resultSummary, String reviewReason, long revision, Long storeVisitNo,
            Long employeeVisitNo, Long ownershipVisitNo, String visitType, String captureRef, String reviewSource) {}
    public record OrderAuthorization(String orderId, String ownershipId, String requestId) {}
    public record OrderPermit(String storeId, String ownershipId, String salespersonId,
            String departmentId, String storeName, long ownershipRevision, Instant acceptedAt) {}
    public record RefreshResult(String ownershipId, String outcome, String phase,
            Instant deadline, String holdReason) {}
}

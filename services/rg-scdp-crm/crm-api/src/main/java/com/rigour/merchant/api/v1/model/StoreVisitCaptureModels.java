package com.rigour.merchant.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售采集保留原始证据，CRM 持有正式拜访和有效性；不复制门店主档。 */
public final class StoreVisitCaptureModels {
    private StoreVisitCaptureModels() {}
    public record Policy(String id, int radiusMeters) {}
    public record Context(String visitId,String storeId,String ownershipId,String employeeCode,
            String captureRef,String policyId,Instant checkedInAt,String storeName,String address,
            BigDecimal longitude,BigDecimal latitude,String coordinateSystem,BigDecimal distanceMeters,long revision,String status) {}
    public record Outcome(String visitId,String captureRef,String employeeCode,String policyId,
            Instant checkedInAt,Instant checkedOutAt,Instant submittedAt,String decision,String reason,
            long revision) {}
    public record Facts(String tenantId,String storeId,String ownershipId,Instant checkedAt,List<Outcome> items) {}
}

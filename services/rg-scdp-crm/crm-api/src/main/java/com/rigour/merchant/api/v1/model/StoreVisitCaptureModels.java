package com.rigour.merchant.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售采集保留原始证据，CRM 持有正式拜访和有效性；不复制门店主档。 */
public final class StoreVisitCaptureModels {
    private StoreVisitCaptureModels() {}
    public record Policy(String id, int radiusMeters, int minimumDwellMinutes, int requiredPhotoCount,
            boolean recordingEnabled, int minimumRecordingSeconds, int maximumClipGapSeconds,
            boolean aiAsrEnabled, boolean aiRelevanceEnabled, boolean aiDuplicateEnabled,
            BigDecimal aiAutoConfirmThreshold) {
        public Policy(String id,int radiusMeters) {
            this(id,radiusMeters,0,1,false,0,30,false,false,false,null);
        }
    }
    public record Context(String visitId,String storeId,String ownershipId,String employeeCode,
            String captureRef,String policyId,Instant checkedInAt,String storeName,String address,
            BigDecimal longitude,BigDecimal latitude,String coordinateSystem,BigDecimal distanceMeters,long revision,String status,
            Policy policy) {
        public Context(String visitId,String storeId,String ownershipId,String employeeCode,
                String captureRef,String policyId,Instant checkedInAt,String storeName,String address,
                BigDecimal longitude,BigDecimal latitude,String coordinateSystem,BigDecimal distanceMeters,long revision,String status) {
            this(visitId,storeId,ownershipId,employeeCode,captureRef,policyId,checkedInAt,storeName,address,
                    longitude,latitude,coordinateSystem,distanceMeters,revision,status,null);
        }
    }
    /** Server-verified raw evidence. The sender cannot decide CRM business effectiveness. */
    public record Evidence(String tenantId,String storeId,String ownershipId,String visitId,
            String captureRef,String employeeCode,String policyId,long revision,Instant checkedAt,
            Instant checkedInAt,Instant checkedOutAt,Instant submittedAt,String contactOutcome,String resultSummary,
            long verifiedPhotoCount,Long verifiedRecordingDurationMs,String recordingEvidenceStatus,
            String aiRecommendation,BigDecimal aiConfidenceScore,boolean aiResultAdopted) {}
}

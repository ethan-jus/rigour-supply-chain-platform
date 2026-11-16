package com.rigour.sales.api.v1.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
/** Sales owns policy publication; CRM retains an immutable copy on each visit. */
public final class SalesVisitPolicyModels {
    private SalesVisitPolicyModels() {}
    public record CaptureRules(boolean requireLocationOnOpen,Integer maximumLocationAccuracyMeters,
            boolean automaticRecording,boolean allowPhotoImport,boolean allowRecordingImport,
            Boolean automaticCheckout) {
        public CaptureRules { if (automaticCheckout == null) automaticCheckout = false; }
        public CaptureRules(boolean requireLocationOnOpen,Integer maximumLocationAccuracyMeters,
                boolean automaticRecording,boolean allowPhotoImport,boolean allowRecordingImport) {
            this(requireLocationOnOpen,maximumLocationAccuracyMeters,automaticRecording,allowPhotoImport,allowRecordingImport,false);
        }
        public static CaptureRules legacy() { return new CaptureRules(false,null,false,false,false); }
    }
    public record Snapshot(String id,int radiusMeters,int minimumDwellMinutes,int requiredPhotoCount,
            boolean recordingEnabled,int minimumRecordingSeconds,int maximumClipGapSeconds,
            boolean aiAsrEnabled,boolean aiRelevanceEnabled,boolean aiDuplicateEnabled,
            BigDecimal aiAutoConfirmThreshold,CaptureRules captureRules) {}
    public record Version(String id,int versionNo,String policyCode,String name,String publishStatus,
            String scopeType,UUID scopeId,Snapshot policy,Instant createdAt,String createdBy) {}
}

package com.rigour.merchant.domain.store;

import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Final customer visit rules live in CRM; media technical verification stays at capture source. */
public final class VisitEvidenceAssessment {
    private VisitEvidenceAssessment() {}
    public static String state(List<String> reasons) {
        if(reasons.isEmpty())return "PASSED";
        if(reasons.stream().anyMatch(r->Set.of("VISIT_TIME_INVALID","CONTACT_NOT_CONFIRMED","DWELL_TOO_SHORT","RECORDING_TOO_SHORT","SUBMISSION_AFTER_DEADLINE").contains(r)))return "FAILED";
        if(reasons.contains("RECORDING_PENDING")||reasons.contains("STOREFRONT_PHOTO_MISSING")||reasons.contains("RESULT_MISSING"))return "PROCESSING";
        return "INSUFFICIENT";
    }
    public static List<String> anomalies(Policy policy,Evidence facts) {
        List<String> reasons=new ArrayList<>();
        if(facts.checkedInAt()==null || facts.checkedOutAt()==null || facts.checkedOutAt().isBefore(facts.checkedInAt()))
            reasons.add("VISIT_TIME_INVALID");
        if(facts.submittedAt()==null) reasons.add("RESULT_MISSING");
        if(!"CONTACTED".equals(facts.contactOutcome())) reasons.add("CONTACT_NOT_CONFIRMED");
        if(facts.checkedInAt()!=null && facts.checkedOutAt()!=null
                && Duration.between(facts.checkedInAt(),facts.checkedOutAt()).toMinutes()<policy.minimumDwellMinutes())
            reasons.add("DWELL_TOO_SHORT");
        if(facts.verifiedPhotoCount()<Math.max(1,policy.requiredPhotoCount())) reasons.add("STOREFRONT_PHOTO_MISSING");
        if(policy.recordingEnabled()) {
            if(!"TECHNICALLY_VERIFIED".equals(facts.recordingEvidenceStatus()) || facts.verifiedRecordingDurationMs()==null)
                reasons.add("REVIEW_REQUIRED".equals(facts.recordingEvidenceStatus())?"RECORDING_UNVERIFIED":"RECORDING_PENDING");
            else if(facts.verifiedRecordingDurationMs()<policy.minimumRecordingSeconds()*1000L)
                reasons.add("RECORDING_TOO_SHORT");
        }
        return List.copyOf(reasons);
    }
}

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
                reasons.add("RECORDING_UNVERIFIED");
            else if(facts.verifiedRecordingDurationMs()<policy.minimumRecordingSeconds()*1000L)
                reasons.add("RECORDING_TOO_SHORT");
        }
        if(policy.aiAsrEnabled() || policy.aiRelevanceEnabled() || policy.aiDuplicateEnabled()) {
            if(!facts.aiResultAdopted()) reasons.add("AI_PENDING");
            else if(!Set.of("AUTO_CONFIRM","EFFECTIVE").contains(facts.aiRecommendation()==null?"":facts.aiRecommendation()))
                reasons.add("AI_REVIEW_REQUIRED");
            else if(policy.aiAutoConfirmThreshold()!=null && (facts.aiConfidenceScore()==null
                    || facts.aiConfidenceScore().compareTo(policy.aiAutoConfirmThreshold())<0))
                reasons.add("AI_LOW_CONFIDENCE");
        }
        return List.copyOf(reasons);
    }
}

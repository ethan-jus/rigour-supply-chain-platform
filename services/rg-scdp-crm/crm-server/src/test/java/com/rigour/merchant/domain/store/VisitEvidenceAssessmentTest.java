package com.rigour.merchant.domain.store;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VisitEvidenceAssessmentTest {
    final Instant at=Instant.parse("2026-11-06T02:00:00Z");
    final Policy policy=new Policy("policy",500,10,1,true,600,30,true,true,true,new BigDecimal("0.9"));
    Evidence evidence(int dwell,long photos,Long audio,String audioStatus,String ai,BigDecimal score,boolean adopted){
        return new Evidence("tenant","1","2","3","capture","EMP","policy",1,at.plusSeconds(1000),at,at.plusSeconds(dwell),at.plusSeconds(dwell),"CONTACTED","客户有采购意向",photos,audio,audioStatus,ai,score,adopted);
    }
    @Test void verifiedCompleteEvidenceQualifies(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"TECHNICALLY_VERIFIED","AUTO_CONFIRM",BigDecimal.ONE,true))).isEmpty();}
    @Test void reportedAudioDurationWithoutServerVerificationCannotQualify(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"RECEIVED","AUTO_CONFIRM",BigDecimal.ONE,true))).containsExactly("RECORDING_UNVERIFIED");}
    @Test void missingEvidenceStaysForReview(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(599,0,599000L,"TECHNICALLY_VERIFIED",null,null,false))).containsExactly("DWELL_TOO_SHORT","STOREFRONT_PHOTO_MISSING","RECORDING_TOO_SHORT","AI_PENDING");}
    @Test void LowConfidenceDoesNotAutomaticallyQualify(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"TECHNICALLY_VERIFIED","AUTO_CONFIRM",new BigDecimal("0.89"),true))).containsExactly("AI_LOW_CONFIDENCE");}
}

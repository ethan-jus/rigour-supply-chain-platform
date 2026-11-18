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
        return new Evidence("tenant","1","2","3","capture","EMP","policy",1,at.plusSeconds(1000),at,at.plusSeconds(dwell),at.plusSeconds(dwell),"CONTACTED","客户有采购意向",photos,audio,audioStatus,ai,score,adopted,null,null,null,"SUCCEEDED",null);
    }
    @Test void verifiedCompleteEvidenceQualifies(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"TECHNICALLY_VERIFIED","AUTO_CONFIRM",BigDecimal.ONE,true))).isEmpty();}
    @Test void tenMinuteRecordingThresholdIsIndependentOfDwellAndInclusive() {
        var shortAudio=VisitEvidenceAssessment.anomalies(policy,evidence(1200,1,599999L,"TECHNICALLY_VERIFIED",null,null,false));
        assertThat(shortAudio).containsExactly("RECORDING_TOO_SHORT");
        assertThat(VisitEvidenceAssessment.state(shortAudio)).isEqualTo("FAILED");
        assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(1200,1,600000L,"TECHNICALLY_VERIFIED",null,null,false))).isEmpty();
    }
    @Test void recordingThresholdComesFromVisitPolicySnapshot() {
        var longer=new Policy("new-policy",500,10,1,true,900,30,true,true,true,new BigDecimal("0.9"));
        var facts=evidence(1200,1,600000L,"TECHNICALLY_VERIFIED",null,null,false);
        assertThat(VisitEvidenceAssessment.anomalies(policy,facts)).isEmpty();
        assertThat(VisitEvidenceAssessment.anomalies(longer,facts)).containsExactly("RECORDING_TOO_SHORT");
    }
    @Test void reportedAudioDurationWithoutServerVerificationCannotQualify(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"RECEIVED","AUTO_CONFIRM",BigDecimal.ONE,true))).containsExactly("RECORDING_PENDING");}
    @Test void objectiveThresholdsStillApply(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(599,0,599000L,"TECHNICALLY_VERIFIED",null,null,false))).containsExactly("DWELL_TOO_SHORT","STOREFRONT_PHOTO_MISSING","RECORDING_TOO_SHORT");}
    @Test void contentAnalysisDoesNotBlockObjectiveEvidence(){assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"TECHNICALLY_VERIFIED","AUTO_CONFIRM",new BigDecimal("0.89"),true))).isEmpty();}
    @Test void transcriptionOnlyPolicyDoesNotRequirePaidContentAnalysis(){
        var asrOnly=new Policy("policy",500,10,1,true,600,30,true,false,false,new BigDecimal("0.9"));
        assertThat(VisitEvidenceAssessment.anomalies(asrOnly,evidence(600,1,600000L,"TECHNICALLY_VERIFIED",null,null,false))).isEmpty();
    }
    @Test void transcriptionFailureOrDelayDoesNotChangeObjectiveResult(){
        for(String status:java.util.List.of("PENDING","FAILED","MISSING","SUCCEEDED")){
            var e=new Evidence("tenant","1","2","3","capture","EMP","policy",1,at.plusSeconds(1000),at,at.plusSeconds(600),at.plusSeconds(600),"CONTACTED","现场沟通",1,600000L,"TECHNICALLY_VERIFIED",null,null,false,null,null,null,status,null);
            assertThat(VisitEvidenceAssessment.anomalies(policy,e)).as(status).isEmpty();
        }
    }
    @Test void automationSeparatesBusinessFailureFromUploadProcessingAndEvidenceAnomaly(){
        assertThat(VisitEvidenceAssessment.state(java.util.List.of())).isEqualTo("PASSED");
        assertThat(VisitEvidenceAssessment.state(java.util.List.of("RECORDING_PENDING"))).isEqualTo("PROCESSING");
        assertThat(VisitEvidenceAssessment.state(java.util.List.of("STOREFRONT_PHOTO_MISSING"))).isEqualTo("PROCESSING");
        assertThat(VisitEvidenceAssessment.state(java.util.List.of("RECORDING_UNVERIFIED"))).isEqualTo("INSUFFICIENT");
        assertThat(VisitEvidenceAssessment.state(java.util.List.of("DWELL_TOO_SHORT","RECORDING_PENDING"))).isEqualTo("FAILED");
        assertThat(VisitEvidenceAssessment.anomalies(policy,evidence(600,1,600000L,"REVIEW_REQUIRED",null,null,false))).containsExactly("RECORDING_UNVERIFIED");
    }
}

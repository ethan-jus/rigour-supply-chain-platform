package com.rigour.sales.application.service;
import static org.assertj.core.api.Assertions.*;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.CaptureRules;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.VisitPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class SalesCapturePolicyChecksTest {
    private VisitPolicy policy(boolean imports) { return new VisitPolicy(UUID.randomUUID(),"APP","APP",1,"PUBLISHED",true,false,500,10,1,true,600,30,new CaptureRules(true,100,true,imports,imports)); }
    @Test void strictVisitRejectsImportAndMissingAudioSource() {
        var p=policy(false);
        assertThatThrownBy(()->SalesCapturePolicyChecks.photo(p,"FILE_IMPORT")).hasMessageContaining("现场拍照");
        assertThatThrownBy(()->SalesCapturePolicyChecks.photo(p,"FEISHU_CAMERA")).hasMessageContaining("现场拍照");
        assertThatThrownBy(()->SalesCapturePolicyChecks.recording(p,"FILE_IMPORT")).hasMessageContaining("现场录音");
        assertThatThrownBy(()->SalesCapturePolicyChecks.recording(p,null)).hasMessageContaining("现场录音");
        assertThatCode(()->SalesCapturePolicyChecks.photo(p,"APP_CAMERA")).doesNotThrowAnyException();
        assertThatCode(()->SalesCapturePolicyChecks.recording(p,"APP_RECORDER")).doesNotThrowAnyException();
    }
    @Test void historicalImportFlagsCannotReenableExternalFiles() {
        var p=policy(true);
        assertThatThrownBy(()->SalesCapturePolicyChecks.photo(p,"FILE_IMPORT")).hasMessageContaining("现场拍照");
        assertThatThrownBy(()->SalesCapturePolicyChecks.recording(p,"FILE_IMPORT")).hasMessageContaining("现场录音");
        assertThatThrownBy(()->SalesCapturePolicyChecks.recording(p,"OTHER")).hasMessageContaining("现场录音");
    }
    @Test void accuracyMustBeKnownPositiveAndWithinSnapshot() {
        var p=policy(false);
        for(BigDecimal value:new BigDecimal[]{null,BigDecimal.ZERO,BigDecimal.valueOf(101)}) assertThatThrownBy(()->SalesCapturePolicyChecks.location(p,value)).hasMessageContaining("定位精度");
        assertThatCode(()->SalesCapturePolicyChecks.location(p,BigDecimal.valueOf(100))).doesNotThrowAnyException();
    }
    @Test void legacyVisitKeepsItsOriginalCaptureContract() {
        var p=new VisitPolicy(UUID.randomUUID(),"OLD","OLD",1,"PUBLISHED",true,false,500,0,1,false,0,30);
        assertThatCode(()->SalesCapturePolicyChecks.photo(p,"FEISHU_CAMERA")).doesNotThrowAnyException();
        assertThatCode(()->SalesCapturePolicyChecks.recording(p,null)).doesNotThrowAnyException();
        assertThatCode(()->SalesCapturePolicyChecks.location(p,null)).doesNotThrowAnyException();
    }
}

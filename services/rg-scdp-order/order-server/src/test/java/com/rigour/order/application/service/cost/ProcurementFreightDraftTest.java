package com.rigour.order.application.service.cost;
import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.order.domain.cost.ProcurementFreightSyncPolicy;
import com.rigour.order.api.v1.model.LogisticsCostLedger.SourceSync;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
class ProcurementFreightDraftTest {
    static ProcurementCostSource source(String bearer,BigDecimal freight){return new ProcurementCostSource("PO1",1,LocalDate.of(2026,11,3),"SUP1","华东食品供应有限公司","厂家地址","李经理","13800000000",7,"WH1","武汉城市仓","C1","张三","13800000001","仓库收货地址",bearer,freight);}
    @Test void includedFreightRemainsZeroOwnCostAndUnknownThirdPartyTotal(){
        var c=ProcurementFreightDraft.from(source("INCLUDED",new BigDecimal("18")));
        assertThat(c.amount()).isEqualByComparingTo("0");assertThat(c.ledger().feeItems().getFirst().feeAmount()).isNull();
        assertThat(c.ledger().feeItems().getFirst().zeroCostReason()).contains("已含运费");assertThat(c.paymentStatusCode()).isEqualTo("UNKNOWN");
        assertThat(c.requestId()).isEqualTo(ProcurementFreightDraft.from(source("INCLUDED",new BigDecimal("18.00"))).requestId());
        assertThat(c.ledger().sourceSync().lastAppliedFingerprint()).isEqualTo(ProcurementFreightDraft.from(source("INCLUDED",new BigDecimal("18.00"))).ledger().sourceSync().lastAppliedFingerprint());
    }
    @Test void unspecifiedBuyerZeroIsPendingVerificationRatherThanInventedFreeShipping(){
        assertThat(ProcurementFreightDraft.from(source("BUYER",BigDecimal.ZERO)).amount()).isNull();
    }
    @Test void manualOrConfirmedRecordsAreProtectedAndRepeatedOrOlderSourcesAreIgnored(){
        var base=ProcurementFreightDraft.from(source("BUYER",new BigDecimal("18"))).ledger().sourceSync();
        var changed=new SourceSync(base.originBizKey(),2,"new",false,2,"new",false);
        assertThat(ProcurementFreightSyncPolicy.decide("DRAFT",base,changed)).isEqualTo(ProcurementFreightSyncPolicy.Decision.REPLACE_DRAFT);
        assertThat(ProcurementFreightSyncPolicy.decide("CONFIRMED",base,changed)).isEqualTo(ProcurementFreightSyncPolicy.Decision.SOURCE_DIFFERENCE);
        var manual=new SourceSync(base.originBizKey(),1,base.lastAppliedFingerprint(),true,2,"new",true);
        assertThat(ProcurementFreightSyncPolicy.decide("DRAFT",manual,changed)).isEqualTo(ProcurementFreightSyncPolicy.Decision.UNCHANGED);
        assertThat(ProcurementFreightSyncPolicy.decide("DRAFT",manual,base)).isEqualTo(ProcurementFreightSyncPolicy.Decision.UNCHANGED);
    }
}

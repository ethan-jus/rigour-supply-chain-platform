package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DhbIntegrationServiceTest {

    @Test
    void qualifiesSkuSourceIdWithItsProductBecauseDhbOptionsIdIsNotGloballyUnique() {
        assertThat(DhbIntegrationService.normalizedSkuSourceId("P-1", "0"))
                .isEqualTo("P-1::0");
        assertThat(DhbIntegrationService.normalizedSkuSourceId("P-2", "0"))
                .isEqualTo("P-2::0");
    }

    @Test
    void rejectsMissingProductOrSkuSourceId() {
        assertThatThrownBy(() -> DhbIntegrationService.normalizedSkuSourceId("P-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订货宝商品或SKU来源ID不能为空");
    }

    @Test
    void extractsUniqueTransferInboundReceiptCandidates() {
        assertThat(DhbIntegrationService.candidateReceipts("""
                {"candidateReceipts":["RK.1","RK.1","RK.2"," "]}
                """)).containsExactly("RK.1", "RK.2");
    }

    @Test
    void classifiesRealTransferAmbiguityAsManualResolution() {
        DhbIntegrationService.OpenIssueClassification classification =
                DhbIntegrationService.classifyOpenIssue("ERP_STOCK_OUT",
                        "DHB_TRANSFER_INBOUND_AMBIGUOUS", List.of("RK.1", "RK.2"));

        assertThat(classification.category()).isEqualTo("MANUAL_TRANSFER_INBOUND");
        assertThat(classification.actionType()).isEqualTo("MANUAL_RESOLUTION");
        assertThat(classification.manualResolutionRequired()).isTrue();
        assertThat(classification.replaySupported()).isTrue();
    }

    @Test
    void classifiesMissingBusinessTimeAsSourceTimeRepair() {
        DhbIntegrationService.OpenIssueClassification classification =
                DhbIntegrationService.classifyOpenIssue("SALES_ORDER",
                        "DHB_ORDER_BUSINESS_TIME_MISSING", List.of());

        assertThat(classification.category()).isEqualTo("SOURCE_TIME_REQUIRED");
        assertThat(classification.actionType()).isEqualTo("FIX_SOURCE_TIME");
        assertThat(classification.handlingAdvice()).contains("不能用系统当前时间兜底");
    }
}

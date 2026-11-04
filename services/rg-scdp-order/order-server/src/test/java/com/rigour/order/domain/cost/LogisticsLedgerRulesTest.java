package com.rigour.order.domain.cost;

import com.rigour.order.api.v1.model.LogisticsCostLedger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class LogisticsLedgerRulesTest {
    final JsonMapper json = JsonMapper.builder().build();
    final String fixture = """
      {"schemaVersion":2,"transportPurposeCode":"DELIVERY","deliveryModeCode":"THIRD_PARTY","carrierCode":"HLL","costMonth":"2026-11","trackingNo":"HL1",
      "sender":{"partyTypeCode":"OTHER","manualName":"厂家","address":"发货地址"},
      "receivingPoints":[{"pointCode":"P1","partyTypeCode":"OTHER","manualName":"星河门店","contactName":"张三","phone":"13800000001","address":"地址1","cityCode":"C1"},{"pointCode":"P2","partyTypeCode":"OTHER","manualName":"长江门店","contactName":"李四","phone":"13800000002","address":"地址2","cityCode":"C1"}],
      "feeItems":[{"feeKey":"F1","feeTypeCode":"FREIGHT","feeAmount":300,"bearerCode":"COMPANY","ourAmount":300,"allocationMethodCode":"MANUAL"}],
      "sources":[{"orderTypeCode":"SALES_ORDER","orderCode":"SO1","linkRoleCode":"COST","allocations":[{"feeKey":"F1","receivingPointCode":"P1","cityCode":"C1","amount":100}]},{"orderTypeCode":"SALES_ORDER","orderCode":"SO2","linkRoleCode":"COST","allocations":[{"feeKey":"F1","receivingPointCode":"P2","cityCode":"C1","amount":200}]},{"orderTypeCode":"PURCHASE_ORDER","orderCode":"PO1","linkRoleCode":"TRACE","allocations":[]}]}
      """;
    @Test void oneFeeCanBelongToTwoOrdersWhilePurchaseIsOnlyTraced() {
        assertThat(LogisticsLedgerRules.validate(json.readValue(fixture,LogisticsCostLedger.class),true)).isEqualByComparingTo("300");
    }
    @Test void aSecondFullChargeOrUnknownPointIsRejected() {
        assertThatThrownBy(() -> LogisticsLedgerRules.validate(json.readValue(fixture.replace("\"amount\":200","\"amount\":300"),LogisticsCostLedger.class),true)).hasMessageContaining("分摊金额");
        assertThatThrownBy(() -> LogisticsLedgerRules.validate(json.readValue(fixture.replace("\"receivingPointCode\":\"P2\"","\"receivingPointCode\":\"missing\""),LogisticsCostLedger.class),true)).hasMessageContaining("收货点");
    }
    @Test void freeShippingPreservesUnknownThirdPartyCostAndKnownZeroOwnCost() {
        String s=fixture.replace("\"feeAmount\":300","\"feeAmount\":null").replace("\"bearerCode\":\"COMPANY\"","\"bearerCode\":\"SUPPLIER\",\"zeroCostReason\":\"厂家包邮\"").replace("\"ourAmount\":300","\"ourAmount\":0").replace("\"amount\":100","\"amount\":0").replace("\"amount\":200","\"amount\":0");
        assertThat(LogisticsLedgerRules.validate(json.readValue(s,LogisticsCostLedger.class),true)).isEqualByComparingTo("0");
    }
    @Test void selectedAutomaticMethodCannotBeUsedWithArbitraryManualShares() {
        assertThatThrownBy(() -> LogisticsLedgerRules.validate(json.readValue(fixture.replace("\"allocationMethodCode\":\"MANUAL\"","\"allocationMethodCode\":\"EQUAL\""),LogisticsCostLedger.class),true)).hasMessageContaining("重新计算");
    }
}

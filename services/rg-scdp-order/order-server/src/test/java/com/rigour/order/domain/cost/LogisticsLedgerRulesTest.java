package com.rigour.order.domain.cost;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.LogisticsCostLedger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

class LogisticsLedgerRulesTest {
    final JsonMapper json = JsonMapper.builder().build();
    final String fixture =
            """
            {"schemaVersion":2,"transportPurposeCode":"DELIVERY","deliveryModeCode":"THIRD_PARTY","carrierCode":"HLL","costMonth":"2026-11","trackingNo":"HL1",
            "sender":{"partyTypeCode":"OTHER","manualName":"厂家","address":"发货地址"},
            "receivingPoints":[{"pointCode":"P1","partyTypeCode":"OTHER","manualName":"星河门店","contactName":"张三","phone":"13800000001","address":"地址1","cityCode":"C1"},{"pointCode":"P2","partyTypeCode":"OTHER","manualName":"长江门店","contactName":"李四","phone":"13800000002","address":"地址2","cityCode":"C1"}],
            "feeItems":[{"feeKey":"F1","feeTypeCode":"FREIGHT","feeAmount":300,"bearerCode":"COMPANY","ourAmount":300,"allocationMethodCode":"MANUAL"}],
            "sources":[{"orderTypeCode":"SALES_ORDER","orderCode":"SO1","linkRoleCode":"COST","allocations":[{"feeKey":"F1","receivingPointCode":"P1","cityCode":"C1","amount":100}]},{"orderTypeCode":"SALES_ORDER","orderCode":"SO2","linkRoleCode":"COST","allocations":[{"feeKey":"F1","receivingPointCode":"P2","cityCode":"C1","amount":200}]},{"orderTypeCode":"PURCHASE_ORDER","orderCode":"PO1","linkRoleCode":"TRACE","allocations":[]}]}
            """;

    @Test
    void thirdPartyOrderNumberIsIndependentOfCarrierAndWaybill() {
        for (String carrier : java.util.List.of("HLL", "SF", "JT_NEW")) {
            var ledger =
                    json.readValue(
                            fixture.replace(
                                    "\"carrierCode\":\"HLL\"",
                                    "\"carrierCode\":\"" + carrier + "\""),
                            LogisticsCostLedger.class);
            assertThat(LogisticsLedgerRules.validate(ledger.withPlatformOrderNo("ORDER-001"), true))
                    .isEqualByComparingTo("300");
            assertThat(LogisticsLedgerRules.validate(ledger.withPlatformOrderNo(null), true))
                    .isEqualByComparingTo("300");
            assertThatThrownBy(
                            () ->
                                    LogisticsLedgerRules.validate(
                                            ledger.withPlatformOrderNo("X".repeat(81)), true))
                    .hasMessageContaining("第三方订单号过长");
        }
    }

    @Test
    void oneFeeCanBelongToTwoOrdersWhilePurchaseIsOnlyTraced() {
        assertThat(
                        LogisticsLedgerRules.validate(
                                json.readValue(fixture, LogisticsCostLedger.class), true))
                .isEqualByComparingTo("300");
    }

    @Test
    void onlyThreeTransportTypesAreAcceptedAndOtherExpensesRequireTheirPurpose() {
        for (String type : java.util.List.of("TRANSFER", "DELIVERY"))
            assertThat(
                            LogisticsLedgerRules.validate(
                                    json.readValue(
                                            fixture.replace(
                                                    "\"transportPurposeCode\":\"DELIVERY\"",
                                                    "\"transportPurposeCode\":\"" + type + "\""),
                                            LogisticsCostLedger.class),
                                    true))
                    .isEqualByComparingTo("300");
        var other =
                json.readValue(
                        fixture.replace(
                                "\"transportPurposeCode\":\"DELIVERY\"",
                                "\"transportPurposeCode\":\"OTHER\",\"nonSalesPurposeCode\":\"活动赠送配送\""),
                        LogisticsCostLedger.class);
        assertThat(LogisticsLedgerRules.validate(other, true)).isEqualByComparingTo("300");
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.validate(
                                        other.withTransportType("PURCHASE"), true))
                .hasMessageContaining("采购成本");
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.validate(
                                        json.readValue(
                                                fixture.replace(
                                                        "\"transportPurposeCode\":\"DELIVERY\"",
                                                        "\"transportPurposeCode\":\"OTHER\""),
                                                LogisticsCostLedger.class),
                                        true))
                .hasMessageContaining("其他费用用途说明");
    }

    @Test
    void aSecondFullChargeOrUnknownPointIsRejected() {
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.validate(
                                        json.readValue(
                                                fixture.replace("\"amount\":200", "\"amount\":300"),
                                                LogisticsCostLedger.class),
                                        true))
                .hasMessageContaining("分摊金额");
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.validate(
                                        json.readValue(
                                                fixture.replace(
                                                        "\"receivingPointCode\":\"P2\"",
                                                        "\"receivingPointCode\":\"missing\""),
                                                LogisticsCostLedger.class),
                                        true))
                .hasMessageContaining("收货点");
    }

    @Test
    void freeShippingPreservesUnknownThirdPartyCostAndKnownZeroOwnCost() {
        String s =
                fixture.replace("\"feeAmount\":300", "\"feeAmount\":null")
                        .replace(
                                "\"bearerCode\":\"COMPANY\"",
                                "\"bearerCode\":\"SUPPLIER\",\"zeroCostReason\":\"厂家包邮\"")
                        .replace("\"ourAmount\":300", "\"ourAmount\":0")
                        .replace("\"amount\":100", "\"amount\":0")
                        .replace("\"amount\":200", "\"amount\":0");
        assertThat(
                        LogisticsLedgerRules.validate(
                                json.readValue(s, LogisticsCostLedger.class), true))
                .isEqualByComparingTo("0");
    }

    @Test
    void selectedAutomaticMethodCannotBeUsedWithArbitraryManualShares() {
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.validate(
                                        json.readValue(
                                                fixture.replace(
                                                        "\"allocationMethodCode\":\"MANUAL\"",
                                                        "\"allocationMethodCode\":\"EQUAL\""),
                                                LogisticsCostLedger.class),
                                        true))
                .hasMessageContaining("重新计算");
    }

    @Test
    void manualBillsSupportDiscountsButCannotMakeANegativeOrUnknownCost() {
        var fee = new LogisticsCostLedger.ManualBillItem("运费", new BigDecimal("20.12"));
        var discount = new LogisticsCostLedger.ManualBillItem("优惠", new BigDecimal("-2.11"));
        assertThat(LogisticsLedgerRules.manualBillTotal(List.of(fee, discount)))
                .isEqualByComparingTo("18.01");
        assertThatThrownBy(() -> LogisticsLedgerRules.manualBillTotal(List.of(discount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.manualBillTotal(
                                        List.of(
                                                new LogisticsCostLedger.ManualBillItem(
                                                        "运费", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.manualBillTotal(
                                        List.of(
                                                new LogisticsCostLedger.ManualBillItem(
                                                        "", BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                LogisticsLedgerRules.manualBillTotal(
                                        List.of(
                                                new LogisticsCostLedger.ManualBillItem(
                                                        "运费", new BigDecimal("1.001")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manualBillBreakdownIsPreservedAndCannotBeAddedToCostTwice() {
        var ledger =
                json.readValue(fixture, LogisticsCostLedger.class)
                        .withManualBillItems(
                                List.of(
                                        new LogisticsCostLedger.ManualBillItem(
                                                "运费", new BigDecimal("310")),
                                        new LogisticsCostLedger.ManualBillItem(
                                                "优惠", new BigDecimal("-10"))));
        ledger =
                ledger.withPlatformOrderNo("SF-ORDER")
                        .withSources(ledger.sources())
                        .withScopeCities(List.of("C1"))
                        .withTransportType("DELIVERY")
                        .withSourceSync(null)
                        .resolved(ledger.sender(), ledger.receivingPoints(), List.of());
        assertThat(ledger.manualBillItems()).hasSize(2);
        assertThat(LogisticsLedgerRules.validate(ledger, true)).isEqualByComparingTo("300");
        var tampered =
                ledger.withManualBillItems(
                        List.of(new LogisticsCostLedger.ManualBillItem("运费", BigDecimal.ONE)));
        assertThatThrownBy(() -> LogisticsLedgerRules.validate(tampered, true))
                .hasMessageContaining("明细合计");
        assertThat(
                        json.readValue(json.writeValueAsString(ledger), LogisticsCostLedger.class)
                                .manualBillItems())
                .hasSize(2);
    }
}

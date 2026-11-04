package com.rigour.erp.domain.cost;

import com.rigour.erp.api.v1.model.WarehouseCostLedger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class WarehouseLedgerRulesTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private WarehouseCostLedger plan(String extra) {
        return json.readValue("""
            {"schemaVersion":2,"warehouseCode":"WH1","depositAmount":10000,"feeItems":[
            {"feeKey":"rent","feeTypeCode":"RENT","feeAmount":60000,"allocationMethodCode":"MONTH_EQUAL","serviceFrom":"2026-11-01","serviceTo":"2027-04-30"},
            {"feeKey":"assembly","feeTypeCode":"ASSEMBLY","feeAmount":1000,"allocationMethodCode":"SINGLE_DATE","attributionDate":"2026-11-03"}
            ]}
            """.replace("60000", extra), WarehouseCostLedger.class);
    }
    @Test void halfYearPaymentAndDepositDoNotBecomeOneMonthExpense() {
        var p = WarehouseLedgerRules.normalize(plan("60000"), true);
        assertThat(WarehouseLedgerRules.total(p)).isEqualByComparingTo("61000.00");
        assertThat(p.feeItems().getFirst().monthAllocations()).hasSize(6).allMatch(m -> m.amount().compareTo(new BigDecimal("10000"))==0);
        assertThat(WarehouseLedgerRules.firstMonth(p)).isEqualTo("2026-11");
        assertThat(WarehouseLedgerRules.lastMonth(p)).isEqualTo("2027-04");
    }
    @Test void unknownRentKeepsUnknownMonthsAndCannotBeConfirmed() {
        var p = WarehouseLedgerRules.normalize(plan("null"), false);
        assertThat(WarehouseLedgerRules.total(p)).isNull();
        assertThat(p.feeItems().getFirst().monthAllocations()).hasSize(6).allMatch(m -> m.amount()==null);
        assertThatThrownBy(() -> WarehouseLedgerRules.normalize(plan("null"), true)).hasMessageContaining("金额");
    }
    @Test void dayAllocationIncludesLeapDayAndConservesRounding() {
        var p = json.readValue("""
            {"schemaVersion":2,"warehouseCode":"WH1","feeItems":[{"feeKey":"rent","feeTypeCode":"RENT","feeAmount":3,"allocationMethodCode":"DAY_PRORATA","serviceFrom":"2028-02-29","serviceTo":"2028-03-02"}]}
            """, WarehouseCostLedger.class);
        var rows = WarehouseLedgerRules.normalize(p, true).feeItems().getFirst().monthAllocations();
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1.00");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("2.00");
    }
    @Test void manualDuplicateMonthsAndNonConservingTotalsAreRejected() {
        String s = """
            {"schemaVersion":2,"warehouseCode":"WH1","feeItems":[{"feeKey":"rent","feeTypeCode":"RENT","feeAmount":10,"allocationMethodCode":"MANUAL","serviceFrom":"2026-11-01","serviceTo":"2026-12-31","monthAllocations":[{"month":"2026-11","amount":5},{"month":"2026-11","amount":5}]}]}
            """;
        assertThatThrownBy(() -> WarehouseLedgerRules.normalize(json.readValue(s,WarehouseCostLedger.class),true)).hasMessageContaining("重复");
        var original = json.readValue(s, WarehouseCostLedger.class);
        var wrong = original.withFees(java.util.List.of(original.feeItems().getFirst().withAmountAndMonths(new BigDecimal("10"), java.util.List.of(
                new WarehouseCostLedger.Month("2026-11", new BigDecimal("5")),
                new WarehouseCostLedger.Month("2026-12", new BigDecimal("4"))))));
        assertThatThrownBy(() -> WarehouseLedgerRules.normalize(wrong,true)).hasMessageContaining("合计");
    }
}

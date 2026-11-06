package com.rigour.analytics.infrastructure.persistence.scope;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.domain.cost.CostAnalysis;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

class CostLedgerPartitionsTest {
    final JsonMapper json = JsonMapper.builder().build();

    Map<String, String> row(String amount, String data) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("id", "08000000-0000-0000-0000-000000000001");
        r.put("amount", amount);
        r.put("data_json", data);
        r.put("cost_date", "2026-11-03");
        r.put("city_code", "C1");
        r.put("category_code", "RENTAL");
        r.put("status_code", "CONFIRMED");
        r.put("revision", "1");
        r.put("premises_type", "WAREHOUSE");
        return r;
    }

    @Test
    void halfYearRentAndOneMonthOperationsAreNotMultipliedAndCountAsOneDocument() {
        var data =
                """
{"warehouseLedger":{"schemaVersion":2,"feeItems":[
{"feeAmount":60000,"monthAllocations":[{"month":"2026-11","amount":10000},{"month":"2026-12","amount":10000},{"month":"2027-01","amount":10000},{"month":"2027-02","amount":10000},{"month":"2027-03","amount":10000},{"month":"2027-04","amount":10000}]},
{"feeAmount":1000,"monthAllocations":[{"month":"2026-11","amount":1000}]}]}}
""";
        var rows = CostLedgerPartitions.expand("ERP", row("61000", data), json);
        assertThat(rows).hasSize(6);
        assertThat(rows.getFirst().get("amount")).isEqualTo("11000");
        assertThat(
                        rows.stream()
                                .map(r -> new BigDecimal(r.get("amount")))
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("61000");
        assertThat(rows.stream().map(r -> r.get("id")).distinct().count()).isEqualTo(6);
        var normalizer = new CostSourceNormalizer(json);
        var facts =
                rows.stream()
                        .map(r -> normalizer.normalize("ERP", r, List.of(), Map.of()))
                        .toList();
        assertThat(CostAnalysis.count(facts)).isEqualTo(1);
        assertThat(facts)
                .allSatisfy(
                        f ->
                                assertThat(f.rootSourceId())
                                        .isEqualTo("08000000-0000-0000-0000-000000000001"));
        assertThat(CostLedgerPartitions.expand("ERP", row("61000", data), json)).isEqualTo(rows);
    }

    @Test
    void realCostsAreAllocatedByCityWhileTraceLinksNeverAddAnotherFee() {
        var r =
                row(
                        "300",
                        """
{"ledger":{"schemaVersion":2,"costMonth":"2026-11","feeItems":[{"feeKey":"F","ourAmount":300}]}}
""");
        r.put(
                "sources_json",
                """
[{"linkRoleCode":"COST","allocations":[{"feeKey":"F","cityCode":"C1","amount":100},{"feeKey":"F","cityCode":"C2","amount":200}]},{"linkRoleCode":"TRACE","allocations":[]}]
""");
        var rows = CostLedgerPartitions.expand("ORDER", r, json);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(v -> v.get("amount")).containsExactly("100", "200");
        r.put("sources_json", r.get("sources_json").replace("200", "300"));
        assertThatThrownBy(() -> CostLedgerPartitions.expand("ORDER", r, json))
                .hasMessageContaining("守恒");
    }

    @Test
    void missingAmountsStayUnknownAndUnrelatedSourcesAreUntouched() {
        var r =
                row(
                        null,
                        """
{"warehouseLedger":{"schemaVersion":2,"feeItems":[{"feeAmount":null,"monthAllocations":[{"month":"2026-11","amount":null}]}]}}
""");
        assertThat(CostLedgerPartitions.expand("ERP", r, json).getFirst())
                .containsEntry("amount", null);
        var payroll = Map.of("data_json", "not a ledger");
        assertThat(CostLedgerPartitions.expand("HR", payroll, json)).containsExactly(payroll);
    }

    @Test
    void dictionaryFeeTypesPartitionAmountsWithoutMultiplyingDocuments() {
        var r =
                row(
                        "310",
                        """
{"warehouseLedger":{"schemaVersion":2,"feeItems":[
  {"feeTypeCode":"RENT","feeAmount":300,"monthAllocations":[{"month":"2026-11","amount":150},{"month":"2026-12","amount":150}]},
  {"feeTypeCode":"ASSEMBLY_NEW","feeAmount":10,"monthAllocations":[{"month":"2026-11","amount":10}]}]}}
""");
        var rows = CostLedgerPartitions.expand("ERP", r, json);
        assertThat(rows).hasSize(3);
        var normalizer = new CostSourceNormalizer(json);
        var facts =
                rows.stream()
                        .map(v -> normalizer.normalize("ERP", v, List.of(), Map.of()))
                        .toList();
        assertThat(CostAnalysis.amount(facts)).isEqualByComparingTo("310");
        assertThat(CostAnalysis.count(facts)).isEqualTo(1);
        assertThat(facts)
                .extracting(v -> v.dimension("feeType").key())
                .contains("dict:COST_WAREHOUSE_FEE_TYPE:ASSEMBLY_NEW");
        assertThat(
                        facts.stream()
                                .filter(v -> v.dimension("feeType").key().endsWith(":ASSEMBLY_NEW"))
                                .findFirst()
                                .orElseThrow()
                                .amount())
                .isEqualByComparingTo("10");
    }

    @Test
    void multipleLogisticsFeesAndTraceOrdersDoNotMultiplyAllocatedTotals() {
        var r =
                row(
                        "320",
                        """
                        {"ledger":{"schemaVersion":2,"costMonth":"2026-11","feeItems":[
                          {"feeKey":"F1","feeTypeCode":"FREIGHT","ourAmount":300},
                          {"feeKey":"F2","feeTypeCode":"UNLOADING_NEW","ourAmount":20}]}}
                        """);
        r.put("payer_code", "COMPANY");
        r.put(
                "sources_json",
                """
[{"linkRoleCode":"COST","allocations":[{"feeKey":"F1","cityCode":"C1","amount":100},{"feeKey":"F2","cityCode":"C1","amount":20}]},
 {"linkRoleCode":"COST","allocations":[{"feeKey":"F1","cityCode":"C2","amount":200}]},
 {"linkRoleCode":"TRACE","allocations":[]}]
""");
        var rows = CostLedgerPartitions.expand("ORDER", r, json);
        var normalizer = new CostSourceNormalizer(json);
        var facts =
                rows.stream()
                        .map(v -> normalizer.normalize("ORDER", v, List.of(), Map.of()))
                        .toList();
        assertThat(rows).hasSize(3);
        assertThat(CostAnalysis.amount(facts)).isEqualByComparingTo("320");
        assertThat(CostAnalysis.count(facts)).isEqualTo(1);
        assertThat(
                        facts.stream()
                                .filter(
                                        v ->
                                                v.dimension("feeType")
                                                        .key()
                                                        .endsWith(":UNLOADING_NEW"))
                                .findFirst()
                                .orElseThrow()
                                .amount())
                .isEqualByComparingTo("20");
    }
}

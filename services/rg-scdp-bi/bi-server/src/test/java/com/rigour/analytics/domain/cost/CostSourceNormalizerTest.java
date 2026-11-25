package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.application.port.out.CostDashboardStore.City;
import com.rigour.analytics.application.service.CostDashboardAccess;
import com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class CostSourceNormalizerTest {
    final CostSourceNormalizer normalizer = new CostSourceNormalizer(JsonMapper.builder().build());
    final List<City> cities =
            List.of(
                    new City("WH", "武汉市", "4201", "10", List.of("1", "10")),
                    new City("CD", "成都市", "5101", "20", List.of("1", "20")));

    Map<String, String> row() {
        return new HashMap<>(
                Map.of(
                        "id",
                        UUID.randomUUID().toString(),
                        "amount",
                        "0.00",
                        "status_code",
                        "CONFIRMED",
                        "data_json",
                        "{}",
                        "cost_date",
                        "2026-10-19",
                        "revision",
                        "1",
                        "category_code",
                        "RENTAL",
                        "premises_type",
                        "OFFICE",
                        "city_code",
                        "4201"));
    }

    @Test
    void legacyActiveTransportIsExcludedUntilCompletenessProjectionRefreshes() {
        var data = row();
        data.put("status_code", "ACTIVE");
        data.put("payer_code", "COMPANY");
        var fact = normalizer.normalize("ORDER", data, cities, Map.of());
        assertThat(fact).isNotNull();
        assertThat(fact.status()).isEqualTo("DRAFT");
        assertThat(fact.confirmed()).isFalse();
        data.put("status_code", "CONFIRMED");
        assertThat(normalizer.normalize("ORDER", data, cities, Map.of()).confirmed()).isTrue();
        data.put("status_code", "ACTIVE");
        assertThatThrownBy(() -> normalizer.normalize("ERP", data, cities, Map.of()))
                .hasMessageContaining("状态无效");
        data.put("status_code", "VOID");
        assertThat(normalizer.normalize("ORDER", data, cities, Map.of()).confirmed()).isFalse();
    }

    @Test
    void automaticProcurementUsesReceivingWarehouseAndPreservesWarehouseRestriction() {
        var row = row();
        row.put("category_code", "PURCHASE");
        row.put("warehouse_id", "42");
        row.put("data_json", "{\"_procurementOrderId\":101}");
        var fact = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(fact.warehouseId()).isEqualTo("42");
        assertThat(fact.sourceWarehouseId()).isEqualTo("42");
        assertThat(
                        CostDashboardAccess.sourceVisible(
                                warehousePolicy("INVENTORY", "42"), fact, "actor"))
                .isTrue();
        assertThat(
                        CostDashboardAccess.sourceVisible(
                                warehousePolicy("INVENTORY", "43"), fact, "actor"))
                .isFalse();
        assertThat(CostDashboardAccess.dashboardVisible(warehousePolicy("ANALYTICS", "42"), fact))
                .isTrue();
        assertThat(CostDashboardAccess.dashboardVisible(warehousePolicy("ANALYTICS", "43"), fact))
                .isFalse();
        row.put("source_warehouse_id", "44");
        var twoWarehouses = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(twoWarehouses.sourceWarehouseId()).isEqualTo("44");
        assertThat(
                        CostDashboardAccess.sourceVisible(
                                warehousePolicy("INVENTORY", "42"), twoWarehouses, "actor"))
                .isFalse();
        row.remove("source_warehouse_id");
        row.put("data_json", "{}");
        assertThat(normalizer.normalize("ERP", row, cities, Map.of()).sourceWarehouseId()).isNull();
    }

    private SupplyAuthorizationView warehousePolicy(String object, String warehouse) {
        var all = new Limit("ALL", List.of());
        var warehouses = new Limit("SPECIFIED", List.of(warehouse));
        return new SupplyAuthorizationView(
                "ACTIVE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                1,
                1,
                1,
                1,
                Set.of(),
                "read",
                true,
                List.of(
                        new Clause(
                                UUID.randomUUID(),
                                object,
                                "WAREHOUSE",
                                all,
                                all,
                                warehouses,
                                false)),
                all,
                warehouses);
    }

    @Test
    void procurementFreightBelongsToPurchaseAndHistoricalQuotesAreReferenceOnly() {
        var row = row();
        row.put("payer_code", "COMPANY");
        row.put("amount", "60.00");
        row.put("data_json", "{\"logistics\":{\"legCode\":\"PURCHASE\"}}");
        var manual = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(manual.category()).isEqualTo("purchase");
        assertThat(manual.amount()).isEqualByComparingTo("60");
        row.put(
                "data_json",
                "{\"ledger\":{\"transportPurposeCode\":\"PURCHASE\",\"sourceSync\":{\"originBizKey\":\"ERP:PROCUREMENT_ORDER:PO1\"}}}");
        var quote = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(quote.category()).isEqualTo("purchase");
        assertThat(quote.dimension("costTreatment").key()).isEqualTo("REFERENCE");
        row.put("category_code", "PURCHASE");
        row.put("type_code", "PROCUREMENT_FREIGHT");
        row.put(
                "data_json",
                "{\"typeCode\":\"PROCUREMENT_FREIGHT\",\"goods\":{\"purchaseOrderId\":1}}");
        var actual = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(actual.category()).isEqualTo("purchase");
        assertThat(actual.confirmed()).isTrue();
        assertThat(actual.dimension("costTreatment").key()).isNotEqualTo("REFERENCE");
    }

    @Test
    void payrollPreservesPaymentRegistrationStatusWithoutChangingConfirmedCost() {
        for (String payment : List.of("PAID", "UNPAID")) {
            var row = row();
            row.put("category_code", "PAYROLL");
            row.put("payment_status_code", payment);
            row.put("components_json", "{}");
            row.put("cost_date", "2026-09-30");
            row.put("amount", "5000.03");
            var fact = normalizer.normalize("HR", row, cities, Map.of());
            assertThat(fact.payment()).isEqualTo(payment);
            assertThat(fact.confirmed()).isTrue();
            assertThat(fact.amount()).isEqualByComparingTo("5000.03");
            assertThat(fact.category()).isEqualTo("labor");
        }
    }

    @Test
    void unsupportedOrNonOrderTransportPaymentValuesRemainUnknown() {
        var row = row();
        row.put("payer_code", "COMPANY");
        row.put("payment_status_code", "PARTIAL_PAID");
        assertThat(normalizer.normalize("ERP", row, cities, Map.of()).payment())
                .isEqualTo("UNKNOWN");
        row.put("payment_status_code", "invalid");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of()).payment())
                .isEqualTo("UNKNOWN");
        row.remove("payment_status_code");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of()).payment())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void courierReimbursementKeepsShippingDetailsAndTrackingNumberAsText() {
        var row = row();
        row.put("category_code", "EXPENSE");
        row.put("type_code", "COURIER");
        row.put("amount", "18.00");
        row.put(
                "data_json",
                """
                {"expense":{"courier":{"contents":"合同与资料","recipientName":"合作方业务部",
                "companyName":"快递公司","trackingNo":"0001234567"}}}
                """);
        var fact = normalizer.normalize("HR", row, cities, Map.of());
        assertThat(fact.category()).isEqualTo("expense");
        assertThat(fact.amount()).isEqualByComparingTo("18.00");
        assertThat(fact.dimension("courier_contents").label()).isEqualTo("合同与资料");
        assertThat(fact.dimension("courier_recipientName").label()).isEqualTo("合作方业务部");
        assertThat(fact.dimension("courier_companyName").label()).isEqualTo("快递公司");
        assertThat(fact.dimension("courier_trackingNo").label()).isEqualTo("0001234567");
    }

    @Test
    void preservesOperationalDimensionsWithoutMultiplyingTheExpense() {
        var row = row();
        row.put("category_code", "LOSS");
        row.put("warehouse_id", "W1");
        row.put("warehouse_name", "城市仓");
        row.put("amount", "178.00");
        var goods = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(goods.dimension("warehouse").key()).isEqualTo("warehouse:W1");
        assertThat(goods.dimension("warehouse").label()).isEqualTo("城市仓");
        assertThat(goods.amount()).isEqualByComparingTo("178.00");
        row.put("payer_code", "COMPANY");
        row.put(
                "data_json",
                """
                {"logistics":{"senderName":"中心仓","receiverName":"武汉仓","legCode":"TRANSFER"}}
                """);
        var freight = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(freight.dimension("route").label()).isEqualTo("中心仓 → 武汉仓");
        row.put("category_code", "EXPENSE");
        row.put("type_code", "TRAVEL");
        row.put(
                "data_json",
                """
                {"expense":{"tripReference":"武汉客户拜访","destination":"武汉","payeeName":"铁路公司"}}
                """);
        var expense = normalizer.normalize("HR", row, cities, Map.of());
        assertThat(expense.dimension("trip").label()).isEqualTo("武汉客户拜访");
        assertThat(expense.dimension("destination").label()).isEqualTo("武汉");
        assertThat(expense.dimension("payee").label()).isEqualTo("铁路公司");
        assertThat(expense.category()).isEqualTo("expense");
        assertThat(expense.amount()).isEqualByComparingTo("178.00");
    }

    @Test
    void mapsCityAndKeepsZeroDistinctFromMissingAndNeverAddsDeposit() {
        var row = row();
        row.put("data_json", "{\"rental\":{\"deposit\":10000},\"remark\":\"测试\"}");
        var fact = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(fact.city()).isEqualTo("WH");
        assertThat(fact.amount()).isZero();
        assertThat(fact.confirmed()).isTrue();
        assertThat(fact.category()).isEqualTo("office");
        assertThat(fact.dashboardDepartment()).isEqualTo("10");
        row.remove("amount");
        fact = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(fact.amount()).isNull();
        assertThat(fact.confirmed()).isFalse();
    }

    @Test
    void conflictingOrUnknownCityIsRetainedWithoutGuessedOwnership() {
        var row = row();
        row.put("department_id", "20");
        var fact = normalizer.normalize("ERP", row, cities, Map.of("20", List.of("1", "20")));
        assertThat(fact.city()).isEqualTo("UNKNOWN");
        assertThat(fact.mappingState()).isEqualTo("CONFLICT");
        row.remove("department_id");
        row.put("city_code", "unmapped");
        fact = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(fact.city()).isEqualTo("UNKNOWN");
        assertThat(fact.dashboardDepartment()).isNull();
    }

    @Test
    void dedicatedHeadquartersCodeIsNeverGuessedFromEmployeeOrCityName() {
        var row = row();
        row.put("city_code", "COST_HEADQUARTERS");
        row.put("department_id", "10");
        row.put("amount", "320.00");
        var fact = normalizer.normalize("HR", row, cities, Map.of("10", List.of("1", "10")));
        assertThat(fact.city()).isEqualTo("COST_HEADQUARTERS");
        assertThat(fact.cityName()).isEqualTo("总部");
        assertThat(fact.mappingState()).isEqualTo("HEADQUARTERS");
        assertThat(fact.rawCity()).isEqualTo("COST_HEADQUARTERS");
        assertThat(fact.confirmed()).isTrue();
        row.put("city_code", "old-province");
        var historical = normalizer.normalize("HR", row, cities, Map.of("10", List.of("1", "10")));
        assertThat(historical.city()).isEqualTo("UNKNOWN");
        assertThat(historical.rawCity()).isEqualTo("old-province");
        assertThat(historical.amount()).isEqualByComparingTo("320.00");
        assertThat(historical.confirmed()).isTrue();
    }

    @Test
    void headquartersChoiceDoesNotPretendToBeAnOperatingDepartment() {
        var result = normalizer.cities(List.of(), List.of());
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().code()).isEqualTo("COST_HEADQUARTERS");
        assertThat(result.getFirst().departmentId()).isNull();
        assertThatThrownBy(
                        () ->
                                normalizer.cities(
                                        List.of(),
                                        List.of(Map.of("area_code", "COST_HEADQUARTERS"))))
                .hasMessageContaining("保留编码");
    }

    @Test
    void
            actualCityCatalogIncludingHeadquartersNormalizesOperationalCostWithImmutableDepartmentPath() {
        var catalog = actualCityCatalog();
        assertThat(catalog).extracting(City::code).contains("WH", "COST_HEADQUARTERS");
        var row = row();
        row.put("department_id", "10");
        var fact = normalizer.normalize("ERP", row, catalog, Map.of("10", List.of("1", "10")));
        assertThat(fact.city()).isEqualTo("WH");
        assertThat(fact.mappingState()).isEqualTo("CITY_CODE");
        assertThat(fact.dashboardDepartment()).isEqualTo("10");
    }

    @Test
    void actualCityCatalogIncludingHeadquartersNormalizesPayrollWithImmutableDepartmentPath() {
        var row = row();
        row.put("category_code", "PAYROLL");
        row.put("city_code", "WH");
        row.put("department_id", "10");
        row.put("components_json", "{\"baseSalary\":8000}");
        row.put("amount", "8000.00");
        var fact =
                normalizer.normalize(
                        "HR", row, actualCityCatalog(), Map.of("10", List.of("1", "10")));
        assertThat(fact.city()).isEqualTo("WH");
        assertThat(fact.category()).isEqualTo("labor");
        assertThat(fact.components().get("baseSalary")).isEqualByComparingTo("8000");
        assertThat(fact.confirmed()).isTrue();
    }

    private List<City> actualCityCatalog() {
        return normalizer.cities(
                List.of(
                        Map.of(
                                "id",
                                "1",
                                "department_code",
                                "SALES",
                                "department_name",
                                "销售部",
                                "status_code",
                                "ACTIVE",
                                "deleted",
                                "0",
                                "department_path",
                                "[\"1\"]"),
                        Map.of(
                                "id",
                                "10",
                                "department_code",
                                "WH",
                                "department_name",
                                "武汉市",
                                "status_code",
                                "ACTIVE",
                                "deleted",
                                "0",
                                "department_path",
                                "[\"1\",\"10\"]")),
                List.of(
                        Map.of(
                                "area_code",
                                "4201",
                                "area_name",
                                "武汉市",
                                "status",
                                "ACTIVE",
                                "deleted",
                                "0")));
    }

    @Test
    void nonCompanyFreightIsExcludedAndBadAmountsBlockPublication() {
        var row = row();
        row.put("payer_code", "CUSTOMER");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        row.put("payer_code", "COMPANY");
        row.put("amount", "-0.01");
        assertThatThrownBy(() -> normalizer.normalize("ORDER", row, cities, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pendingDhbFreightWithoutPayerCanBeCompletedButNeverCountsAsExpense() {
        var row = row();
        row.put("source_system", "DHB");
        row.put("status_code", "DRAFT");
        row.put("data_json", "{\"_transportMissing\":\"AMOUNT,ORDER\"}");
        row.remove("amount");
        row.remove("payer_code");

        var pending = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(pending.status()).isEqualTo("DRAFT");
        assertThat(pending.amount()).isNull();
        assertThat(pending.dimension("transportMissing").key()).isEqualTo("AMOUNT,ORDER");

        row.put("payer_code", "CUSTOMER");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();

        row.remove("payer_code");
        row.put("status_code", "CONFIRMED");
        row.put("amount", "35.00");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
    }

    @Test
    void explicitCompanyLedgerOverridesMissingAndStaleLegacyPayerForBothSyncSources() {
        var row = row();
        row.put("category_code", "LOGISTICS");
        row.put("amount", "14.21");
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"costMonth":"2026-10","feeItems":[
                  {"bearerCode":"COMPANY","feeAmount":14.21,"ourAmount":14.21}]}}
                """);
        for (String source : List.of("DHB", "HUOLALA")) {
            row.put("source_system", source);
            for (String payer : Arrays.asList(null, "", "CUSTOMER", "SUPPLIER")) {
                row.put("payer_code", payer);
                var fact = normalizer.normalize("ORDER", row, cities, Map.of());
                assertThat(fact).as("source=%s payer=%s", source, payer).isNotNull();
                assertThat(fact.confirmed()).isTrue();
                assertThat(fact.amount()).isEqualByComparingTo("14.21");
                assertThat(row.get("payer_code")).isEqualTo(payer);
            }
        }
    }

    @Test
    void sharedLedgerUsesCompanyShareAndExternalLedgersDoNotBecomeCompanyCost() {
        var row = row();
        row.put("amount", "30.00");
        row.put("payer_code", "CUSTOMER");
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"feeItems":[
                  {"bearerCode":"SHARED","feeAmount":100,"ourAmount":30}]}}
                """);
        var shared = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(shared).isNotNull();
        assertThat(shared.amount()).isEqualByComparingTo("30.00");
        assertThat(shared.confirmed()).isTrue();

        row.put("source_system", "DHB");
        row.put("amount", "0.00");
        for (String bearer : List.of("CUSTOMER", "SUPPLIER")) {
            row.put("data_json", """
                    {"ledger":{"schemaVersion":2,"feeItems":[
                      {"bearerCode":"%s","feeAmount":100,"ourAmount":0}]}}
                    """.formatted(bearer));
            for (String status : List.of("CONFIRMED", "DRAFT")) {
                row.put("status_code", status);
                for (String payer : Arrays.asList(null, "COMPANY")) {
                    row.put("payer_code", payer);
                    assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
                }
            }
        }
    }

    @Test
    void zeroSelfDeliveryCountsWhileUnknownUnfinishedAndVoidedAmountsRemainExcluded() {
        var row = row();
        row.put("source_system", "DHB");
        row.remove("payer_code");
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"deliveryModeCode":"SELF","feeItems":[
                  {"bearerCode":"COMPANY","feeAmount":0,"ourAmount":0,
                   "zeroCostReason":"立即送无快递单号"}]}}
                """);
        var zero = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(zero).isNotNull();
        assertThat(zero.confirmed()).isTrue();
        assertThat(zero.amount()).isZero();

        for (String status : List.of("DRAFT", "VOID", "ACTIVE")) {
            row.put("status_code", status);
            assertThat(normalizer.normalize("ORDER", row, cities, Map.of()).confirmed()).isFalse();
        }
        row.put("status_code", "DRAFT");
        row.remove("amount");
        row.put("data_json", """
                {"_transportMissing":"AMOUNT","ledger":{"schemaVersion":2,"feeItems":[
                  {"bearerCode":"COMPANY","feeAmount":null,"ourAmount":null}]}}
                """);
        for (String source : List.of("DHB", "HUOLALA")) {
            row.put("source_system", source);
            var pending = normalizer.normalize("ORDER", row, cities, Map.of());
            assertThat(pending).isNotNull();
            assertThat(pending.amount()).isNull();
            assertThat(pending.confirmed()).isFalse();
            assertThat(pending.dimension("transportMissing").key()).isEqualTo("AMOUNT");
        }
    }

    @Test
    void ledgerWithoutExplicitBearersKeepsLegacyPayerCompatibility() {
        var row = row();
        row.put("amount", "30.00");
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"feeItems":[{"ourAmount":30}]}}
                """);
        row.remove("payer_code");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        row.put("payer_code", "COMPANY");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of()).amount())
                .isEqualByComparingTo("30.00");
        row.put("payer_code", "CUSTOMER");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
    }

    @Test
    void legacySnapshotCompanyPayerRescuesMissingColumnWithoutRewritingSavedAmounts() {
        var row = row();
        row.put("amount", "35.00");
        row.put("data_json", """
                {"amount":35,"logistics":{"legCode":"DELIVERY","payerCode":"COMPANY"}}
                """);
        for (String source : List.of("HUOLALA", "DHB", "MANUAL_ENTRY")) {
            row.put("source_system", source);
            for (String payer : Arrays.asList(null, "", " ")) {
                row.put("payer_code", payer);
                var fact = normalizer.normalize("ORDER", row, cities, Map.of());
                assertThat(fact).as("source=%s payer=%s", source, payer).isNotNull();
                assertThat(fact.confirmed()).isTrue();
                assertThat(fact.amount()).isEqualByComparingTo("35.00");
                assertThat(row.get("payer_code")).isEqualTo(payer);
            }
        }
        row.put("payer_code", "CUSTOMER");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
    }

    @Test
    void completeLegacyHuolalaUsesExistingSyncDefaultWhenBothPayersAreMissing() {
        var row = row();
        row.put("source_system", "HUOLALA");
        row.put("amount", "35.00");
        row.put("data_json", """
                {"amount":35,"ledger":null,"logistics":{"legCode":"DELIVERY","carrierName":"货拉拉"}}
                """);
        for (String payer : Arrays.asList(null, "", " ")) {
            row.put("payer_code", payer);
            var fact = normalizer.normalize("ORDER", row, cities, Map.of());
            assertThat(fact).isNotNull();
            assertThat(fact.confirmed()).isTrue();
            assertThat(fact.amount()).isEqualByComparingTo("35.00");
            assertThat(row.get("payer_code")).isEqualTo(payer);
        }
        row.put("amount", "0.00");
        row.put("data_json", "{\"amount\":0,\"logistics\":{\"legCode\":\"DELIVERY\"}}");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of()).amount()).isZero();
    }

    @Test
    void legacyHuolalaDefaultCannotOverrideExplicitExternalPayersOrOtherSourceDefaults() {
        var row = row();
        row.put("source_system", "HUOLALA");
        row.put("amount", "35.00");
        for (String payer : List.of("CUSTOMER", "SUPPLIER", "OTHER", "SHARED")) {
            row.put("payer_code", payer);
            row.put("data_json", "{}");
            assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
            row.remove("payer_code");
            row.put("data_json", "{\"logistics\":{\"payerCode\":\"" + payer + "\"}}");
            assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        }
        row.put("data_json", "{}");
        for (String source : List.of("DHB", "MANUAL_ENTRY", "NONE")) {
            row.put("source_system", source);
            assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        }
    }

    @Test
    void legacyHuolalaDefaultNeverPromotesUnknownUnconfirmedOrIncompleteModernRecords() {
        var row = row();
        row.put("source_system", "HUOLALA");
        row.put("amount", "35.00");
        for (String status : List.of("DRAFT", "ACTIVE", "VOID")) {
            row.put("status_code", status);
            var fact = normalizer.normalize("ORDER", row, cities, Map.of());
            assertThat(fact == null || !fact.confirmed()).isTrue();
        }
        row.put("status_code", "CONFIRMED");
        row.remove("amount");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        row.put("amount", "35.00");
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"feeItems":[{"ourAmount":35}]}}
                """);
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
        row.put("data_json", """
                {"ledger":{"schemaVersion":2,"feeItems":[
                  {"bearerCode":"CUSTOMER","feeAmount":100,"ourAmount":0}]}}
                """);
        row.put("amount", "0.00");
        assertThat(normalizer.normalize("ORDER", row, cities, Map.of())).isNull();
    }

    @Test
    void sourceLinksNeverMultiplyCostRowsOrExposeOtherDocumentNumbers() {
        var row = row();
        row.put("category_code", "PURCHASE");
        row.put(
                "sources_json",
                "[{\"documentNo\":\"private-other-document\"},{\"documentNo\":\"second\"}]");
        row.put("data_json", "{\"goods\":{\"externalDocument\":{\"documentNo\":\"MANUAL-1\"}}}");
        var fact = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(fact.originalNumbers()).containsExactly("MANUAL-1");
    }

    @Test
    void restrictedScopeRejectsMissingOwnershipWithoutNullPointerOrTenantWideFallback() {
        var row = row();
        row.remove("city_code");
        var fact = normalizer.normalize("ERP", row, cities, Map.of());
        var all =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "ALL", List.of());
        var none =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "NONE", List.of());
        var dept =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "SPECIFIED", List.of("10"));
        var view =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView(
                        "ACTIVE",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        1,
                        1,
                        1,
                        1,
                        Set.of(),
                        "read",
                        true,
                        List.of(
                                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView
                                        .Clause(
                                        UUID.randomUUID(),
                                        "ANALYTICS",
                                        "DEPARTMENT",
                                        dept,
                                        none,
                                        all,
                                        true)),
                        all,
                        all);
        assertThat(
                        com.rigour.analytics.application.service.CostDashboardAccess
                                .dashboardVisible(view, fact))
                .isFalse();
    }

    @Test
    void reimbursementsStaySeparateFromPayrollFreightAndPremises() {
        var row = row();
        for (String type :
                List.of(
                        "TRAVEL",
                        "TAXI",
                        "ACCOMMODATION",
                        "OFFICE_SUPPLIES",
                        "FREIGHT",
                        "CUSTOM_UNKNOWN")) {
            row.put("type_code", type);
            var fact = normalizer.normalize("HR", row, cities, Map.of());
            assertThat(fact.category()).isEqualTo("expense");
            assertThat(fact.source()).isEqualTo("HR");
            assertThat(fact.sourceId()).isEqualTo(row.get("id"));
            assertThat(fact.dimensions().get("type").key())
                    .isEqualTo("code:REIMBURSEMENT_EXPENSE_TYPE:" + type);
        }
    }

    @Test
    void newDictionaryCodesAndModernRoutesArePreservedWithoutEnumeratingTheirValues() {
        var row = row();
        row.put("payer_code", "COMPANY");
        row.put(
                "data_json",
                """
                {"_ledgerFeeType":"HANDLING_NEW","ledger":{
                  "transportPurposeCode":"DELIVERY","deliveryModeCode":"THIRD_PARTY",
                  "transportMethodCode":"RAIL_NEW","carrierCode":"CARRIER_NEW",
                  "sender":{"displayName":"武汉仓"},
                  "receivingPoints":[{"displayName":"门店甲"},{"manualName":"门店乙"}]}}
                """);
        var fact = normalizer.normalize("ORDER", row, cities, Map.of());
        assertThat(fact.dimension("leg").key()).isEqualTo("dict:COST_TRANSPORT_PURPOSE:DELIVERY");
        assertThat(fact.dimension("method").key()).isEqualTo("dict:COST_TRANSPORT_KIND:RAIL_NEW");
        assertThat(fact.dimension("carrier").key()).isEqualTo("dict:COST_CARRIER:CARRIER_NEW");
        assertThat(fact.dimension("feeType").key())
                .isEqualTo("dict:COST_LOGISTICS_FEE_TYPE:HANDLING_NEW");
        assertThat(fact.dimension("route").label()).isEqualTo("武汉仓 → 门店甲、门店乙");
    }

    @Test
    void procurementGoodsAndActualFreightShareOrderIdentityAndKeepSeparateComponents() {
        var row = row();
        row.put("category_code", "PURCHASE");
        row.put("data_json", "{\"_procurementOrderId\":42,\"_procurementOrderCode\":\"PO42\"}");
        var goods = normalizer.normalize("ERP", row, cities, Map.of());
        row.put("id", UUID.randomUUID().toString());
        row.put("type_code", "PROCUREMENT_FREIGHT");
        row.put("data_json", "{\"goods\":{\"purchaseOrderId\":42}}");
        var freight = normalizer.normalize("ERP", row, cities, Map.of());
        assertThat(goods.documentKey()).isEqualTo(freight.documentKey());
        assertThat(goods.dimension("component").key()).isEqualTo("GOODS");
        assertThat(freight.dimension("component").key()).isEqualTo("FREIGHT");
    }
}

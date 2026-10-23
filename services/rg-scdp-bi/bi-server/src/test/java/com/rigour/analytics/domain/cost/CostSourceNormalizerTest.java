package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;

import com.rigour.analytics.application.port.out.CostDashboardStore.City;
import com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer;

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
    void courierReimbursementKeepsShippingDetailsAndTrackingNumberAsText() {
        var row = row();
        row.put("category_code", "EXPENSE");
        row.put("type_code", "COURIER");
        row.put("amount", "18.00");
        row.put("data_json", """
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
}

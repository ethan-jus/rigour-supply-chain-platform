package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrProductCommissionRuleTest {
    final String tenant = UUID.randomUUID().toString();
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final JsonMapper json = JsonMapper.builder().build();
    final HrPayrollService service =
            new HrPayrollService(store, source, mock(ConfiguredPayrollService.class));

    @BeforeEach
    void setup() {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.fromString(tenant),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:payroll-rule:write", "hr:payroll-rule:read")));
        when(source.requireProduct(eq(tenant), eq(1L), anyLong(), eq("SET")))
                .thenAnswer(
                        i ->
                                new ErpPayrollProductView(
                                        1L,
                                        i.getArgument(2),
                                        "P1",
                                        "SKU",
                                        "澳洋华彩",
                                        "A600",
                                        "SET",
                                        2L,
                                        "台呢"));
        when(store.saveRule(anyString(), any(), any(), anyString()))
                .thenAnswer(i -> new PayrollRuleView("R", 2, i.getArgument(2), Instant.EPOCH));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PayrollRuleCommand command(Long variant, String role, String from, String to) {
        return new PayrollRuleCommand(
                UUID.randomUUID().toString(),
                1,
                "自定义提成名称",
                "COMMISSION",
                null,
                List.of(10L),
                null,
                null,
                true,
                1L,
                variant,
                "客户端错误名称",
                "SET",
                null,
                role,
                "QUANTITY",
                new BigDecimal("25"),
                null,
                LocalDate.parse(from),
                LocalDate.parse(to),
                true,
                "调整标准",
                null,
                null,
                null,
                2L,
                "客户端错误分类");
    }

    @Test
    void canonicalProductCategoryAndModelAreSavedWithEditableRateWithoutTemplate() {
        var result = service.saveRule(null, command(2L, "SALES", "2026-10-01", "9999-12-31"));
        assertThat(result.data().name()).isEqualTo("自定义提成名称");
        assertThat(result.data().productName()).isEqualTo("澳洋华彩 · A600");
        assertThat(result.data().categoryName()).isEqualTo("台呢");
        assertThat(result.data().rate()).isEqualByComparingTo("25");
        assertThat(result.data().groupCode()).isNull();
    }

    @Test
    void sameModelRoleDepartmentCannotOverlapButOtherModelRoleOrDateCan() {
        when(store.rules(tenant))
                .thenReturn(
                        List.of(
                                new PayrollRuleView(
                                        "OLD",
                                        1,
                                        command(2L, "SALES", "2026-01-01", "2026-09-30"),
                                        Instant.EPOCH)));
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null, command(2L, "SALES", "2026-09-30", "2026-12-31")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重叠");
        assertThatCode(
                        () ->
                                service.saveRule(
                                        null, command(2L, "SALES", "2026-10-01", "9999-12-31")))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                service.saveRule(
                                        null, command(3L, "SALES", "2026-01-01", "2026-12-31")))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                service.saveRule(
                                        null,
                                        command(2L, "CITY_MANAGER", "2026-01-01", "2026-12-31")))
                .doesNotThrowAnyException();
    }

    @Test
    void hrCanEditExistingRuleWithoutRewritingConfirmedPayroll() {
        var command = command(2L, "SALES", "2026-01-01", "2026-12-31");
        when(store.rules(tenant))
                .thenReturn(List.of(new PayrollRuleView("R", 1, command, Instant.EPOCH)));
        service.saveRule("R", command);
        verify(store).saveRule(eq(tenant), eq("R"), any(), anyString());
        verify(store, never())
                .saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
        verify(store, never())
                .transition(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void catalogMismatchRejectsSave() {
        when(source.requireProduct(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(
                        new ErpPayrollProductView(
                                1L, 2L, "P", "V", "台呢", "A600", "SET", 3L, "其他分类"));
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null, command(2L, "SALES", "2026-01-01", "2026-12-31")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类已变化");
        verify(store, never()).saveRule(anyString(), any(), any(), anyString());
    }

    @Test
    void legacyListGetsCategoryBySkuWithoutWritingRuleOrChangingStoredName() {
        var original =
                command(2L, "SALES", "2026-01-01", "2026-12-31").withProduct("原商品名", null, null);
        when(store.rules(tenant))
                .thenReturn(List.of(new PayrollRuleView("R", 1, original, Instant.EPOCH)));
        when(source.productsByVariants(tenant, List.of(2L)))
                .thenReturn(
                        List.of(
                                new ErpPayrollProductView(
                                        1L, 2L, "P", "V", "新商品名", "A600", "SET", 2L, "台呢")));
        var result = service.rules(0, 20, "COMMISSION", "").items().getFirst();
        assertThat(result.data().categoryName()).isEqualTo("台呢");
        assertThat(result.data().productName()).isEqualTo("原商品名");
        assertThat(original.categoryId()).isNull();
        verify(store, never()).saveRule(anyString(), any(), any(), anyString());
    }

    PayrollRuleCommand city(PayrollCityCommissionPolicy policy) {
        when(store.employee(anyString(), eq("M1"), anyString()))
                .thenReturn(
                        new PayrollEmployeeView(
                                "M1",
                                "城市总",
                                10L,
                                "销售部",
                                "石家庄",
                                LocalDate.of(2025, 1, 1),
                                null,
                                "SALES"));
        return new PayrollRuleCommand(
                UUID.randomUUID().toString(),
                null,
                "城市总提成",
                "CITY_MANAGER",
                "M1",
                List.of(),
                "C1",
                "石家庄",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(9999, 12, 31),
                true,
                "",
                null,
                null,
                null,
                null,
                null,
                policy);
    }

    PayrollCityProductRule cityProduct(Long category, String method, String rate) {
        return new PayrollCityProductRule(
                1L, 2L, "不可信名称", "SET", category, "不可信分类", method, new BigDecimal(rate));
    }

    @Test
    void cityPolicyNormalizesRealSkuAndRejectsDuplicateModelsInvalidRatesAndCategoryChanges() {
        var product = cityProduct(2L, "QUANTITY", "25");
        var saved =
                service.saveRule(
                        null,
                        city(
                                new PayrollCityCommissionPolicy(
                                        new BigDecimal("50"), List.of(product))));
        assertThat(saved.data().cityCommission().products())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.productName()).isEqualTo("澳洋华彩 · A600");
                            assertThat(p.categoryName()).isEqualTo("台呢");
                            assertThat(p.rate()).isEqualByComparingTo("25");
                        });
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        city(
                                                new PayrollCityCommissionPolicy(
                                                        new BigDecimal("101"), List.of()))))
                .hasMessageContaining("100%");
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        city(
                                                new PayrollCityCommissionPolicy(
                                                        new BigDecimal("-1"), List.of()))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        city(
                                                new PayrollCityCommissionPolicy(
                                                        BigDecimal.TEN,
                                                        List.of(product, product)))))
                .hasMessageContaining("重复");
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        city(
                                                new PayrollCityCommissionPolicy(
                                                        BigDecimal.TEN,
                                                        List.of(
                                                                cityProduct(
                                                                        3L, "QUANTITY", "25"))))))
                .hasMessageContaining("分类已变化");
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        city(
                                                new PayrollCityCommissionPolicy(
                                                        BigDecimal.TEN,
                                                        List.of(
                                                                cityProduct(
                                                                        2L, "PERCENT", "101"))))))
                .hasMessageContaining("100%");
        verify(store, times(1)).saveRule(anyString(), any(), any(), anyString());
    }

    @Test
    void cityBatchRequiresWritePermissionAndLegacyJsonDefaultsToUnconfiguredPolicy() {
        var legacy = json.readTree(json.writeValueAsString(city(null)));
        ((tools.jackson.databind.node.ObjectNode) legacy).remove("cityCommission");
        assertThat(json.treeToValue(legacy, PayrollRuleCommand.class).cityCommission()).isNull();
        TestAuthorizationContext.clear();
        assertThatThrownBy(() -> service.createCityManagers(List.of(city(null))))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never()).saveRule(anyString(), any(), any(), anyString());
    }
}

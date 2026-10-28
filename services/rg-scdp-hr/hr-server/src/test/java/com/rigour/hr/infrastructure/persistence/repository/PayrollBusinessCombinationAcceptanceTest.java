package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.application.service.*;
import com.rigour.order.api.v1.model.OrderPayrollLineView;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Business combinations against actual H2/MyBatis transactions; not MySQL migration evidence. */
class PayrollBusinessCombinationAcceptanceTest {
    final PayrollPersistenceWorkflowTest database = new PayrollPersistenceWorkflowTest();
    final JsonMapper json = JsonMapper.builder().build();
    final UUID tenantId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final String tenant = tenantId.toString();
    HrPayrollSource source;
    HrPayrollSchemeStore schemes;
    PayrollRuleResolver resolver;
    HrPayrollService service;
    HrPayrollWorkflowService workflow;
    PayrollEmployeeView employee;

    @BeforeEach
    void setup() throws Exception {
        database.setup();
        permissions("hr:payroll:read", "hr:payroll:calculate", "hr:payroll:confirm");
        employee = new PayrollEmployeeView("E1", "李四", 1L, "总部", null,
                LocalDate.of(2026, 1, 1), null, "HEADQUARTERS");
        doAnswer(invocation -> List.of(employee)).when(database.references).employees(any());
        doReturn(List.of(1L)).when(database.references).departmentsAt(anyString(), anyString(), any(), any());
        doReturn(null).when(database.references).salarySource(anyString(), anyString());
        source = mock(HrPayrollSource.class);
        schemes = mock(HrPayrollSchemeStore.class);
        resolver = mock(PayrollRuleResolver.class);
        when(schemes.facts(eq(tenant), eq("E1"), anyString()))
                .thenReturn(new PayrollSchemeFactsView(0, null, List.of(), ""));
        when(source.productsByVariants(eq(tenant), anyList())).thenReturn(List.of(
                new ErpPayrollProductView(1L, 1L, "P1", "SKU1", "商品", "规格", "BOX", 10L, "方便面")));
        when(source.productCategories(tenant)).thenReturn(List.of(new ErpPayrollCategoryView(10L, null, "方便面", 0)));
        var commission = new ConfiguredCommissionService(database.payroll, source);
        var attendance = mock(HrAttendanceStore.class);
        when(attendance.employee(anyString(), any(), anyString(), anyString())).thenReturn(new HrAttendanceStore.Employee(
                "E1", "李四", 1L, "总部", employee.entryDate(), null, null,
                new AttendanceRow("E1", "李四", "总部", 22, "DEPARTMENT", new BigDecimal("22"), BigDecimal.ZERO,
                        22, null, null, "NONE", "CONFIRMED", "AVAILABLE", List.of(), "", 1)));
        when(attendance.employee(anyString(), any(), anyString())).thenAnswer(invocation ->
                attendance.employee(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), "hr:payroll:read"));
        var gate = new PayrollAttendanceGate(attendance, mock(HrPayrollComponentLifecycleStore.class));
        var configured = new ConfiguredPayrollService(database.payroll, schemes, source,
                attendance, commission, json);
        ReflectionTestUtils.setField(configured, "rules", resolver);
        var transactionProxy = new ProxyFactory(new HrPayrollService(database.payroll, source, configured));
        transactionProxy.setProxyTargetClass(true);
        transactionProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database.dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (HrPayrollService) transactionProxy.getProxy();
        ReflectionTestUtils.setField(service, "attendanceGate", gate);
        workflow = new HrPayrollWorkflowService(database.payroll, service,
                mock(HrEmployeePayrollProfileStore.class));
        ReflectionTestUtils.setField(workflow, "attendanceGate", gate);
    }

    @AfterEach
    void close() throws Exception {
        database.closeSchemaConnection();
    }

    void permissions(String... permissions) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", userId, tenantId, userId, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(permissions)));
    }

    PayrollSchemeVersionView policy(String role, String proration, boolean salaryChange, boolean commission) {
        employee = new PayrollEmployeeView("E1", "李四", 1L, "部门", null,
                LocalDate.of(2026, 1, 1), null, role);
        ObjectNode definition = json.valueToTree(PayrollSchemeTestFixture.definition());
        definition.put("name", "业务组合验收").put("role", role).put("performanceMode", "FIXED");
        definition.set("baseSalary", json.valueToTree(
                new PayrollSchemeBaseSalary("FIXED", new BigDecimal("6000"), List.of(), null)));
        definition.put("performanceBase", new BigDecimal("1000"));
        definition.set("allowances", json.createArrayNode());
        definition.set("proration", json.valueToTree(new PayrollProrationPolicy(proration, proration, "NONE")));
        // A plain Monday-Friday calendar makes the independent September day-count oracle explicit.
        ((ObjectNode) definition.get("calendar")).set("alternateWeekdays", json.createArrayNode());
        ((ObjectNode) definition.get("calendar")).putNull("alternateAnchor");
        if (!commission) ((ObjectNode) definition.get("commission")).set("rules", json.createArrayNode());
        if (salaryChange) definition.set("personalPolicy", json.valueToTree(new PayrollPersonalPolicy(
                List.of(), null, null, null, null, LocalDate.of(2026, 9, 15),
                new BigDecimal("9000"), new BigDecimal("1600"))));
        var version = PayrollSchemeTestFixture.version(json.treeToValue(definition, PayrollSchemeDefinition.class));
        when(schemes.versions(tenant)).thenReturn(List.of(version));
        when(resolver.resolve(eq(tenant), any(), any(), any())).thenReturn(new PayrollRuleMatch(version, List.of()));
        when(schemes.facts(eq(tenant), eq("E1"), anyString())).thenReturn(new PayrollSchemeFactsView(
                0, null, List.of(), "当月绩效核定", new BigDecimal("80")));
        return version;
    }

    @ParameterizedTest(name = "{0} {1}: salary={2}, performance={3}")
    @CsvSource({
        "HEADQUARTERS,CALENDAR_DAYS,7600,1056",
        "SALES,CALENDAR_DAYS,7600,1320",
        "CITY_MANAGER,CALENDAR_DAYS,7600,1320",
        "HEADQUARTERS,WORK_DAYS,7636.36,1061.82",
        "SALES,WORK_DAYS,7636.36,1327.27",
        "CITY_MANAGER,WORK_DAYS,7636.36,1327.27"
    })
    void explicitFixedSalaryChangeUsesConfiguredDayBasisAndFreezesAmounts(
            String role, String proration, String expectedBase, String expectedPerformance) {
        policy(role, proration, true, false);
        var result = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        assertThat(result.statusCode()).isEqualTo("READY");
        assertThat(result.calculation().issues()).isEmpty();
        // Sep 1-14 / Sep 15-30: 14/16 calendar days or 10/12 weekdays; no policy inferred from role.
        assertThat(result.calculation().baseSalary()).isEqualByComparingTo(expectedBase);
        assertThat(result.calculation().performance()).isEqualByComparingTo(expectedPerformance);
        var confirmed = service.confirm(result.id(), new PayrollActionCommand(result.revision(), "按明确的日折算规则核对"));
        assertThat(confirmed.calculation().scheme().scheme().definition().personalPolicy().salaryChangeDate())
                .isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(confirmed.calculation().baseSalary()).isEqualByComparingTo(expectedBase);
    }

    @Test
    void departmentChangeBlocksCalculationUntilExplicitMonthlyDepartmentIsSavedAndStaleSaveCannotOverwriteIt() {
        policy("HEADQUARTERS", "CALENDAR_DAYS", true, false);
        doAnswer(invocation -> {
            LocalDateTime from = invocation.getArgument(2);
            return List.of(from.plusHours(8).getDayOfMonth() < 15 ? 1L : 2L);
        }).when(database.references).departmentsAt(anyString(), anyString(), any(), any());
        var pending = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        assertThat(pending.statusCode()).isEqualTo("REVIEW");
        assertThat(pending.calculation().issues()).anyMatch(issue -> issue.contains("月内部门发生变化"));
        assertThatThrownBy(() -> service.confirm(pending.id(), new PayrollActionCommand(pending.revision(), "直接确认")))
                .isInstanceOf(BusinessException.class);
        var input = database.payroll.saveInput(tenant, "E1", "2026-09",
                new PayrollInputCommand(0, null, null, BigDecimal.ZERO, "核定9月统一适用总部薪资", null, 2L), userId.toString());
        assertThat(input.departmentOverrideId()).isEqualTo(2L);
        assertThatThrownBy(() -> database.payroll.saveInput(tenant, "E1", "2026-09",
                new PayrollInputCommand(0, null, null, BigDecimal.ZERO, "过期窗口", null, 1L), userId.toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(database.payroll.input(tenant, "E1", "2026-09").departmentOverrideId()).isEqualTo(2L);
        var ready = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        assertThat(ready.calculation().issues()).isEmpty();
        assertThat(ready.calculation().baseSalary()).isEqualByComparingTo("7600");
        assertThat(service.confirm(ready.id(), new PayrollActionCommand(ready.revision(), "核对部门与月中调薪"))
                .statusCode()).isEqualTo("CONFIRMED");
    }

    OrderPayrollLineView order(String refund) {
        return new OrderPayrollLineView("LINE1", "O1", "SO1", "E1", "WH", 1L, 1L, 1L,
                "商品", "BOX", LocalDate.of(2026, 9, 2), BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"), LocalDate.of(2026, 9, 5), new BigDecimal(refund), null, "COMPLETE");
    }

    @Test
    void fullPaymentThenRefundKeepsPaidSnapshotAndManualCrossMonthClawbackIsIdempotent() throws Exception {
        policy("SALES", "CALENDAR_DAYS", false, true);
        when(source.payrollLines(eq(tenant), eq("E1"), isNull(), any())).thenReturn(List.of(order("0")));
        var september = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        assertThat(september.calculation().issues()).isEmpty();
        assertThat(september.calculation().commission()).isEqualByComparingTo("40");
        var confirmed = service.confirm(september.id(), new PayrollActionCommand(september.revision(), "全额回款商品行提成核对"));
        var paid = database.payroll.pay(tenant, confirmed.id(), new PayrollPaymentCommand(
                UUID.randomUUID().toString(), confirmed.revision(), LocalDate.of(2026, 10, 1),
                confirmed.calculation().netPay(), "BANK-SEP", "已实际发放登记"), userId.toString());
        assertThat(database.payroll.released(tenant)).containsExactly("LINE1|SALES");
        when(source.payrollLines(eq(tenant), eq("E1"), isNull(), any())).thenReturn(List.of(order("1000")));
        var changed = workflow.preview(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        assertThat(changed.sourceChanged()).isTrue();
        assertThat(changed.calculation().issues()).anyMatch(issue -> issue.contains("退款"));
        assertThatThrownBy(() -> workflow.reopen(paid.id(), new PayrollActionCommand(paid.revision(), "退货后撤回")))
                .isInstanceOf(BusinessException.class);
        var command = new PayrollCorrectionCommand(UUID.randomUUID().toString(), paid.revision(),
                "2026-10", new BigDecimal("-40"), "SO1全额退货退款，按原人原4%提成冲回");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<PayrollCorrectionView> submit = () -> {
                permissions("hr:payroll:read", "hr:payroll:confirm");
                try {
                    return workflow.correct(paid.id(), command);
                } finally {
                    TestAuthorizationContext.clear();
                }
            };
            var retries = executor.invokeAll(List.of(submit, submit));
            assertThat(retries.getFirst().get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(retries.getLast().get(10, TimeUnit.SECONDS).id());
        }
        assertThatThrownBy(() -> workflow.correct(paid.id(), new PayrollCorrectionCommand(command.requestId(),
                paid.revision(), "2026-10", new BigDecimal("-50"), command.reason())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("请求编号已使用");
        for (int attempt = 0; attempt < 2; attempt++) {
            var october = workflow.preview(new PayrollCalculateCommand("2026-10", List.of("E1"))).getFirst().calculation();
            // An open target month is preview-only; it must not introduce business-data issues.
            assertThat(october.issues()).allMatch(issue -> issue.equals("核算月份尚未结束，当前结果仅供试算"));
            assertThat(october.commission()).isZero();
            assertThat(october.correctionAmount()).isEqualByComparingTo("-40");
            assertThat(october.corrections()).hasSize(1);
            assertThat(october.netPay()).isEqualByComparingTo("6960");
        }
        var unchanged = database.payroll.detail(tenant, paid.id(), "hr:payroll:read");
        assertThat(unchanged.statusCode()).isEqualTo("PAID");
        assertThat(unchanged.revision()).isEqualTo(paid.revision());
        assertThat(unchanged.calculation()).isEqualTo(paid.calculation());
        assertThat(database.payroll.released(tenant)).containsExactly("LINE1|SALES");
    }

    @Test
    void concurrentConfirmationOfOneRevisionClaimsCommissionAndTransitionsExactlyOnce() throws Exception {
        policy("SALES", "CALENDAR_DAYS", false, true);
        when(source.payrollLines(eq(tenant), eq("E1"), isNull(), any())).thenReturn(List.of(order("0")));
        var calculated = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> confirm = () -> {
                permissions("hr:payroll:read", "hr:payroll:calculate", "hr:payroll:confirm");
                try {
                    return service.confirm(calculated.id(),
                            new PayrollActionCommand(calculated.revision(), "同时确认同一份工资")).statusCode();
                } catch (BusinessException conflict) {
                    return "CONFLICT";
                } finally {
                    TestAuthorizationContext.clear();
                }
            };
            var attempts = executor.invokeAll(List.of(confirm, confirm));
            assertThat(List.of(attempts.getFirst().get(10, TimeUnit.SECONDS),
                    attempts.getLast().get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
        }
        var saved = database.payroll.detail(tenant, calculated.id(), "hr:payroll:read");
        assertThat(saved.statusCode()).isEqualTo("CONFIRMED");
        assertThat(saved.revision()).isEqualTo(calculated.revision() + 1);
        assertThat(database.payroll.released(tenant)).containsExactly("LINE1|SALES");
    }

    @Test
    void readOnlyPrincipalCannotCalculateConfirmOrCorrectPayroll() {
        policy("HEADQUARTERS", "CALENDAR_DAYS", false, false);
        var calculated = service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
        permissions("hr:payroll:read");
        assertThatThrownBy(() -> service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.confirm(calculated.id(), new PayrollActionCommand(calculated.revision(), "只读角色")))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> workflow.correct(calculated.id(), new PayrollCorrectionCommand(
                UUID.randomUUID().toString(), calculated.revision(), "2026-10", BigDecimal.TEN, "只读角色")))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(database.payroll.detail(tenant, calculated.id(), "hr:payroll:read").statusCode()).isEqualTo("READY");
    }
}

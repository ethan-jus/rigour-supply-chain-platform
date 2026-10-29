package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.model.PayrollQuery;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollEntity;
import com.rigour.hr.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

class PayrollSummaryTest {
    final HrPayrollMapper mapper = mock(HrPayrollMapper.class);
    final HrDataScope scope = mock(HrDataScope.class);
    final MybatisPlusHrPayrollRepository repository =
            new MybatisPlusHrPayrollRepository(
                    mapper,
                    mock(HrPayrollRuleMapper.class),
                    mock(HrPayrollReferenceMapper.class),
                    mock(HrPayrollPersistenceSupport.class),
                    JsonMapper.builder().build(),
                    scope,
                    mock(HrPayrollPaymentMapper.class),
                    mock(HrPayrollCorrectionMapper.class),
                    mock(HrPayrollCommissionReleaseMapper.class));

    @Test
    void paymentRestrictionProjectionKeepsOriginalCreationFacts() {
        var created = java.time.Instant.parse("2026-09-01T00:00:00Z");
        var updated = java.time.Instant.parse("2026-10-01T00:00:00Z");
        var record = new com.rigour.hr.api.v1.model.PayrollDetailView("P", "2026-09", "E", "员工甲", 1L,
                "部门", null, "READY", 2, null, updated, List.of(), List.of(), null, "creator", "创建人甲", created);
        var projected = record.withPaymentBlockReason("待复核");
        assertThat(projected.createdBy()).isEqualTo("creator");
        assertThat(projected.createdByName()).isEqualTo("创建人甲");
        assertThat(projected.createdTime()).isEqualTo(created);
        assertThat(projected.updatedTime()).isEqualTo(updated);
    }

    void fixture() {
        when(scope.apply(any(), eq("hr:payroll:read")))
                .thenAnswer(
                        invocation -> {
                            QueryWrapper<HrPayrollEntity> query = invocation.getArgument(0);
                            return query.in("department_id", List.of(6L, 7L));
                        });
        when(mapper.selectMaps(any()))
                .thenReturn(
                        List.of(
                                group("REVIEW", 2, 0, "0", "0"),
                                group("READY", 41, 40, "10000.10", "9900.10"),
                                group("PAID", 1, 1, "0.20", "0.20")));
    }

    @Test
    void fullMonthTotalsPreserveScopeAndSeparateIncompleteRecordsFromZero() {
        fixture();
        var result =
                repository.summary(
                        "tenant-a",
                        new PayrollQuery(20, 20, "2026-09", "许", 6L, null),
                        "hr:payroll:read");
        assertThat(result.total()).isEqualTo(44);
        assertThat(result.completeCount()).isEqualTo(41);
        assertThat(result.pendingCount()).isEqualTo(3);
        assertThat(result.grossPay()).isEqualByComparingTo("10000.30");
        assertThat(result.netPay()).isEqualByComparingTo("9900.30");
        var captured = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectMaps(captured.capture());
        var query = captured.getValue();
        assertThat(query.getSqlSegment())
                .contains(
                        "tenant_id",
                        "department_id IN",
                        "payroll_month",
                        "employee_code LIKE",
                        "employee_name LIKE",
                        "GROUP BY status_code")
                .doesNotContain("LIMIT", "OFFSET");
        assertThat(query.getParamNameValuePairs().values())
                .contains("tenant-a", "2026-09", "%许%", 6L, 7L);
        assertThat(query.getSqlSelect())
                .contains("JSON_TYPE", "DECIMAL(20,2)", "'READY','CONFIRMED','PAID'");
    }

    @Test
    void stageFiltersAmountsButCountsStillCoverOtherStages() {
        fixture();
        var result =
                repository.summary(
                        "tenant-a",
                        new PayrollQuery(0, 1, "2026-09", null, null, "READY"),
                        "hr:payroll:read");
        assertThat(result.total()).isEqualTo(41);
        assertThat(result.completeCount()).isEqualTo(40);
        assertThat(result.pendingCount()).isEqualTo(1);
        assertThat(result.netPay()).isEqualByComparingTo("9900.10");
        assertThat(result.statusCounts()).containsEntry("REVIEW", 2L).containsEntry("PAID", 1L);
    }

    @Test
    void categoryFilterUsesHistoricalSnapshotAndBoundValueForListSummaryAndExport() {
        fixture();
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());
        var criteria = new PayrollQuery(0, 20, "2026-09", null, 6L, null, "CITY_MANAGER");
        repository.payroll("tenant-a", criteria, "hr:payroll:read");
        repository.summary("tenant-a", criteria, "hr:payroll:read");
        repository.export("tenant-a", criteria, "hr:payroll:read");
        var list = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, times(2)).selectList(list.capture());
        var summary = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectMaps(summary.capture());
        var queries = new java.util.ArrayList<QueryWrapper>(list.getAllValues());
        queries.add(summary.getValue());
        for (var query : queries) {
            assertThat(query.getSqlSegment())
                    .contains("$.scheme.scheme.definition.role", "$.salarySource.payrollCategory", "tenant_id", "department_id")
                    .doesNotContain("CITY_MANAGER");
            assertThat(query.getParamNameValuePairs().values()).contains("CITY_MANAGER", "tenant-a", 6L);
        }
    }

    @Test
    void independentEmployeeFiltersApplyTogetherToListTotalsAndExport() {
        fixture();
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());
        var criteria = new PayrollQuery(0, 20, "2026-09", null, 6L, "READY", null,
                " 许 ", " EMP2026 ");
        repository.payroll("tenant-a", criteria, "hr:payroll:read");
        repository.summary("tenant-a", criteria, "hr:payroll:read");
        repository.export("tenant-a", criteria, "hr:payroll:read");
        var list = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, times(2)).selectList(list.capture());
        var totals = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectMaps(totals.capture());
        var queries = new java.util.ArrayList<QueryWrapper>(list.getAllValues());
        queries.add(totals.getValue());
        for (var query : queries) {
            assertThat(query.getSqlSegment()).contains("employee_name LIKE", "employee_code LIKE", "tenant_id", "department_id IN").doesNotContain(" OR ");
            assertThat(query.getParamNameValuePairs().values()).contains("%许%", "%EMP2026%", "tenant-a", 6L, 7L);
        }
    }

    @Test
    void emptyScopeDoesNotInventEmployeesOrAmounts() {
        fixture();
        when(mapper.selectMaps(any())).thenReturn(List.of());
        var result =
                repository.summary(
                        "tenant-a",
                        new PayrollQuery(0, 20, "2026-09", null, null, null),
                        "hr:payroll:read");
        assertThat(result.total()).isZero();
        assertThat(result.completeCount()).isZero();
        assertThat(result.statusCounts()).isEmpty();
        assertThat(result.netPay()).isZero();
    }

    private Map<String, Object> group(
            String stage, long total, long complete, String gross, String net) {
        return Map.of(
                "stage",
                stage,
                "total",
                total,
                "complete_count",
                complete,
                "gross_pay",
                new BigDecimal(gross),
                "net_pay",
                new BigDecimal(net));
    }
}

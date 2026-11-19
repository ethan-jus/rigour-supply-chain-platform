package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class LogisticsDepartmentRefreshServiceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final java.time.LocalDate date = java.time.LocalDate.parse("2026-11-16");

    OrderLogisticsReferences.DepartmentAt key(String code) {
        return new OrderLogisticsReferences.DepartmentAt(code, date);
    }

    LogisticsCostCommand data() {
        return json.readValue(
                """
                {"costDate":"2026-11-16","amount":80,"paymentStatusCode":"PAID","remark":"人工备注","ledger":{"schemaVersion":2,
                "sources":[{"orderCode":"SO1","employeeCode":"EMP1","employeeName":"李林","linkRoleCode":"COST",
                "allocations":[{"feeKey":"freight","amount":80,"basis":1}],"linkedAt":"2026-11-16T11:43:40Z"}],
                "manualBillItems":[{"name":"运费","amount":80}]}}
                """,
                LogisticsCostCommand.class);
    }

    @Test
    void fillsOnlyMissingDepartmentAndPreservesMoneyLinkTimeAndPayment() {
        var old = data();
        var next =
                LogisticsDepartmentRefreshService.fill(
                        old.ledger(),
                        date,
                        Map.of(
                                key("EMP1"),
                                new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市")));
        assertThat(next.sources().getFirst().departmentId()).isEqualTo(6L);
        assertThat(next.sources().getFirst().departmentName()).isEqualTo("北京市");
        assertThat(next.sources().getFirst().allocations())
                .isEqualTo(old.ledger().sources().getFirst().allocations());
        assertThat(next.sources().getFirst().linkedAt())
                .isEqualTo(old.ledger().sources().getFirst().linkedAt());
        assertThat(next.manualBillItems()).isEqualTo(old.ledger().manualBillItems());
        assertThat(
                        LogisticsDepartmentRefreshService.fill(
                                next,
                                date,
                                Map.of(
                                        key("EMP1"),
                                        new LogisticsCostEmployeeView("EMP1", "李林", 7L, "新部门"))))
                .isEqualTo(next);
        assertThat(old.withLedger(next, old.amount()).amount()).isEqualByComparingTo("80");
        assertThat(old.withLedger(next, old.amount()).remark()).isEqualTo("人工备注");
        assertThat(old.withLedger(next, old.amount()).paymentStatusCode()).isEqualTo("PAID");
    }

    @Test
    void sameEmployeeAcrossOrdersUsesEachOriginalDocumentDateRatherThanTransportDate() {
        var old = data().ledger();
        var source = old.sources().getFirst();
        var september = java.time.LocalDate.parse("2026-09-10");
        var october = java.time.LocalDate.parse("2026-10-10");
        var first =
                new LogisticsCostLedger.Source(
                        source.orderTypeCode(),
                        "SO1",
                        source.linkRoleCode(),
                        source.allocations(),
                        source.noSourceReason(),
                        source.employeeCode(),
                        source.employeeName(),
                        null,
                        null,
                        source.sourceWarehouseCode(),
                        source.documentAmount(),
                        september,
                        source.linkedAt());
        var second =
                new LogisticsCostLedger.Source(
                        source.orderTypeCode(),
                        "SO2",
                        source.linkRoleCode(),
                        source.allocations(),
                        source.noSourceReason(),
                        source.employeeCode(),
                        source.employeeName(),
                        null,
                        null,
                        source.sourceWarehouseCode(),
                        source.documentAmount(),
                        october,
                        source.linkedAt());
        var next =
                LogisticsDepartmentRefreshService.fill(
                        old.withSources(List.of(first, second)),
                        date,
                        Map.of(
                                new OrderLogisticsReferences.DepartmentAt("EMP1", september),
                                new LogisticsCostEmployeeView("EMP1", "李林", 6L, "原部门"),
                                new OrderLogisticsReferences.DepartmentAt("EMP1", october),
                                new LogisticsCostEmployeeView("EMP1", "李林", 7L, "调岗后部门"),
                                key("EMP1"),
                                new LogisticsCostEmployeeView("EMP1", "李林", 8L, "当前部门")));
        assertThat(next.sources())
                .extracting(LogisticsCostLedger.Source::departmentId)
                .containsExactly(6L, 7L);
        assertThat(next.sources())
                .extracting(LogisticsCostLedger.Source::documentDate)
                .containsExactly(september, october);
        assertThat(next.sources())
                .allSatisfy(
                        s -> {
                            assertThat(s.allocations()).isEqualTo(source.allocations());
                            assertThat(s.linkedAt()).isEqualTo(source.linkedAt());
                        });
    }

    @Test
    void doesNotGuessFromEmployeeNameOrReplaceConflictingHistoricalDepartment() {
        var old = data().ledger();
        assertThat(
                        LogisticsDepartmentRefreshService.fill(
                                old,
                                date,
                                Map.of(
                                        key("OTHER"),
                                        new LogisticsCostEmployeeView("OTHER", "李林", 6L, "北京市"))))
                .isEqualTo(old);
        var source = old.sources().getFirst();
        var partial =
                new LogisticsCostLedger.Source(
                        source.orderTypeCode(),
                        source.orderCode(),
                        source.linkRoleCode(),
                        source.allocations(),
                        null,
                        "EMP1",
                        "李林",
                        9L,
                        null,
                        null,
                        null,
                        null,
                        source.linkedAt());
        assertThat(
                        LogisticsDepartmentRefreshService.fill(
                                        old.withSources(List.of(partial)),
                                        date,
                                        Map.of(
                                                key("EMP1"),
                                                new LogisticsCostEmployeeView(
                                                        "EMP1", "李林", 6L, "北京市")))
                                .sources()
                                .getFirst())
                .isEqualTo(partial);
    }

    @Test
    void automaticReviewUsesTenantBoundIdentityAndOriginalRevisionWithoutCreatingCost() {
        var store = mock(OrderLogisticsCostStore.class);
        var refs = mock(OrderLogisticsReferences.class);
        var service = new LogisticsDepartmentRefreshService(store, refs);
        var tenant = UUID.randomUUID().toString();
        when(store.departmentTenants()).thenReturn(List.of(tenant));
        when(store.missingDepartments(tenant, ""))
                .thenReturn(
                        List.of(new OrderLogisticsCostStore.DepartmentCandidate("C1", 4, data())));
        when(refs.employeeDepartmentsAt(
                        any(), eq(Set.of(key("EMP1"))), eq("order:logistics-cost:sync-source")))
                .thenAnswer(
                        inv -> {
                            var caller =
                                    (com.rigour.shared.context.CallerIdentity) inv.getArgument(0);
                            assertThat(caller.tenantId().toString()).isEqualTo(tenant);
                            assertThat(caller.principalScope()).isEqualTo("SERVICE");
                            assertThat(caller.userId()).isNull();
                            return Map.of(
                                    key("EMP1"),
                                    new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市"));
                        });
        service.refresh();
        verify(store).fillDepartments(eq(tenant), eq("C1"), eq(4), anyMap());
        verify(store).missingDepartments(tenant, "C1");
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unresolvedAttributionDoesNotFillAndUnavailableTenantDoesNotStopOthers() {
        var store = mock(OrderLogisticsCostStore.class);
        var refs = mock(OrderLogisticsReferences.class);
        var first = UUID.randomUUID().toString();
        var next = UUID.randomUUID().toString();
        when(store.departmentTenants()).thenReturn(List.of(first, next));
        when(store.missingDepartments(first, ""))
                .thenThrow(new IllegalStateException("unavailable"));
        when(store.missingDepartments(next, ""))
                .thenReturn(
                        List.of(new OrderLogisticsCostStore.DepartmentCandidate("C1", 1, data())));
        when(refs.employeeDepartmentsAt(any(), anySet(), anyString())).thenReturn(Map.of());
        new LogisticsDepartmentRefreshService(store, refs).refresh();
        verify(store, never()).fillDepartments(any(), any(), anyInt(), anyMap());
        verify(store).missingDepartments(next, "C1");
    }
}

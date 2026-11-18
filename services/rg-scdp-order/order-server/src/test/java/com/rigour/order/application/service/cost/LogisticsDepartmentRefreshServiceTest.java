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

    LogisticsCostCommand data() {
        return json.readValue(
                """
                {"amount":80,"paymentStatusCode":"PAID","remark":"人工备注","ledger":{"schemaVersion":2,
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
                        Map.of("EMP1", new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市")));
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
                                Map.of(
                                        "EMP1",
                                        new LogisticsCostEmployeeView("EMP1", "李林", 7L, "新部门"))))
                .isEqualTo(next);
        assertThat(old.withLedger(next, old.amount()).amount()).isEqualByComparingTo("80");
        assertThat(old.withLedger(next, old.amount()).remark()).isEqualTo("人工备注");
        assertThat(old.withLedger(next, old.amount()).paymentStatusCode()).isEqualTo("PAID");
    }

    @Test
    void doesNotGuessFromEmployeeNameOrReplaceConflictingHistoricalDepartment() {
        var old = data().ledger();
        assertThat(
                        LogisticsDepartmentRefreshService.fill(
                                old,
                                Map.of(
                                        "OTHER",
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
                                        Map.of(
                                                "EMP1",
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
        when(refs.employeeDepartments(
                        any(), eq(Set.of("EMP1")), eq("order:logistics-cost:sync-source")))
                .thenAnswer(
                        inv -> {
                            var caller =
                                    (com.rigour.shared.context.CallerIdentity) inv.getArgument(0);
                            assertThat(caller.tenantId().toString()).isEqualTo(tenant);
                            assertThat(caller.principalScope()).isEqualTo("SERVICE");
                            assertThat(caller.userId()).isNull();
                            return List.of(new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市"));
                        });
        service.refresh();
        verify(store).fillDepartments(eq(tenant), eq("C1"), eq(4), anyMap());
        verify(store).missingDepartments(tenant, "C1");
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ambiguousIdentityDoesNotFillAndUnavailableTenantDoesNotStopOthers() {
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
        when(refs.employeeDepartments(any(), anySet(), anyString()))
                .thenReturn(
                        List.of(
                                new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市"),
                                new LogisticsCostEmployeeView("EMP1", "李林", 9L, "其他部门")));
        new LogisticsDepartmentRefreshService(store, refs).refresh();
        verify(store, never()).fillDepartments(any(), any(), anyInt(), anyMap());
        verify(store).missingDepartments(next, "C1");
    }
}

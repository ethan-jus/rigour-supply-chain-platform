package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class LogisticsEmployeeDepartmentTest {
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final OrderLedgerSourceStore source = mock(OrderLedgerSourceStore.class);
    final OrderLogisticsLedgerService service = new OrderLogisticsLedgerService(refs, source);
    final JsonMapper json = JsonMapper.builder().build();
    final UUID tenant = UUID.randomUUID();
    final String read = "order:logistics-cost:read";

    @BeforeEach
    void setup() {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(read)));
        when(refs.cities(read)).thenReturn(Map.of("WH", "武汉"));
        when(refs.employeeDepartmentsAt(any(), anySet(), eq(read)))
                .thenReturn(
                        Map.of(
                                new OrderLogisticsReferences.DepartmentAt(
                                        "EMP1", java.time.LocalDate.parse("2026-11-10")),
                                new LogisticsCostEmployeeView("EMP1", "同名员工", 10L, "武汉销售部")));
    }

    @AfterEach
    void close() {
        TestAuthorizationContext.clear();
    }

    OrderDeliveryCostSourceView order(String code, String employee, String department) {
        return json.readValue(
                """
                {"id":1,"documentDate":"2026-11-10","documentNo":"%s","cityCode":"WH","employeeCode":%s,"employeeName":"同名员工",
                "departmentId":%s,"departmentName":%s,"contactName":"收货人","contactPhone":"13000000000",
                "destinationAddress":"收货地址","customerCode":"C1"}
                """
                        .formatted(
                                code,
                                employee == null ? "null" : "\"" + employee + "\"",
                                department == null ? "null" : "20",
                                department == null ? "null" : "\"历史部门\""),
                OrderDeliveryCostSourceView.class);
    }

    @Test
    void historicalPreviewDoesNotLoadCityCatalogButWriteStillValidatesIt() {
        var nos = List.of("SO1");
        when(source.salesByNos(tenant.toString(), nos, read, true))
                .thenReturn(List.of(order("SO1", "EMP1", "old")));
        assertThat(service.salesSources(nos, read, true)).hasSize(1);
        verify(refs, never()).cities(anyString());
        verify(refs, never()).employeeDepartmentsAt(any(), anySet(), anyString());
        when(source.salesByNos(tenant.toString(), nos, read, false))
                .thenReturn(List.of(order("SO1", "EMP1", "old")));
        service.salesSources(nos, read, false);
        verify(refs).cities(read);
    }

    @Test
    void batchFillsMissingDepartmentsByEmployeeCodeAndKeepsHistoricalAttribution() {
        var rows =
                List.of(
                        order("SO1", "EMP1", null),
                        order("SO2", "EMP1", null),
                        order("SO3", "EMP1", "old"),
                        order("SO4", "UNKNOWN", null),
                        order("SO5", null, null));
        var nos = rows.stream().map(OrderDeliveryCostSourceView::documentNo).toList();
        when(source.salesByNos(tenant.toString(), nos, read, false)).thenReturn(rows);
        var result = service.salesSources(nos, read, false);
        assertThat(result.get(0).departmentId()).isEqualTo(10L);
        assertThat(result.get(1).departmentName()).isEqualTo("武汉销售部");
        assertThat(result.get(2).departmentId()).isEqualTo(20L);
        assertThat(result.get(2).departmentName()).isEqualTo("历史部门");
        assertThat(result.get(3).departmentId()).isNull();
        assertThat(result.get(4).departmentId()).isNull();
        verify(refs, times(1)).employeeDepartmentsAt(any(), anySet(), eq(read));
    }

    @Test
    void departmentFilterSuppliesExactMemberCodesBeforeDatabasePagination() {
        var query =
                new LogisticsLedgerSourceQuery(
                        "SALES_ORDER",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        false,
                        false,
                        null,
                        "武汉销售");
        when(source.sales(tenant.toString(), query, read, null, Set.of("EMP1")))
                .thenReturn(List.of(order("SO1", "EMP1", null)));
        when(refs.employees(read))
                .thenReturn(List.of(new LogisticsCostEmployeeView("EMP1", "同名员工", 10L, "武汉销售部")));
        var result = service.sources(query, read);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().departmentName()).isEqualTo("武汉销售部");
        verify(source).sales(tenant.toString(), query, read, null, Set.of("EMP1"));
        verify(refs, times(1)).employees(read);
    }

    @Test
    void unresolvedHistoricalDepartmentCannotBeGuessedFromEmployeeName() {
        when(refs.employeeDepartmentsAt(any(), anySet(), eq(read))).thenReturn(Map.of());
        when(source.salesByNos(tenant.toString(), List.of("SO1"), read, false))
                .thenReturn(List.of(order("SO1", "EMP1", null)));
        assertThat(service.salesSources(List.of("SO1"), read, false).getFirst().departmentId())
                .isNull();
    }

    @Test
    void automaticSourceSyncUsesMinimalIdentityInsteadOfTenantEmployeeDirectory() {
        var principal = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "SERVICE",
                        principal,
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(read)));
        when(source.salesByNos(tenant.toString(), List.of("SO1"), read, false))
                .thenReturn(List.of(order("SO1", "EMP1", null)));
        assertThat(service.salesSources(List.of("SO1"), read, false).getFirst().departmentId())
                .isEqualTo(10L);
        verify(refs)
                .employeeDepartmentsAt(
                        any(),
                        eq(
                                Set.of(
                                        new OrderLogisticsReferences.DepartmentAt(
                                                "EMP1", java.time.LocalDate.parse("2026-11-10")))),
                        eq(read));
        verify(refs, never()).employees(anyString());
    }
}

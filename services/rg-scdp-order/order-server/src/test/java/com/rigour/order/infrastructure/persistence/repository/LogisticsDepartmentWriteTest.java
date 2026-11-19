package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;
import com.rigour.order.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

class LogisticsDepartmentWriteTest {
    final OrderLogisticsCostMapper mapper = mock(OrderLogisticsCostMapper.class);
    final OrderLogisticsCostAuditMapper audit = mock(OrderLogisticsCostAuditMapper.class);
    final JsonMapper json = JsonMapper.builder().build();
    final MybatisPlusOrderLogisticsCostRepository repository =
            new MybatisPlusOrderLogisticsCostRepository(
                    mapper,
                    audit,
                    json,
                    mock(LogisticsWarehouseDataScope.class),
                    mock(OrderLogisticsReferences.class));
    final String tenant = UUID.randomUUID().toString();
    final Map<OrderLogisticsReferences.DepartmentAt, LogisticsCostEmployeeView> employee =
            Map.of(
                    new OrderLogisticsReferences.DepartmentAt(
                            "EMP1", java.time.LocalDate.parse("2026-11-16")),
                    new LogisticsCostEmployeeView("EMP1", "李林", 6L, "北京市"));

    @Test
    void staleRevisionOrVoidCannotUpdateMoneyOrAdvanceVersion() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertThat(repository.fillDepartments(tenant, "C1", 3, employee)).isFalse();
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("tenant_id", "id", "revision", "status_code", "deleted");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains(tenant, "C1", 3, "ACTIVE", 0);
        verify(mapper, never()).update(any(), any(UpdateWrapper.class));
        verify(mapper, never()).advanceAnalyticsVersion(any());
        verifyNoInteractions(audit);
    }

    @Test
    void patchOnlyAttributionWithTenantGuardAndAuditNeverRepricesManualBill() {
        var row = new OrderLogisticsCostEntity();
        row.setId("C1");
        row.setTenantId(tenant);
        row.setRevision(3);
        row.setStatusCode("ACTIVE");
        row.setDataJson(
                """
                {"costDate":"2026-11-16","amount":80,"paymentStatusCode":"PAID","remark":"人工备注","ledger":{"schemaVersion":2,
                "sources":[{"orderCode":"SO1","employeeCode":"EMP1","employeeName":"李林","linkRoleCode":"COST",
                "allocations":[{"feeKey":"freight","amount":80}]}]}}
                """);
        row.setCreatedTime(LocalDateTime.now());
        row.setSourcesJson(
                json.writeValueAsString(
                        json.readValue(row.getDataJson(), LogisticsCostCommand.class)
                                .ledger()
                                .sources()));
        row.setUpdatedTime(LocalDateTime.now());
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(row);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        assertThat(repository.fillDepartments(tenant, "C1", 3, employee)).isTrue();
        var update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet())
                .doesNotContain(
                        "amount=", "amount_manually_edited", "payment_status_code", "cost_date");
        var next = json.readValue(row.getDataJson(), LogisticsCostCommand.class);
        assertThat(next.amount()).isEqualByComparingTo("80");
        assertThat(next.paymentStatusCode()).isEqualTo("PAID");
        assertThat(next.remark()).isEqualTo("人工备注");
        assertThat(next.ledger().sources().getFirst().departmentId()).isEqualTo(6L);
        var order = inOrder(mapper);
        order.verify(mapper).ensureGuard(tenant);
        order.verify(mapper).lock(tenant);
        order.verify(mapper).selectOne(any(QueryWrapper.class));
        order.verify(mapper).update(isNull(), any(UpdateWrapper.class));
        order.verify(mapper).advanceAnalyticsVersion(tenant);
        verify(audit)
                .insert(
                        any(
                                com.rigour.order.infrastructure.persistence.entity
                                        .OrderLogisticsCostAuditEntity.class));
    }
}

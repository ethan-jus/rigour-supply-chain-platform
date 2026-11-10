package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostAuditEntity;
import com.rigour.order.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class HuolalaCostConfirmationTest {
    ObjectMapper json = new ObjectMapper();
    OrderLogisticsCostMapper mapper = mock(OrderLogisticsCostMapper.class);
    OrderLogisticsCostAuditMapper audits = mock(OrderLogisticsCostAuditMapper.class);
    MybatisPlusOrderLogisticsCostRepository repository;
    CarrierOrder excel =
            new CarrierOrder(
                    "12345",
                    "3624071813921329726",
                    "已完成",
                    "2026-11-09",
                    new BigDecimal("40.50"),
                    Map.of(),
                    List.of(),
                    2);

    @BeforeEach
    void setup() {
        repository =
                spy(
                        new MybatisPlusOrderLogisticsCostRepository(
                                mapper, audits, json, null, mock(OrderLogisticsReferences.class)));
        var data =
                json.readValue(
                        "{\"amount\":40.50,\"ledger\":{\"schemaVersion\":2,\"carrierCode\":\"HLL\",\"trackingNo\":\"3624071813921329726\"}}",
                        LogisticsCostCommand.class);
        var old =
                new LogisticsCostDetailView(
                        "C1",
                        "LC001",
                        "LOGISTICS",
                        "DRAFT",
                        1,
                        data,
                        List.of(),
                        null,
                        null,
                        Instant.now(),
                        Instant.now());
        doReturn(old)
                .when(repository)
                .entry("T1", "logistics", "C1", "order:logistics-cost:confirm");
    }

    void source(boolean difference, BillVerification verification) {
        Map<String, Object> row = new HashMap<>();
        row.put("source_json", json.writeValueAsString(excel));
        row.put("source_difference", difference ? 1 : 0);
        row.put(
                "bill_verification_json",
                verification == null ? null : json.writeValueAsString(verification));
        when(mapper.lockCarrierSource("T1", "C1")).thenReturn(List.of(row));
    }

    @Test
    void excelCostUpgradedByApiCannotBypassDifferenceGuard() {
        source(true, null);
        assertThatThrownBy(
                        () ->
                                repository.transition(
                                        "T1",
                                        "logistics",
                                        "C1",
                                        new LogisticsCostActionCommand(1, ""),
                                        "U1",
                                        "order:logistics-cost:confirm"))
                .hasMessageContaining("来源账单已变化");
    }

    @Test
    void verifiedExcelAmountIsRecheckedAtConfirmationRatherThanTrustingPage() {
        source(
                false,
                new BillVerification(new BigDecimal("50.00"), "根据最终账单核对", "U1", Instant.now()));
        assertThatThrownBy(
                        () ->
                                repository.transition(
                                        "T1",
                                        "logistics",
                                        "C1",
                                        new LogisticsCostActionCommand(1, ""),
                                        "U1",
                                        "order:logistics-cost:confirm"))
                .hasMessageContaining("净账单不一致");
    }

    @Test
    void sourceVerificationEvidenceDoesNotDeserializeAsCostSnapshot() {
        var event = new OrderLogisticsCostAuditEntity();
        event.setActionCode("BILL_VERIFY");
        event.setActorId("U1");
        event.setReason("实际账单");
        event.setCreatedTime(java.time.LocalDateTime.now());
        event.setSnapshotJson("{\"amount\":40.50,\"reason\":\"实际账单\"}");
        when(audits.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(event));
        var result = repository.history("T1", "C1").getFirst();
        assertThat(result.snapshot()).isNull();
        assertThat(result.evidence()).containsKey("amount");
    }
}

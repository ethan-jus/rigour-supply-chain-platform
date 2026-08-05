package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.infrastructure.persistence.entity.DhbFinancialDocumentEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.DhbFinancialDocumentMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbReturnLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbReturnMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentMapper;
import com.rigour.shared.outbox.OutboxStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MybatisPlusOrderDocumentRepositoryTest {
    private DhbShipmentMapper shipmentMapper;
    private DhbShipmentLineMapper shipmentLineMapper;
    private DhbFinancialDocumentMapper financialMapper;
    private MybatisPlusOrderDocumentRepository repository;

    @BeforeEach
    void setUp() {
        shipmentMapper = mock(DhbShipmentMapper.class);
        shipmentLineMapper = mock(DhbShipmentLineMapper.class);
        financialMapper = mock(DhbFinancialDocumentMapper.class);
        repository = new MybatisPlusOrderDocumentRepository(shipmentMapper, shipmentLineMapper,
                mock(DhbReturnMapper.class), mock(DhbReturnLineMapper.class), financialMapper,
                mock(OutboxStore.class), JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void insertsShipmentAndDetailsThroughMybatisPlusMappers() {
        when(shipmentMapper.selectOne(any())).thenReturn(null);
        DhbOrderImportBatch.ShipmentLineItem line = new DhbOrderImportBatch.ShipmentLineItem(
                "LINE-1", "G-1", "SKU-1", "P-1", "商品一", new BigDecimal("2"),
                new BigDecimal("10"), new BigDecimal("20"), "件", "W-1", "完整发货");

        int changed = repository.importShipments("tenant-1", List.of(shipment(List.of(line), true, "hash-1")));

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<DhbShipmentEntity> shipment = ArgumentCaptor.forClass(DhbShipmentEntity.class);
        verify(shipmentMapper).insert(shipment.capture());
        assertThat(shipment.getValue().tenantId).isEqualTo("tenant-1");
        assertThat(shipment.getValue().sourceSystem).isEqualTo("DINGHUOBAO");
        assertThat(shipment.getValue().detailAvailable).isTrue();
        ArgumentCaptor<DhbShipmentLineEntity> detail = ArgumentCaptor.forClass(DhbShipmentLineEntity.class);
        verify(shipmentLineMapper).insert(detail.capture());
        assertThat(detail.getValue().sourceLineId).isEqualTo("LINE-1");
        assertThat(detail.getValue().lineAmount).isEqualByComparingTo("20");
    }

    @Test
    void skipsUnchangedShipmentAndPreservesExistingDetails() {
        DhbShipmentEntity existing = new DhbShipmentEntity();
        existing.id = "existing-id";
        existing.payloadHash = "same-hash";
        existing.detailAvailable = true;
        existing.createdAt = java.time.LocalDateTime.of(2026, 8, 1, 0, 0);
        when(shipmentMapper.selectOne(any())).thenReturn(existing);

        int changed = repository.importShipments("tenant-1", List.of(shipment(List.of(), false, "same-hash")));

        assertThat(changed).isZero();
        verify(shipmentMapper, never()).updateById(any(DhbShipmentEntity.class));
        verify(shipmentLineMapper, never()).delete(any());
    }

    @Test
    void updatesChangedListFieldsWithoutDeletingPreviouslyStoredDetails() {
        DhbShipmentEntity existing = new DhbShipmentEntity();
        existing.id = "existing-id";
        existing.payloadHash = "detail-hash";
        existing.detailAvailable = true;
        existing.createdAt = java.time.LocalDateTime.of(2026, 8, 1, 0, 0);
        when(shipmentMapper.selectOne(any())).thenReturn(existing);

        int changed = repository.importShipments(
                "tenant-1", List.of(shipment(List.of(), false, "changed-list-hash")));

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<DhbShipmentEntity> updated = ArgumentCaptor.forClass(DhbShipmentEntity.class);
        verify(shipmentMapper).updateById(updated.capture());
        assertThat(updated.getValue().detailAvailable).isTrue();
        verify(shipmentLineMapper, never()).delete(any());
    }

    @Test
    void usesDocumentTypeInFinancialIdempotencyAndPersistence() {
        when(financialMapper.selectOne(any())).thenReturn(null);
        DhbOrderImportBatch.FinancialItem item = new DhbOrderImportBatch.FinancialItem(
                "RECEIPT", "RC-1", null, "DH-1", "C-1", "CG-1", "13", "Offline",
                new BigDecimal("99.50"), "pend_receipted", Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"), Instant.parse("2026-08-04T01:00:00Z"),
                "SERIAL-1", "瑞盖", "测试银行", "6222****", "到账", "{}", "financial-hash");

        int changed = repository.importFinancialDocuments("tenant-1", List.of(item));

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<DhbFinancialDocumentEntity> saved = ArgumentCaptor.forClass(DhbFinancialDocumentEntity.class);
        verify(financialMapper).insert(saved.capture());
        assertThat(saved.getValue().documentType).isEqualTo("RECEIPT");
        assertThat(saved.getValue().documentNo).isEqualTo("RC-1");
        assertThat(saved.getValue().sourceStatus).isEqualTo("pend_receipted");
    }

    private static DhbOrderImportBatch.ShipmentItem shipment(
            List<DhbOrderImportBatch.ShipmentLineItem> lines, boolean detail, String hash) {
        return new DhbOrderImportBatch.ShipmentItem("S-ID", "S-1", "DH-1", "receivedin", "待收货",
                "10", "销售出库", "C-1", "客户一", "CG-1", "W-1", "主仓", "WG-1",
                Instant.parse("2026-08-04T02:00:00Z"), "顺丰", "SF-1", "测试",
                Instant.parse("2026-08-04T01:00:00Z"), Instant.parse("2026-08-04T02:00:00Z"),
                lines, "{}", hash, detail);
    }
}

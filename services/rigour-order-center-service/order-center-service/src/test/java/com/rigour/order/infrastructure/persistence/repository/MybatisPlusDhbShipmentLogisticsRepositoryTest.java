package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLogisticsEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLogisticsLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLogisticsLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLogisticsMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisPlusDhbShipmentLogisticsRepositoryTest {
    private DhbShipmentLogisticsMapper mapper;
    private DhbShipmentLogisticsLineMapper lineMapper;
    private MybatisPlusDhbShipmentLogisticsRepository repository;

    @BeforeEach
    void setUp() {
        mapper = org.mockito.Mockito.mock(DhbShipmentLogisticsMapper.class);
        lineMapper = org.mockito.Mockito.mock(DhbShipmentLogisticsLineMapper.class);
        repository = new MybatisPlusDhbShipmentLogisticsRepository(mapper, lineMapper,
                Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void importsShippedAndWaitStockLinesIntoOneOrderSnapshot() {
        when(mapper.selectOne(any())).thenReturn(null);
        DhbOrderImportBatch.ShipmentLogisticsRecord shipped = new DhbOrderImportBatch.ShipmentLogisticsRecord(
                "SHIP-ID", "SHIP-1", "receivedin", "顺丰", "SF", "SF-1",
                Instant.parse("2026-08-04T02:00:00Z"), null, "W-1", "主仓", List.of(
                        new DhbOrderImportBatch.ShipmentLogisticsLineItem("SHIPPED", "SHIP-LINE", "ORDER-LINE",
                                "G-1", "SKU-1", "buy", "P-1", "商品一", "红色", "件", "箱",
                                new BigDecimal("10"), new BigDecimal("2"), "发货" , "W-1", "主仓")));
        DhbOrderImportBatch.WaitStockItem wait = new DhbOrderImportBatch.WaitStockItem(
                "WAIT_STOCK", "ORDER-LINE-2", "G-2", "SKU-2", "buy", "P-2", "商品二", "蓝色",
                "件", "箱", new BigDecimal("10"), "W-1", "主仓", new BigDecimal("3"),
                new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("2"), "待出库");

        int changed = repository.importSnapshots("tenant-1", List.of(new DhbOrderImportBatch.ShipmentLogisticsItem(
                "DH-1", List.of(shipped), List.of(wait), "{\"shipped\":[]}", "hash-1")));

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<DhbShipmentLogisticsEntity> saved = ArgumentCaptor.forClass(DhbShipmentLogisticsEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().tenantId).isEqualTo("tenant-1");
        assertThat(saved.getValue().orderNo).isEqualTo("DH-1");
        assertThat(saved.getValue().shippedCount).isEqualTo(1);
        assertThat(saved.getValue().waitStockCount).isEqualTo(1);
        ArgumentCaptor<DhbShipmentLogisticsLineEntity> lines = ArgumentCaptor.forClass(DhbShipmentLogisticsLineEntity.class);
        verify(lineMapper, org.mockito.Mockito.times(2)).insert(lines.capture());
        assertThat(lines.getAllValues()).extracting(line -> line.lineType)
                .containsExactlyInAnyOrder("SHIPPED", "WAIT_STOCK");
    }

    @Test
    void skipsUnchangedSnapshotAndPreservesExistingLines() {
        DhbShipmentLogisticsEntity existing = new DhbShipmentLogisticsEntity();
        existing.id = "existing-id";
        existing.payloadHash = "same-hash";
        when(mapper.selectOne(any())).thenReturn(existing);

        int changed = repository.importSnapshots("tenant-1", List.of(
                new DhbOrderImportBatch.ShipmentLogisticsItem("DH-1", List.of(), List.of(), "{}", "same-hash")));

        assertThat(changed).isZero();
        verify(mapper, never()).updateById(any(DhbShipmentLogisticsEntity.class));
        verify(lineMapper, never()).delete(any());
    }
}

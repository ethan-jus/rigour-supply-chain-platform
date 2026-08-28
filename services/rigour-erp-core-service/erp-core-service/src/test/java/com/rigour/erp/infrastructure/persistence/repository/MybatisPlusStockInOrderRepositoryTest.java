package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.StockInOrderSearchCriteria;
import com.rigour.erp.domain.enums.ErpStockInType;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockFlowMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisPlusStockInOrderRepositoryTest {
    private static final String TENANT_ID = "019fb100-0000-7000-8000-000000000011";

    private final InternalStockInOrderMapper stockInMapper = mock(InternalStockInOrderMapper.class);
    private final InternalStockInOrderLineMapper stockInLineMapper = mock(InternalStockInOrderLineMapper.class);
    private final InternalProcurementOrderMapper procurementOrderMapper = mock(InternalProcurementOrderMapper.class);
    private final InternalProcurementOrderLineMapper procurementLineMapper = mock(InternalProcurementOrderLineMapper.class);
    private final InternalStockBalanceMapper stockBalanceMapper = mock(InternalStockBalanceMapper.class);
    private final InternalStockFlowMapper stockFlowMapper = mock(InternalStockFlowMapper.class);
    private final InternalSupplierProfileMapper supplierMapper = mock(InternalSupplierProfileMapper.class);
    private final InternalInventoryWarehouseMapper warehouseMapper = mock(InternalInventoryWarehouseMapper.class);
    private final MybatisPlusStockInOrderRepository repository = new MybatisPlusStockInOrderRepository(
            stockInMapper, stockInLineMapper, procurementOrderMapper, procurementLineMapper,
            stockBalanceMapper, stockFlowMapper, supplierMapper, warehouseMapper,
            Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

    @BeforeAll
    static void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InternalStockInOrderEntity.class);
    }

    @Test
    void stockInOrdersSupportsSynchronizedTransferRowsWithoutSupplier() {
        InternalStockInOrderEntity order = stockIn(99L);
        when(stockInMapper.selectCount(any())).thenReturn(1L);
        when(stockInMapper.selectList(any())).thenReturn(List.of(order));
        when(stockInLineMapper.selectList(any())).thenReturn(List.of());
        when(warehouseMapper.selectList(any())).thenReturn(List.of(warehouse(9L, "深圳仓")));

        var result = repository.stockInOrders(TENANT_ID, 0, 20,
                new com.rigour.erp.application.port.out.ErpStockInOrderStore.StockInOrderSearchCriteria(
                        null, null, null, null, null, null, null, null));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.stockInNo()).isEqualTo("SI202608210001");
            assertThat(item.stockInTypeCode()).isEqualTo(ErpStockInType.TRANSFER.code());
            assertThat(item.supplierId()).isNull();
            assertThat(item.supplierName()).isNull();
            assertThat(item.warehouseName()).isEqualTo("深圳仓");
        });
        verifyNoInteractions(supplierMapper);
    }

    @Test
    void stockInOrderDetailSupportsSynchronizedTransferRowsWithoutSupplier() {
        InternalStockInOrderEntity order = stockIn(99L);
        when(stockInMapper.selectOne(any())).thenReturn(order);
        when(stockInLineMapper.selectList(any())).thenReturn(List.of());
        when(warehouseMapper.selectList(any())).thenReturn(List.of(warehouse(9L, "深圳仓")));

        var result = repository.stockInOrder(TENANT_ID, 99L);

        assertThat(result).hasValueSatisfying(item -> {
            assertThat(item.stockInNo()).isEqualTo("SI202608210001");
            assertThat(item.stockInTypeCode()).isEqualTo(ErpStockInType.TRANSFER.code());
            assertThat(item.supplierId()).isNull();
            assertThat(item.supplierName()).isNull();
            assertThat(item.warehouseName()).isEqualTo("深圳仓");
        });
        verifyNoInteractions(supplierMapper);
    }

    @Test
    void stockInOrdersMatchesInternalNoSourceNoRelationsAndRemark() {
        when(stockInMapper.selectCount(any())).thenReturn(0L);
        when(stockInMapper.selectList(any())).thenReturn(List.of());

        repository.stockInOrders(TENANT_ID, 0, 20,
                new StockInOrderSearchCriteria("RK.20260826.0091", null, null,
                        null, null, null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<InternalStockInOrderEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(stockInMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("stock_in_no");
        assertThat(sql).contains("source_document_no");
        assertThat(sql).contains("procurement_no");
        assertThat(sql).contains("transfer_order_no");
        assertThat(sql).contains("remark");
    }

    private static InternalStockInOrderEntity stockIn(Long id) {
        InternalStockInOrderEntity entity = new InternalStockInOrderEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setStockInNo("SI202608210001");
        entity.setSourceSystemCode("DINGHUOBAO");
        entity.setSourceDocumentNo("RK.20260821.0001");
        entity.setStockInTypeCode(ErpStockInType.TRANSFER.code());
        entity.setTransferOrderNo("DB.20260821.0053");
        entity.setWarehouseId(9L);
        entity.setSupplierId(null);
        entity.setStatusCode("CONFIRMED");
        entity.setStockInTime(LocalDateTime.of(2026, 8, 21, 10, 20));
        entity.setRevision(1);
        entity.setUpdatedTime(LocalDateTime.of(2026, 8, 26, 10, 20));
        entity.setDeleted(0);
        return entity;
    }

    private static InternalInventoryWarehouseEntity warehouse(Long id, String name) {
        InternalInventoryWarehouseEntity entity = new InternalInventoryWarehouseEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setWarehouseName(name);
        entity.setDeleted(0);
        return entity;
    }
}

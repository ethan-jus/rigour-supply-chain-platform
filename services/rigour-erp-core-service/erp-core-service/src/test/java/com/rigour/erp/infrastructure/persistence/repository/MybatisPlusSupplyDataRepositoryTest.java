package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.SupplyDataStore.ImportResult;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalPurchaseReturnOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalPurchaseReturnOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalSupplierProfileEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalPurchaseReturnOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalPurchaseReturnOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ERP 供应链同步引用解析不能反向制造主数据。 */
class MybatisPlusSupplyDataRepositoryTest {
    private static final String TENANT_ID = "019fb100-0000-7000-8000-000000000011";
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000012");
    private static final UUID RUN_ID = UUID.fromString("019fb100-0000-7000-8000-000000000013");

    private final InternalSupplierProfileMapper supplierMapper = mock(InternalSupplierProfileMapper.class);
    private final InternalInventoryWarehouseMapper warehouseMapper = mock(InternalInventoryWarehouseMapper.class);
    private final InternalProductMapper productMapper = mock(InternalProductMapper.class);
    private final InternalProductVariantMapper variantMapper = mock(InternalProductVariantMapper.class);
    private final InternalProcurementOrderMapper procurementMapper = mock(InternalProcurementOrderMapper.class);
    private final InternalProcurementOrderLineMapper procurementLineMapper = mock(InternalProcurementOrderLineMapper.class);
    private final InternalPurchaseReturnOrderMapper purchaseReturnMapper = mock(InternalPurchaseReturnOrderMapper.class);
    private final InternalPurchaseReturnOrderLineMapper purchaseReturnLineMapper =
            mock(InternalPurchaseReturnOrderLineMapper.class);
    private final InternalStockInOrderMapper stockInMapper = mock(InternalStockInOrderMapper.class);
    private final InternalStockInOrderLineMapper stockInLineMapper = mock(InternalStockInOrderLineMapper.class);
    private final InternalStockBalanceMapper stockBalanceMapper = mock(InternalStockBalanceMapper.class);
    private final MasterSourceBindingMapper bindingMapper = mock(MasterSourceBindingMapper.class);
    private final MasterDataSyncRunMapper syncRunMapper = mock(MasterDataSyncRunMapper.class);
    private final MasterDataSyncLockMapper syncLockMapper = mock(MasterDataSyncLockMapper.class);
    private final MybatisPlusSupplyDataRepository repository = repository();

    @Test
    void supplierSyncCodeUsesSourceCreatedTimeFromRawFields() {
        givenRunConnector();
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalSupplierProfileEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return 1;
        }).when(supplierMapper).insert(any(InternalSupplierProfileEntity.class));

        ImportResult result = repository.importSupplier(TENANT_ID, RUN_ID,
                new Supplier("SUP-1", null, "S001", "勇者工厂", null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null,
                        Map.of("create_date", "2026-08-21 09:20:00"), "hash-supplier"));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalSupplierProfileEntity> inserted =
                ArgumentCaptor.forClass(InternalSupplierProfileEntity.class);
        verify(supplierMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSupplierCode()).startsWith("SUP20260821");
    }

    @Test
    void warehouseSyncCodeUsesSourceCreatedTimeFromRawFields() {
        givenRunConnector();
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(warehouseMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalInventoryWarehouseEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        }).when(warehouseMapper).insert(any(InternalInventoryWarehouseEntity.class));

        ImportResult result = repository.importWarehouse(TENANT_ID, RUN_ID,
                new Warehouse("WH-1", null, "34", "指间云仓", "T", Boolean.TRUE,
                        null, null, null, null, null,
                        Map.of("create_date", "2026-08-21 09:20:00"), "hash-warehouse"));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalInventoryWarehouseEntity> inserted =
                ArgumentCaptor.forClass(InternalInventoryWarehouseEntity.class);
        verify(warehouseMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getWarehouseCode()).startsWith("WH20260821");
    }

    @Test
    void purchaseOrderWithUnresolvedReferencesIsRejectedWithoutCreatingMasterData() {
        givenRunConnector();
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        ImportResult result = repository.importPurchaseOrder(TENANT_ID, RUN_ID,
                purchaseOrder("PO-1", "未知供应商", "未知仓库"));

        assertThat(result.rejected()).isEqualTo(1);
        verify(supplierMapper, never()).insert(any(InternalSupplierProfileEntity.class));
        verify(warehouseMapper, never()).insert(any(InternalInventoryWarehouseEntity.class));
        verify(procurementMapper, never()).insert(any(InternalProcurementOrderEntity.class));
        verify(bindingMapper, never()).insert(any(MasterSourceBindingEntity.class));
    }

    @Test
    void purchaseOrderWithDhbUnspecifiedWarehouseUsesDisabledPlaceholderWarehouse() {
        givenRunConnector();
        InternalSupplierProfileEntity supplier = supplier(7L, "勇者工厂");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("SUPPLIER", "SUPPLIER_PROFILE", "550585",
                        supplier.getId(), supplier.getSupplierName()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(supplierMapper.selectById(supplier.getId())).thenReturn(supplier);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(warehouseMapper.selectCount(any())).thenReturn(0L);
        when(procurementMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalInventoryWarehouseEntity entity = invocation.getArgument(0);
            assertThat(entity.getWarehouseCode()).startsWith("WH20260821");
            assertThat(entity.getWarehouseName()).isEqualTo("订货宝未指定仓库");
            assertThat(entity.getStatusCode()).isEqualTo("DISABLED");
            entity.setId(19L);
            return 1;
        }).when(warehouseMapper).insert(any(InternalInventoryWarehouseEntity.class));
        doAnswer(invocation -> {
            InternalProcurementOrderEntity entity = invocation.getArgument(0);
            assertThat(entity.getTargetWarehouseId()).isEqualTo(19L);
            entity.setId(88L);
            return 1;
        }).when(procurementMapper).insert(any(InternalProcurementOrderEntity.class));

        ImportResult result = repository.importPurchaseOrder(TENANT_ID, RUN_ID,
                purchaseOrderWithUnspecifiedWarehouse("27447", supplier.getSupplierName()));

        assertThat(result.created()).isEqualTo(1);
        verify(warehouseMapper).insert(any(InternalInventoryWarehouseEntity.class));
        verify(procurementMapper).insert(any(InternalProcurementOrderEntity.class));
        verify(procurementLineMapper).insert(any(InternalProcurementOrderLineEntity.class));
    }

    @Test
    void purchaseOrderCanResolveSupplierAndWarehouseByUniqueSourceNameWithoutCreatingThem() {
        givenRunConnector();
        InternalSupplierProfileEntity supplier = supplier(7L, "杨掌柜食品科技");
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "杭州仓");
        when(bindingMapper.selectList(any()))
                .thenReturn(List.of(binding("SUPPLIER", "SUPPLIER_PROFILE", "549414",
                        supplier.getId(), supplier.getSupplierName())))
                .thenReturn(List.of(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26860",
                        warehouse.getId(), warehouse.getWarehouseName())));
        when(supplierMapper.selectById(supplier.getId())).thenReturn(supplier);
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(procurementMapper.selectCount(any())).thenReturn(0L);

        ImportResult result = repository.importPurchaseOrder(TENANT_ID, RUN_ID,
                purchaseOrder("11768202", supplier.getSupplierName(), warehouse.getWarehouseName()));

        assertThat(result.created()).isEqualTo(1);
        verify(supplierMapper, never()).insert(any(InternalSupplierProfileEntity.class));
        verify(warehouseMapper, never()).insert(any(InternalInventoryWarehouseEntity.class));
        verify(procurementMapper).insert(any(InternalProcurementOrderEntity.class));
    }

    @Test
    void purchaseOrderResolvesSkuByGoodsIdAndOptionsIdCompositeSourceId() {
        givenRunConnector();
        InternalSupplierProfileEntity supplier = supplier(7L, "勇者工厂");
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "指间云仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("SUPPLIER", "SUPPLIER_PROFILE", "550585",
                        supplier.getId(), supplier.getSupplierName()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(supplierMapper.selectById(supplier.getId())).thenReturn(supplier);
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(procurementMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalProcurementOrderEntity entity = invocation.getArgument(0);
            assertThat(entity.getProcurementNo()).startsWith("PO20260821");
            entity.setId(88L);
            return 1;
        }).when(procurementMapper).insert(any(InternalProcurementOrderEntity.class));

        ImportResult result = repository.importPurchaseOrder(TENANT_ID, RUN_ID,
                purchaseOrderWithLine("27781", supplier.getSupplierName(), warehouse.getWarehouseName()));

        assertThat(result.created()).isEqualTo(1);
        verify(procurementLineMapper).insert(any(InternalProcurementOrderLineEntity.class));
        verify(productMapper, never()).insert(any(InternalProductEntity.class));
        verify(variantMapper, never()).insert(any(InternalProductVariantEntity.class));
    }

    @Test
    void purchaseOrderReprojectsHistoricalNumberDateWhenPayloadUnchanged() {
        givenRunConnector();
        InternalSupplierProfileEntity supplier = supplier(7L, "勇者工厂");
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "指间云仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        MasterSourceBindingEntity existingBinding = binding("PURCHASE_ORDER", "PROCUREMENT_ORDER",
                "27781", 66L, supplier.getSupplierName());
        existingBinding.sourceCode = "JH.20260821.0023";
        existingBinding.sourcePayloadHash = "hash-po-with-line";
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("SUPPLIER", "SUPPLIER_PROFILE", "550585",
                        supplier.getId(), supplier.getSupplierName()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(existingBinding)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(supplierMapper.selectById(supplier.getId())).thenReturn(supplier);
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(procurementMapper.selectCount(any())).thenReturn(0L);
        InternalProcurementOrderEntity historical = procurement(66L);
        historical.setProcurementNo("PO202608260001");
        when(procurementMapper.selectById(66L)).thenReturn(historical);

        ImportResult result = repository.importPurchaseOrder(TENANT_ID, RUN_ID,
                purchaseOrderWithLine("27781", supplier.getSupplierName(), warehouse.getWarehouseName()));

        assertThat(result.changed()).isEqualTo(1);
        ArgumentCaptor<InternalProcurementOrderEntity> updated =
                ArgumentCaptor.forClass(InternalProcurementOrderEntity.class);
        verify(procurementMapper).updateById(updated.capture());
        assertThat(updated.getValue().getProcurementNo()).startsWith("PO20260821");
        verify(procurementLineMapper).insert(any(InternalProcurementOrderLineEntity.class));
    }

    @Test
    void warehousingReceiptImportDoesNotMutateStockBalance() {
        givenRunConnector();
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "指间云仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(stockInMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalStockInOrderEntity entity = invocation.getArgument(0);
            assertThat(entity.getConnectorId()).isEqualTo(CONNECTOR_ID.toString());
            assertThat(entity.getSourceSystemCode()).isEqualTo("DINGHUOBAO");
            assertThat(entity.getSourceDocumentNo()).isEqualTo("RK.20260821.0001");
            assertThat(entity.getStockInTypeCode()).isEqualTo("PURCHASE");
            entity.setId(99L);
            return 1;
        }).when(stockInMapper).insert(any(InternalStockInOrderEntity.class));

        ImportResult result = repository.importWarehousingReceipt(TENANT_ID, RUN_ID,
                warehousingReceipt("RK-1", warehouse.getWarehouseName()));

        assertThat(result.created()).isEqualTo(1);
        verify(stockInMapper).insert(any(InternalStockInOrderEntity.class));
        verify(stockInLineMapper).insert(any(InternalStockInOrderLineEntity.class));
        verifyNoInteractions(stockBalanceMapper);
    }

    @Test
    void warehousingReceiptTypeEightImportsAsTransferStockIn() {
        givenRunConnector();
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "重庆仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(stockInMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalStockInOrderEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        }).when(stockInMapper).insert(any(InternalStockInOrderEntity.class));

        ImportResult result = repository.importWarehousingReceipt(TENANT_ID, RUN_ID,
                warehousingReceipt("RK-TRANSFER-1", warehouse.getWarehouseName(),
                        "8", "调拨入库", List.of(), Map.of("transfer_num", "DB.20260821.0053")));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalStockInOrderEntity> inserted =
                ArgumentCaptor.forClass(InternalStockInOrderEntity.class);
        verify(stockInMapper).insert(inserted.capture());
        assertThat(inserted.getValue()).satisfies(entity -> {
            assertThat(entity.getConnectorId()).isEqualTo(CONNECTOR_ID.toString());
            assertThat(entity.getSourceSystemCode()).isEqualTo("DINGHUOBAO");
            assertThat(entity.getSourceDocumentNo()).isEqualTo("RK.20260821.0001");
            assertThat(entity.getStockInNo()).startsWith("SI20260821");
            assertThat(entity.getStockInTypeCode()).isEqualTo("TRANSFER");
            assertThat(entity.getProcurementOrderId()).isNull();
            assertThat(entity.getProcurementNo()).isNull();
            assertThat(entity.getTransferOrderId()).isNull();
            assertThat(entity.getTransferOrderNo()).isEqualTo("DB.20260821.0053");
            assertThat(entity.getSupplierId()).isNull();
        });
        verify(stockInLineMapper).insert(any(InternalStockInOrderLineEntity.class));
        verifyNoInteractions(stockBalanceMapper);
    }

    @Test
    void warehousingReceiptTypeMinusOneImportsAsReturnStockIn() {
        givenRunConnector();
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "深圳仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(null)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(stockInMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            InternalStockInOrderEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        }).when(stockInMapper).insert(any(InternalStockInOrderEntity.class));

        ImportResult result = repository.importWarehousingReceipt(TENANT_ID, RUN_ID,
                warehousingReceipt("RK-RETURN-1", warehouse.getWarehouseName(),
                        "-1", "退货入库", List.of(), Map.of()));

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<InternalStockInOrderEntity> inserted =
                ArgumentCaptor.forClass(InternalStockInOrderEntity.class);
        verify(stockInMapper).insert(inserted.capture());
        assertThat(inserted.getValue()).satisfies(entity -> {
            assertThat(entity.getConnectorId()).isEqualTo(CONNECTOR_ID.toString());
            assertThat(entity.getSourceSystemCode()).isEqualTo("DINGHUOBAO");
            assertThat(entity.getSourceDocumentNo()).isEqualTo("RK.20260821.0001");
            assertThat(entity.getStockInNo()).startsWith("SI20260821");
            assertThat(entity.getStockInTypeCode()).isEqualTo("RETURN");
            assertThat(entity.getProcurementOrderId()).isNull();
            assertThat(entity.getProcurementNo()).isNull();
            assertThat(entity.getTransferOrderId()).isNull();
            assertThat(entity.getTransferOrderNo()).isNull();
            assertThat(entity.getSupplierId()).isNull();
        });
        verify(stockInLineMapper).insert(any(InternalStockInOrderLineEntity.class));
        verifyNoInteractions(stockBalanceMapper);
    }

    @Test
    void warehousingReceiptReprojectsWrongHistoricalTypeWhenPayloadUnchanged() {
        givenRunConnector();
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "重庆仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        MasterSourceBindingEntity existingBinding = binding("WAREHOUSING_RECEIPT", "STOCK_IN_ORDER",
                "RK-TRANSFER-OLD", 77L, warehouse.getWarehouseName());
        existingBinding.sourceCode = "RK.20260821.0001";
        existingBinding.sourcePayloadHash = "hash-rk";
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(existingBinding)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(stockInMapper.selectById(77L)).thenReturn(stockIn(77L, "PURCHASE"));

        ImportResult result = repository.importWarehousingReceipt(TENANT_ID, RUN_ID,
                warehousingReceipt("RK-TRANSFER-OLD", warehouse.getWarehouseName(),
                        "8", "调拨入库", List.of(), Map.of("transfer_num", "DB.20260821.0053")));

        assertThat(result.changed()).isEqualTo(1);
        ArgumentCaptor<InternalStockInOrderEntity> updated =
                ArgumentCaptor.forClass(InternalStockInOrderEntity.class);
        verify(stockInMapper).updateById(updated.capture());
        assertThat(updated.getValue()).satisfies(entity -> {
            assertThat(entity.getStockInTypeCode()).isEqualTo("TRANSFER");
            assertThat(entity.getProcurementOrderId()).isNull();
            assertThat(entity.getProcurementNo()).isNull();
            assertThat(entity.getTransferOrderNo()).isEqualTo("DB.20260821.0053");
            assertThat(entity.getSupplierId()).isNull();
        });
        verify(stockInMapper, never()).insert(any(InternalStockInOrderEntity.class));
        verifyNoInteractions(stockBalanceMapper);
    }

    @Test
    void warehousingReceiptReprojectsHistoricalPurchaseToReturnWhenPayloadUnchanged() {
        givenRunConnector();
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "深圳仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        MasterSourceBindingEntity existingBinding = binding("WAREHOUSING_RECEIPT", "STOCK_IN_ORDER",
                "RK-RETURN-OLD", 78L, warehouse.getWarehouseName());
        existingBinding.sourceCode = "RK.20260821.0001";
        existingBinding.sourcePayloadHash = "hash-rk";
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(existingBinding)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        InternalStockInOrderEntity historical = stockIn(78L, "PURCHASE");
        historical.setStockInNo("SI202608260001");
        when(stockInMapper.selectById(78L)).thenReturn(historical);

        ImportResult result = repository.importWarehousingReceipt(TENANT_ID, RUN_ID,
                warehousingReceipt("RK-RETURN-OLD", warehouse.getWarehouseName(),
                        "-1", "退货入库", List.of(), Map.of()));

        assertThat(result.changed()).isEqualTo(1);
        ArgumentCaptor<InternalStockInOrderEntity> updated =
                ArgumentCaptor.forClass(InternalStockInOrderEntity.class);
        verify(stockInMapper).updateById(updated.capture());
        assertThat(updated.getValue()).satisfies(entity -> {
            assertThat(entity.getStockInNo()).startsWith("SI20260821");
            assertThat(entity.getStockInTypeCode()).isEqualTo("RETURN");
            assertThat(entity.getProcurementOrderId()).isNull();
            assertThat(entity.getProcurementNo()).isNull();
            assertThat(entity.getTransferOrderNo()).isNull();
            assertThat(entity.getSupplierId()).isNull();
        });
        verify(stockInMapper, never()).insert(any(InternalStockInOrderEntity.class));
        verifyNoInteractions(stockBalanceMapper);
    }

    @Test
    void purchaseReturnReprojectsHistoricalNumberDateWhenPayloadUnchanged() {
        givenRunConnector();
        InternalSupplierProfileEntity supplier = supplier(7L, "勇者工厂");
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "指间云仓");
        InternalProductEntity product = product(11L, "100009", "启航款皮头");
        InternalProductVariantEntity variant = variant(13L, product.getId(), "SKU-100009-H", "H");
        MasterSourceBindingEntity existingBinding = binding("PURCHASE_RETURN", "PURCHASE_RETURN_ORDER",
                "TH-27781", 79L, supplier.getSupplierName());
        existingBinding.sourceCode = "TH.20260821.0003";
        existingBinding.sourcePayloadHash = "hash-return";
        when(bindingMapper.selectOne(any()))
                .thenReturn(binding("SUPPLIER", "SUPPLIER_PROFILE", "550585",
                        supplier.getId(), supplier.getSupplierName()))
                .thenReturn(binding("WAREHOUSE", "INVENTORY_WAREHOUSE", "26914",
                        warehouse.getId(), warehouse.getWarehouseName()))
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()))
                .thenReturn(existingBinding)
                .thenReturn(binding("PRODUCT_SPU", "PRODUCT", "1167088",
                        product.getId(), product.getProductName()))
                .thenReturn(binding("PRODUCT_SKU", "PRODUCT_VARIANT", "1167088::721960",
                        variant.getId(), variant.getSpecificationSnapshot()));
        when(supplierMapper.selectById(supplier.getId())).thenReturn(supplier);
        when(warehouseMapper.selectById(warehouse.getId())).thenReturn(warehouse);
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(variantMapper.selectById(variant.getId())).thenReturn(variant);
        when(purchaseReturnMapper.selectCount(any())).thenReturn(0L);
        InternalPurchaseReturnOrderEntity historical = purchaseReturn(79L);
        historical.setPurchaseReturnNo("PR202608260001");
        when(purchaseReturnMapper.selectById(79L)).thenReturn(historical);

        ImportResult result = repository.importPurchaseReturn(TENANT_ID, RUN_ID,
                purchaseReturn("TH-27781", supplier.getSupplierName(), warehouse.getWarehouseName()));

        assertThat(result.changed()).isEqualTo(1);
        ArgumentCaptor<InternalPurchaseReturnOrderEntity> updated =
                ArgumentCaptor.forClass(InternalPurchaseReturnOrderEntity.class);
        verify(purchaseReturnMapper).updateById(updated.capture());
        assertThat(updated.getValue().getPurchaseReturnNo()).startsWith("PR20260821");
        verify(purchaseReturnLineMapper).insert(any(InternalPurchaseReturnOrderLineEntity.class));
    }

    @Test
    void warehouseSourceBindingPublishesExternalMappingForOrderProjection() {
        MasterSourceBindingEntity binding = binding("WAREHOUSE", "INVENTORY_WAREHOUSE",
                "26914", 9L, "指间云仓");
        binding.sourceCode = "34";
        binding.sourcePayloadHash = "hash-warehouse";
        binding.syncedAt = java.time.LocalDateTime.of(2026, 8, 21, 8, 0);
        InternalInventoryWarehouseEntity warehouse = warehouse(9L, "指间云仓");
        warehouse.setWarehouseCode("WH0001");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(warehouse));

        var result = repository.externalObjectMappings(TENANT_ID, CONNECTOR_ID, RUN_ID,
                com.rigour.erp.domain.model.supply.SupplyDataObjectType.WAREHOUSE);

        assertThat(result).singleElement().satisfies(mapping -> {
            assertThat(mapping.sourceObjectType()).isEqualTo("WAREHOUSE");
            assertThat(mapping.sourceObjectId()).isEqualTo("26914");
            assertThat(mapping.sourceObjectNo()).isEqualTo("34");
            assertThat(mapping.internalDomain()).isEqualTo("ERP");
            assertThat(mapping.internalObjectType()).isEqualTo("WAREHOUSE");
            assertThat(mapping.internalObjectId()).isEqualTo(9L);
            assertThat(mapping.internalObjectNo()).isEqualTo("WH0001");
            assertThat(mapping.payloadChecksum()).isEqualTo("hash-warehouse");
        });
    }

    private MybatisPlusSupplyDataRepository repository() {
        return new MybatisPlusSupplyDataRepository(supplierMapper, warehouseMapper, productMapper,
                variantMapper, procurementMapper, procurementLineMapper, purchaseReturnMapper,
                purchaseReturnLineMapper, stockInMapper, stockInLineMapper, stockBalanceMapper,
                bindingMapper, syncRunMapper, syncLockMapper,
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }

    private void givenRunConnector() {
        MasterDataSyncRunEntity run = new MasterDataSyncRunEntity();
        run.id = RUN_ID.toString();
        run.tenantId = TENANT_ID;
        run.connectorId = CONNECTOR_ID.toString();
        when(syncRunMapper.selectOne(any())).thenReturn(run);
    }

    private static PurchaseOrder purchaseOrder(String supplierSourceId, String supplierName,
                                               String warehouseName) {
        return new PurchaseOrder("PO-1", "CG-001", supplierSourceId, null, supplierName,
                null, null, warehouseName, null, null, "FINISHED", "完成",
                null, null, null, null, null, null, null, null,
                false, null, null, List.of(), Map.of(), "hash-po-1");
    }

    private static PurchaseOrder purchaseOrderWithLine(String sourceId, String supplierName,
                                                       String warehouseName) {
        PurchaseOrder.Line line = new PurchaseOrder.Line("103380", "1167088", "",
                "100009", "启航款皮头", "721960", "", "H",
                java.math.BigDecimal.valueOf(1200), java.math.BigDecimal.ZERO,
                "base_units", "颗", java.math.BigDecimal.valueOf(1200),
                java.math.BigDecimal.valueOf(1200), java.math.BigDecimal.ZERO,
                null, Map.of(), "hash-line");
        return new PurchaseOrder(sourceId, "JH.20260821.0023", "550585", "29971",
                supplierName, "26914", "34", warehouseName, null, null,
                "finished", "已完成", "paided", "已付款", null,
                Instant.parse("2026-08-21T01:20:00Z"), null,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.valueOf(1200), false, null, null,
                List.of(line), Map.of(), "hash-po-with-line");
    }

    private static PurchaseOrder purchaseOrderWithUnspecifiedWarehouse(String sourceId, String supplierName) {
        PurchaseOrder.Line line = new PurchaseOrder.Line("103380", "1167088", "",
                "100009", "启航款皮头", "721960", "", "H",
                java.math.BigDecimal.valueOf(1200), java.math.BigDecimal.ZERO,
                "base_units", "颗", java.math.BigDecimal.valueOf(1200),
                java.math.BigDecimal.valueOf(1200), java.math.BigDecimal.ZERO,
                null, Map.of(), "hash-line");
        return new PurchaseOrder(sourceId, "JH.20260813.0004", "550585", "29971",
                supplierName, "0", "-", "-", null, null,
                "finished", "已完成", "paided", "已付款", null,
                Instant.parse("2026-08-21T01:20:00Z"), null,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.valueOf(1200), false, null, null,
                List.of(line), Map.of(), "hash-po-with-unspecified-warehouse");
    }

    private static WarehousingReceipt warehousingReceipt(String sourceId, String warehouseName) {
        return warehousingReceipt(sourceId, warehouseName, "1", "采购入库", List.of(), Map.of());
    }

    private static WarehousingReceipt warehousingReceipt(String sourceId, String warehouseName,
                                                         String typeId, String typeName,
                                                         List<WarehousingReceipt.PurchaseLink> purchaseLinks,
                                                         Map<String, Object> sourceFields) {
        WarehousingReceipt.Line line = new WarehousingReceipt.Line("RK-LINE-1",
                "1167088", "100009", "启航款皮头", "721960", "SKU-100009-H", "H",
                java.math.BigDecimal.valueOf(1200), java.math.BigDecimal.valueOf(1200),
                "base_units", "颗", java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, Map.of(), "hash-rk-line");
        return new WarehousingReceipt(sourceId, "RK.20260821.0001", "26914", warehouseName,
                null, null, typeId, typeName, "finished", "已完成", null,
                null, null, null, null, null, null,
                Instant.parse("2026-08-21T02:20:00Z"),
                Instant.parse("2026-08-21T02:00:00Z"),
                Instant.parse("2026-08-21T02:30:00Z"),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                false, null, "订货宝入库", List.of(line), purchaseLinks, sourceFields, "hash-rk");
    }

    private static PurchaseReturn purchaseReturn(String sourceId, String supplierName, String warehouseName) {
        PurchaseReturn.Line line = new PurchaseReturn.Line("TH-LINE-1",
                "1167088", "100009", "启航款皮头", "721960", "SKU-100009-H", "H",
                java.math.BigDecimal.valueOf(2), java.math.BigDecimal.valueOf(2),
                java.math.BigDecimal.valueOf(100), java.math.BigDecimal.valueOf(100),
                "base_units", "颗", java.math.BigDecimal.valueOf(2), java.math.BigDecimal.valueOf(2),
                java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(200), java.math.BigDecimal.ZERO,
                null, null, null, null, Map.of(), "hash-return-line");
        return new PurchaseReturn(sourceId, "TH.20260821.0003", "550585", "29971",
                supplierName, "26914", "34", warehouseName, null, "15313265881",
                "finished", "已完成", java.math.BigDecimal.valueOf(200), java.math.BigDecimal.ZERO,
                "退货", Instant.parse("2026-08-21T02:00:00Z"),
                Instant.parse("2026-08-21T02:20:00Z"), null, "订货宝采购退货",
                1, null, null, null, List.of(), List.of(), null, null, null,
                false, List.of(line), Map.of(), "hash-return");
    }

    private static InternalProcurementOrderEntity procurement(Long id) {
        InternalProcurementOrderEntity entity = new InternalProcurementOrderEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setProcurementNo("PO202608210001");
        entity.setSupplierId(7L);
        entity.setTargetWarehouseId(9L);
        entity.setStatusCode("FINISHED");
        entity.setTotalQuantity(java.math.BigDecimal.valueOf(1200));
        entity.setTotalAmount(java.math.BigDecimal.valueOf(1200));
        entity.setUpdatedBy("SYSTEM");
        entity.setRevision(1);
        entity.setDeleted(0);
        return entity;
    }

    private static InternalPurchaseReturnOrderEntity purchaseReturn(Long id) {
        InternalPurchaseReturnOrderEntity entity = new InternalPurchaseReturnOrderEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setSupplierId(7L);
        entity.setWarehouseId(9L);
        entity.setStatusCode("FINISHED");
        entity.setTotalQuantity(java.math.BigDecimal.valueOf(2));
        entity.setTotalAmount(java.math.BigDecimal.valueOf(200));
        entity.setUpdatedBy("SYSTEM");
        entity.setRevision(1);
        entity.setDeleted(0);
        return entity;
    }

    private static InternalSupplierProfileEntity supplier(Long id, String name) {
        InternalSupplierProfileEntity entity = new InternalSupplierProfileEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setSupplierName(name);
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

    private static InternalProductEntity product(Long id, String code, String name) {
        InternalProductEntity entity = new InternalProductEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setProductCode(code);
        entity.setProductName(name);
        entity.setDeleted(0);
        return entity;
    }

    private static InternalProductVariantEntity variant(Long id, Long productId, String code,
                                                        String specification) {
        InternalProductVariantEntity entity = new InternalProductVariantEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setProductId(productId);
        entity.setVariantCode(code);
        entity.setSpecificationSnapshot(specification);
        entity.setDeleted(0);
        return entity;
    }

    private static InternalStockInOrderEntity stockIn(Long id, String stockInTypeCode) {
        InternalStockInOrderEntity entity = new InternalStockInOrderEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setStockInNo("SI202608210001");
        entity.setStockInTypeCode(stockInTypeCode);
        entity.setProcurementOrderId(44L);
        entity.setProcurementNo("PO202608210001");
        entity.setWarehouseId(9L);
        entity.setSupplierId(8L);
        entity.setUpdatedBy("SYSTEM");
        entity.setRevision(1);
        entity.setDeleted(0);
        return entity;
    }

    private static MasterSourceBindingEntity binding(String sourceType, String targetType,
                                                     String sourceId, Long targetId,
                                                     String sourceName) {
        MasterSourceBindingEntity entity = new MasterSourceBindingEntity();
        entity.id = UUID.randomUUID().toString();
        entity.tenantId = TENANT_ID;
        entity.connectorId = CONNECTOR_ID.toString();
        entity.sourceSystem = "DINGHUOBAO";
        entity.sourceObjectType = sourceType;
        entity.sourceObjectId = sourceId;
        entity.targetType = targetType;
        entity.targetId = targetId.toString();
        entity.sourceName = sourceName;
        entity.sourcePresence = "PRESENT";
        entity.deleted = 0;
        return entity;
    }
}

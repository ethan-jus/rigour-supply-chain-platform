package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.WarehouseView;
import com.rigour.erp.api.v1.model.SupplierView;
import com.rigour.erp.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseReturnEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseReturnLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.SupplierEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingPurchaseLinkEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingReceiptEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingReceiptLineEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseReturnLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseReturnMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SupplierMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingPurchaseLinkMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingReceiptLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingReceiptMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ERP 供应链查询排序回归测试。 */
@ExtendWith(MockitoExtension.class)
class MybatisPlusSupplyDataRepositoryTest {
    @Mock private SupplierMapper supplierMapper;
    @Mock private WarehouseMapper warehouseMapper;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private PurchaseOrderLineMapper purchaseOrderLineMapper;
    @Mock private PurchaseReturnMapper purchaseReturnMapper;
    @Mock private PurchaseReturnLineMapper purchaseReturnLineMapper;
    @Mock private WarehousingReceiptMapper warehousingReceiptMapper;
    @Mock private WarehousingReceiptLineMapper warehousingReceiptLineMapper;
    @Mock private WarehousingPurchaseLinkMapper warehousingPurchaseLinkMapper;
    @Mock private InventoryBalanceMapper inventoryBalanceMapper;
    @Mock private MasterSourceBindingMapper bindingMapper;
    @Mock private MasterDataSyncRunMapper syncRunMapper;
    @Mock private MasterDataSyncLockMapper syncLockMapper;
    @Mock private Clock clock;
    @InjectMocks private MybatisPlusSupplyDataRepository repository;

    @Test
    void returnsFullSupplierBankAccountFromLocalProjection() {
        SupplierEntity supplier = new SupplierEntity();
        supplier.id = "supplier-1";
        supplier.tenantId = "tenant-id";
        supplier.supplierCode = "SUP-001";
        supplier.name = "供应商一";
        supplier.address = "完整地址";
        supplier.mobile = "13800000000";
        supplier.phone = "021-12345678";
        supplier.email = "supplier@test.com";
        supplier.bankName = "测试银行";
        supplier.bankAccount = "6222000012345678";
        supplier.taxpayerNumber = "91310000";
        when(supplierMapper.selectCount(any())).thenReturn(1L);
        when(supplierMapper.selectList(any())).thenReturn(List.of(supplier));

        var result = repository.suppliers("tenant-id", 0, 20, null, null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item).isInstanceOf(SupplierView.class);
            assertThat(((SupplierView) item).address()).isEqualTo("完整地址");
            assertThat(((SupplierView) item).mobile()).isEqualTo("13800000000");
            assertThat(((SupplierView) item).phone()).isEqualTo("021-12345678");
            assertThat(((SupplierView) item).email()).isEqualTo("supplier@test.com");
            assertThat(((SupplierView) item).bankAccount()).isEqualTo("6222000012345678");
            assertThat(((SupplierView) item).taxpayerNumber()).isEqualTo("91310000");
        });
    }

    @Test
    void ordersWarehousesByWarehouseCodeAscendingBeforeApplyingPageLimit() {
        when(warehouseMapper.selectCount(any())).thenReturn(0L);
        when(warehouseMapper.selectList(any())).thenReturn(List.of());

        repository.warehouses("tenant-id", 20, 10, null, null);

        ArgumentCaptor<QueryWrapper<WarehouseEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(warehouseMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("ORDER BY CAST(warehouse_code AS UNSIGNED) ASC")
                .contains("warehouse_code ASC")
                .contains("id ASC")
                .contains("LIMIT 20,10");
    }

    @Test
    void ordersInventoryBalancesByWarehouseCodeAscendingBeforeApplyingPageLimit() {
        when(inventoryBalanceMapper.selectCount(any())).thenReturn(0L);
        when(inventoryBalanceMapper.selectList(any())).thenReturn(List.of());

        repository.inventory("tenant-id", 20, 10, null, null);

        ArgumentCaptor<QueryWrapper<InventoryBalanceEntity>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(inventoryBalanceMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("ORDER BY CAST(source_warehouse_code AS UNSIGNED) ASC")
                .contains("source_warehouse_code ASC")
                .contains("id ASC")
                .contains("LIMIT 20,10");
    }

    @Test
    void translatesWarehouseSourceStatusForListResponse() {
        WarehouseEntity active = new WarehouseEntity();
        active.id = "warehouse-1";
        active.warehouseCode = "1";
        active.sourceStatus = "T";
        active.remark = "主仓";
        WarehouseEntity disabled = new WarehouseEntity();
        disabled.id = "warehouse-2";
        disabled.warehouseCode = "2";
        disabled.sourceStatus = "F";
        when(warehouseMapper.selectCount(any())).thenReturn(2L);
        when(warehouseMapper.selectList(any())).thenReturn(List.of(active, disabled));

        var result = repository.warehouses("tenant-id", 0, 10, null, null);

        assertThat(result.items()).extracting(WarehouseView::sourceStatus)
                .containsExactly("正常", "停用");
        assertThat(result.items()).extracting(WarehouseView::remark)
                .containsExactly("主仓", (String) null);
    }

    @Test
    void loadsPurchaseOrderDetailFromTenantScopedLocalLines() {
        PurchaseOrderEntity order = new PurchaseOrderEntity();
        order.id = "order-1";
        order.tenantId = "tenant-id";
        order.purchaseOrderNo = "PO-1";
        order.sourcePurchaseId = "PURCHASE-1";
        order.sourceSupplierId = "SUP-1";
        order.sourceWarehouseId = "WH-1";
        order.supplierNameSnapshot = "供应商一";
        order.remark = "订单备注";
        PurchaseOrderLineEntity line = new PurchaseOrderLineEntity();
        line.id = "line-1";
        line.purchaseOrderId = order.id;
        line.sourceGoodsCode = "G-1";
        line.sourceGoodsName = "商品一";
        line.baseQuantity = java.math.BigDecimal.TEN;
        when(purchaseOrderMapper.selectOne(any())).thenReturn(order);
        when(purchaseOrderLineMapper.selectList(any())).thenReturn(List.of(line));

        var result = repository.purchaseOrder("tenant-id", order.id);

        assertThat(result.sourceId()).isEqualTo("PURCHASE-1");
        assertThat(result.supplierSourceId()).isEqualTo("SUP-1");
        assertThat(result.warehouseSourceId()).isEqualTo("WH-1");
        assertThat(result.number()).isEqualTo("PO-1");
        assertThat(result.remark()).isEqualTo("订单备注");
        assertThat(result.lines()).singleElement().satisfies(item -> {
            assertThat(item.goodsCode()).isEqualTo("G-1");
            assertThat(item.baseQuantity()).isEqualByComparingTo("10");
        });
    }

    @Test
    void loadsPurchaseReturnDetailFromTenantScopedLocalLines() {
        PurchaseReturnEntity document = new PurchaseReturnEntity();
        document.id = "return-1";
        document.tenantId = "tenant-id";
        document.purchaseReturnNo = "PR-1";
        document.sourceReturnId = "RETURN-1";
        document.sourceSupplierId = "SUP-2";
        document.sourceWarehouseId = "WH-2";
        document.contactName = "联系人";
        PurchaseReturnLineEntity line = new PurchaseReturnLineEntity();
        line.id = "return-line-1";
        line.purchaseReturnId = document.id;
        line.sourceGoodsCode = "G-2";
        line.sourceGoodsName = "商品二";
        line.requestedQuantity = java.math.BigDecimal.ONE;
        when(purchaseReturnMapper.selectOne(any())).thenReturn(document);
        when(purchaseReturnLineMapper.selectList(any())).thenReturn(List.of(line));

        var result = repository.purchaseReturn("tenant-id", document.id);

        assertThat(result.sourceId()).isEqualTo("RETURN-1");
        assertThat(result.supplierSourceId()).isEqualTo("SUP-2");
        assertThat(result.warehouseSourceId()).isEqualTo("WH-2");
        assertThat(result.number()).isEqualTo("PR-1");
        assertThat(result.contactName()).isEqualTo("联系人");
        assertThat(result.lines()).singleElement().satisfies(item -> {
            assertThat(item.goodsCode()).isEqualTo("G-2");
            assertThat(item.requestedQuantity()).isEqualByComparingTo("1");
        });
    }

    @Test
    void loadsWarehousingReceiptDetailWithLinesAndPurchaseLinks() {
        WarehousingReceiptEntity receipt = new WarehousingReceiptEntity();
        receipt.id = "receipt-1";
        receipt.tenantId = "tenant-id";
        receipt.sourceWarehousingId = "WAREHOUSING-1";
        receipt.warehousingNo = "WH-1";
        receipt.sourceWarehouseId = "WH-SOURCE-1";
        receipt.sourceSupplierId = "SUP-SOURCE-1";
        receipt.warehouseNameSnapshot = "一号仓";
        receipt.supplierNameSnapshot = "供应商一";
        receipt.remark = "入库备注";
        WarehousingReceiptLineEntity line = new WarehousingReceiptLineEntity();
        line.id = "receipt-line-1";
        line.warehousingReceiptId = receipt.id;
        line.sourceGoodsId = "GOODS-1";
        line.sourceGoodsCode = "G-1";
        line.sourceGoodsName = "商品一";
        line.unitQuantity = java.math.BigDecimal.TEN;
        WarehousingPurchaseLinkEntity link = new WarehousingPurchaseLinkEntity();
        link.sourcePurchaseId = "PURCHASE-1";
        link.purchaseOrderNo = "PO-1";
        when(warehousingReceiptMapper.selectOne(any())).thenReturn(receipt);
        when(warehousingReceiptLineMapper.selectList(any())).thenReturn(List.of(line));
        when(warehousingPurchaseLinkMapper.selectList(any())).thenReturn(List.of(link));

        var result = repository.warehousingReceipt("tenant-id", receipt.id);

        assertThat(result.sourceId()).isEqualTo("WAREHOUSING-1");
        assertThat(result.warehouseSourceId()).isEqualTo("WH-SOURCE-1");
        assertThat(result.remark()).isEqualTo("入库备注");
        assertThat(result.lines()).singleElement().satisfies(item -> {
            assertThat(item.sourceGoodsId()).isEqualTo("GOODS-1");
            assertThat(item.unitQuantity()).isEqualByComparingTo("10");
        });
        assertThat(result.purchaseLinks()).singleElement().satisfies(item -> {
            assertThat(item.sourcePurchaseId()).isEqualTo("PURCHASE-1");
            assertThat(item.purchaseOrderNo()).isEqualTo("PO-1");
        });
    }
}

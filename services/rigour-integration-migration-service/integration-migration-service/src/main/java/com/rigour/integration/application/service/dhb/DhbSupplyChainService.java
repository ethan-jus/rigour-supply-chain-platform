package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbInventoryBalanceView;
import com.rigour.integration.api.v1.model.DhbInventoryQueryCommand;
import com.rigour.integration.api.v1.model.DhbInventoryView;
import com.rigour.integration.api.v1.model.DhbPurchaseLinkView;
import com.rigour.integration.api.v1.model.DhbPurchaseOrderLineView;
import com.rigour.integration.api.v1.model.DhbPurchaseOrderView;
import com.rigour.integration.api.v1.model.DhbPurchaseReturnLineView;
import com.rigour.integration.api.v1.model.DhbPurchaseReturnView;
import com.rigour.integration.api.v1.model.DhbSupplierView;
import com.rigour.integration.api.v1.model.DhbSupplyPageQueryCommand;
import com.rigour.integration.api.v1.model.DhbSupplyPageView;
import com.rigour.integration.api.v1.model.DhbWarehouseView;
import com.rigour.integration.api.v1.model.DhbWarehousingLineView;
import com.rigour.integration.api.v1.model.DhbWarehousingReceiptView;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 订货宝采购与库存归一化用例；负责 Raw Landing 和供应商数据跨服务转换。 */
@Service
public final class DhbSupplyChainService {
    private static final int PURCHASE_RETURN_PAGE_SIZE = 99;
    private static final Logger log = LoggerFactory.getLogger(DhbSupplyChainService.class);
    private final DhbClient client;
    private final DhbIntegrationStore store;

    public DhbSupplyChainService(DhbClient client, DhbIntegrationStore store) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.store = Objects.requireNonNull(store, "store cannot be null");
    }

    public DhbSupplyPageView<DhbSupplierView> suppliers(
            UUID connectorId, DhbSupplyPageQueryCommand command) {
        CallerIdentity caller = caller();
        PageRequest request = page(command);
        Page<DhbClient.Supplier> page = client.getSuppliers(connector(caller, connectorId), request);
        page.items().forEach(item -> landing(caller, connectorId, "SUPPLIER", item.sourceId(),
                item.sourceUpdatedAt(), item.attributes()));
        log.info("订货宝供应商归一化完成 tenantId={} connectorId={} begin={} step={} returned={} total={}",
                caller.tenantId(), connectorId, request.begin(), request.step(), page.items().size(), page.total());
        return new DhbSupplyPageView<>(page.total(), page.items().stream()
                .map(DhbSupplyChainService::supplier).toList());
    }

    public DhbSupplyPageView<DhbPurchaseOrderView> purchaseOrders(
            UUID connectorId, DhbSupplyPageQueryCommand command) {
        CallerIdentity caller = caller();
        PageRequest request = page(command);
        Page<DhbClient.PurchaseOrder> page = client.getPurchaseOrders(connector(caller, connectorId), request);
        page.items().forEach(item -> landing(caller, connectorId, "PURCHASE_ORDER", item.sourceId(),
                item.updatedAt(), item.attributes()));
        log.info("订货宝采购单归一化完成 tenantId={} connectorId={} begin={} step={} returned={} total={}",
                caller.tenantId(), connectorId, request.begin(), request.step(), page.items().size(), page.total());
        return new DhbSupplyPageView<>(page.total(), page.items().stream()
                .map(DhbSupplyChainService::purchaseOrder).toList());
    }

    public DhbSupplyPageView<DhbPurchaseReturnView> purchaseReturns(
            UUID connectorId, DhbSupplyPageQueryCommand command) {
        CallerIdentity caller = caller();
        // getPurchaseReturnList rejects page sizes greater than 99, unlike most DHB list APIs.
        PageRequest request = page(command, PURCHASE_RETURN_PAGE_SIZE);
        Page<DhbClient.PurchaseReturn> page = client.getPurchaseReturns(connector(caller, connectorId), request);
        page.items().forEach(item -> landing(caller, connectorId, "PURCHASE_RETURN", item.sourceId(),
                item.createdAt(), item.attributes()));
        log.info("订货宝采购退货单归一化完成 tenantId={} connectorId={} begin={} step={} returned={} total={}",
                caller.tenantId(), connectorId, request.begin(), request.step(), page.items().size(), page.total());
        return new DhbSupplyPageView<>(page.total(), page.items().stream()
                .map(DhbSupplyChainService::purchaseReturn).toList());
    }

    public DhbSupplyPageView<DhbWarehousingReceiptView> warehousingReceipts(
            UUID connectorId, DhbSupplyPageQueryCommand command) {
        CallerIdentity caller = caller();
        PageRequest request = page(command);
        Page<DhbClient.WarehousingReceipt> page = client.getWarehousingReceipts(
                connector(caller, connectorId), request);
        page.items().forEach(item -> landing(caller, connectorId, "WAREHOUSING_RECEIPT",
                item.sourceId(), item.updatedAt(), item.attributes()));
        log.info("订货宝入库单归一化完成 tenantId={} connectorId={} begin={} step={} returned={} total={}",
                caller.tenantId(), connectorId, request.begin(), request.step(), page.items().size(), page.total());
        return new DhbSupplyPageView<>(page.total(), page.items().stream()
                .map(DhbSupplyChainService::warehousingReceipt).toList());
    }

    public DhbSupplyPageView<DhbWarehouseView> warehouses(
            UUID connectorId, DhbSupplyPageQueryCommand command) {
        CallerIdentity caller = caller();
        PageRequest request = page(command);
        Page<DhbClient.Warehouse> page = client.getWarehouses(connector(caller, connectorId), request);
        page.items().forEach(item -> landing(caller, connectorId, "WAREHOUSE", item.sourceId(),
                Instant.now(), item.attributes()));
        log.info("订货宝仓库归一化完成 tenantId={} connectorId={} begin={} step={} returned={} total={}",
                caller.tenantId(), connectorId, request.begin(), request.step(), page.items().size(), page.total());
        return new DhbSupplyPageView<>(page.total(), page.items().stream()
                .map(DhbSupplyChainService::warehouse).toList());
    }

    public DhbInventoryView inventory(UUID connectorId, DhbInventoryQueryCommand command) {
        CallerIdentity caller = caller();
        if (command == null || command.goodsCodes().isEmpty()) {
            throw new IllegalArgumentException("goodsCodes不能为空");
        }
        if (command.goodsCodes().size() > 100) {
            throw new IllegalArgumentException("单次batchGetStock最多传100个商品编码");
        }
        List<DhbClient.InventoryBalance> items = client.getInventory(
                connector(caller, connectorId), command.goodsCodes());
        items.forEach(item -> landing(caller, connectorId, "INVENTORY_BALANCE",
                inventoryKey(item), Instant.now(), item.attributes()));
        log.info("订货宝库存归一化完成 tenantId={} connectorId={} requestedGoods={} returned={}",
                caller.tenantId(), connectorId, command.goodsCodes().size(), items.size());
        return new DhbInventoryView(items.stream().map(DhbSupplyChainService::inventory).toList());
    }

    private void landing(CallerIdentity caller, UUID connectorId, String type, String sourceId,
                         Instant updatedAt, Map<String, Object> payload) {
        store.persistRawLanding(caller.tenantId(), connectorId, type,
                sourceId == null || sourceId.isBlank() ? UUID.randomUUID().toString() : sourceId,
                updatedAt, payload == null ? Map.of() : payload);
    }

    private DhbClient.Connector connector(CallerIdentity caller, UUID connectorId) {
        ConnectorView view = store.connector(caller.tenantId(), connectorId);
        return new DhbClient.Connector(caller.tenantId(), view.id(), view.baseUrl(), view.authSecretRef());
    }

    private static PageRequest page(DhbSupplyPageQueryCommand command) {
        return page(command, 1000);
    }

    private static PageRequest page(DhbSupplyPageQueryCommand command, int maximumStep) {
        int begin = command == null ? 0 : command.effectiveBegin();
        int step = Math.min(command == null ? 200 : command.effectiveStep(), maximumStep);
        return new PageRequest(begin, step);
    }

    private static CallerIdentity caller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        boolean allowed = caller.tenantId() != null && (("TENANT".equals(caller.principalScope())
                && caller.userId() != null) || ("SERVICE".equals(caller.principalScope())
                && caller.userId() == null));
        if (!allowed) throw new com.rigour.shared.context.AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("integration:dhb:read");
        return caller;
    }

    private static DhbSupplierView supplier(DhbClient.Supplier item) {
        return new DhbSupplierView(item.sourceId(), item.sourceGuid(), item.code(), item.name(),
                item.areaName(), item.address(), item.contactName(), item.mobile(), item.phone(), item.email(),
                item.accountName(), item.bankName(), item.bankAccount(), item.invoiceTitle(), item.taxpayerNumber(),
                item.remark(), item.sourceUpdatedAt(), Map.of());
    }

    private static DhbWarehouseView warehouse(DhbClient.Warehouse item) {
        return new DhbWarehouseView(item.sourceId(), item.sourceGuid(), item.code(), item.name(),
                item.status(), item.defaultFlag(), item.acreage(), maskPhone(item.phone()), item.address(),
                item.collaboratorSourceId(), item.remark(), Map.of());
    }

    private static DhbPurchaseOrderView purchaseOrder(DhbClient.PurchaseOrder item) {
        return new DhbPurchaseOrderView(item.sourceId(), item.number(), item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), item.warehouseSourceId(), item.warehouseCode(),
                item.warehouseName(), item.staffSourceId(), item.staffName(), item.status(), item.statusName(),
                item.paymentStatus(), item.paymentStatusName(), item.deliveryAt(), item.createdAt(), item.updatedAt(),
                item.totalAmount(), item.paidAmount(), item.goodsCount(), item.downloaded(), item.remark(),
                item.internalCommunication(), item.lines().stream().map(line -> new DhbPurchaseOrderLineView(
                line.sourceLineId(), line.sourceGoodsId(), line.sourceGoodsGuid(), line.goodsCode(),
                line.goodsName(), line.optionsId(), line.optionsGoodsCode(), line.optionsSummary(),
                line.baseQuantity(), line.unitPrice(), line.purchaseUnitCode(), line.purchaseUnitName(),
                line.purchaseUnitQuantity(), line.warehousedQuantity(), line.returnedQuantity(),
                line.remark(), Map.of())).toList(), Map.of());
    }

    private static DhbPurchaseReturnView purchaseReturn(DhbClient.PurchaseReturn item) {
        return new DhbPurchaseReturnView(item.sourceId(), item.number(), item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), item.warehouseSourceId(), item.warehouseCode(),
                item.warehouseName(), item.staffSourceId(), item.staffName(), item.status(), item.statusName(),
                item.returnAmount(), item.discountAmount(), item.reason(), item.createdAt(), item.sendAt(),
                item.internalCommunication(), item.remark(), item.detailCount(), item.contactName(),
                maskPhone(item.contactPhone()), maskAddress(item.contactAddress()), item.cityIds(), item.cityNames(),
                item.sourceDevice(), item.parentReturnSourceId(), item.parentCompanySourceId(), item.downloaded(),
                item.lines().stream().map(line -> new DhbPurchaseReturnLineView(line.sourceLineId(),
                line.sourceGoodsId(), line.goodsCode(), line.goodsName(), line.optionsId(),
                line.optionsGoodsCode(), line.optionsSummary(), line.requestedQuantity(),
                line.confirmedQuantity(), line.returnPrice(), line.confirmedPrice(), line.unitCode(),
                line.unitName(), line.unitQuantity(), line.confirmedUnitQuantity(), line.conversionNumber(),
                line.amount(), line.costPrice(), line.purchaseOrderNo(), line.categoryName(), line.brandName(),
                line.remark(), Map.of())).toList(), Map.of());
    }

    private static DhbWarehousingReceiptView warehousingReceipt(DhbClient.WarehousingReceipt item) {
        return new DhbWarehousingReceiptView(item.sourceId(), item.number(), item.warehouseSourceId(),
                item.warehouseName(), item.supplierSourceId(), item.supplierName(), item.typeId(), item.typeName(),
                item.status(), item.statusName(), item.staffName(), item.clientSourceId(), item.accountSourceId(),
                item.collaboratorSourceId(), item.collaboratorName(), item.logisticsSourceId(), item.expressNumber(),
                item.storageAt(), item.createdAt(), item.updatedAt(), item.freightAmount(), item.totalAmount(),
                item.costAmount(), item.apiFlag(), item.splitType(), item.remark(), item.lines().stream().map(line ->
                new DhbWarehousingLineView(line.sourceLineId(), line.sourceGoodsId(), line.goodsCode(), line.goodsName(),
                line.optionsId(), line.optionsGoodsCode(), line.optionsSummary(), line.baseQuantity(),
                line.unitQuantity(), line.unitCode(), line.unitName(), line.conversionNumber(), line.costPrice(),
                line.unitCostPrice(), line.purchasePrice(), line.wholesalePrice(), line.allocation(), line.barcode(),
                line.goodsModel(), line.sourceRealQuantity(), line.sourceAvailableQuantity(),
                line.collaboratorSourceId(), line.collaboratorName(), line.remark(), Map.of())).toList(),
                item.purchaseLinks().stream().map(link -> new DhbPurchaseLinkView(
                        link.sourcePurchaseId(), link.purchaseOrderNo())).toList(), Map.of());
    }

    private static DhbInventoryBalanceView inventory(DhbClient.InventoryBalance item) {
        return new DhbInventoryBalanceView(item.goodsGuid(), item.goodsCode(), item.goodsName(),
                item.warehouseGuid(), item.warehouseCode(), item.warehouseName(), item.firstOptionGuid(),
                item.firstOptionCode(), item.firstOptionName(), item.secondOptionGuid(), item.secondOptionCode(),
                item.secondOptionName(), item.availableQuantity(), item.realQuantity(), Map.of());
    }

    private static String inventoryKey(DhbClient.InventoryBalance item) {
        return String.join("|", safe(item.warehouseGuid(), item.warehouseCode()),
                safe(item.goodsGuid(), item.goodsCode()), safe(item.firstOptionGuid(), item.firstOptionCode()),
                safe(item.secondOptionGuid(), item.secondOptionCode()));
    }

    private static String safe(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "BASE" : second);
    }

    private static String maskPhone(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        if (text.length() <= 4) return "****";
        return text.substring(0, Math.min(3, text.length())) + "****" + text.substring(text.length() - 4);
    }

    private static String maskEmail(String value) {
        if (value == null || value.isBlank()) return null;
        int at = value.indexOf('@');
        return at <= 0 ? "***" : value.substring(0, 1) + "***" + value.substring(at);
    }

    private static String maskAddress(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() <= 6 ? "******" : text.substring(0, Math.min(6, text.length())) + "***";
    }

    private static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() <= 8 ? "********" : text.substring(0, 4) + "********" + text.substring(text.length() - 4);
    }

    private static String last4(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.substring(Math.max(0, text.length() - 4));
    }
}

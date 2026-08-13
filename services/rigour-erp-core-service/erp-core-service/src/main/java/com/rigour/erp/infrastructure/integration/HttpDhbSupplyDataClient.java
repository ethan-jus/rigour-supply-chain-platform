package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.integration.api.v1.DhbSupplyChainApi;
import com.rigour.integration.api.v1.model.DhbInventoryQueryCommand;
import com.rigour.integration.api.v1.model.DhbInventoryView;
import com.rigour.integration.api.v1.model.DhbPurchaseOrderView;
import com.rigour.integration.api.v1.model.DhbPurchaseReturnView;
import com.rigour.integration.api.v1.model.DhbSupplierView;
import com.rigour.integration.api.v1.model.DhbSupplyPageQueryCommand;
import com.rigour.integration.api.v1.model.DhbSupplyPageView;
import com.rigour.integration.api.v1.model.DhbWarehouseView;
import com.rigour.integration.api.v1.model.DhbWarehousingReceiptView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** 通过 Integration V1 契约收集订货宝采购与库存数据。 */
public final class HttpDhbSupplyDataClient implements DhbSupplyDataClient {
    private static final int PAGE_SIZE = 200;
    private static final int PURCHASE_RETURN_PAGE_SIZE = 99;
    private static final int INVENTORY_BATCH_SIZE = 100;
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final ObjectMapper objectMapper;
    private final URI integrationBaseUri;

    public HttpDhbSupplyDataClient(RestClient.Builder builder, TrustedContextSigner signer,
                                   ObjectMapper objectMapper, String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "signer不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.integrationBaseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }

    @Override
    public Collected collect(CallerIdentity caller, UUID connectorId, SupplyDataObjectType type,
                             int maxPages, List<String> inventoryGoodsCodes) {
        return switch (type) {
            case SUPPLIER -> suppliers(caller, connectorId, maxPages);
            case PURCHASE_ORDER -> purchaseOrders(caller, connectorId, maxPages);
            case PURCHASE_RETURN -> purchaseReturns(caller, connectorId, maxPages);
            case WAREHOUSING_RECEIPT -> warehousing(caller, connectorId, maxPages);
            case WAREHOUSE -> warehouses(caller, connectorId, maxPages);
            case INVENTORY -> inventory(caller, connectorId, maxPages, inventoryGoodsCodes);
        };
    }

    private Collected suppliers(CallerIdentity caller, UUID id, int maxPages) {
        List<Supplier> result = collectPages(caller, id, "suppliers", maxPages, PAGE_SIZE,
                new ParameterizedTypeReference<DhbSupplyPageView<DhbSupplierView>>() { },
                item -> new Supplier(item.sourceId(), item.sourceGuid(), item.code(), item.name(),
                        item.areaName(), item.address(), item.contactName(), item.mobile(), item.phone(), item.email(),
                        item.accountName(), item.bankName(), item.bankAccount(), item.invoiceTitle(), item.taxpayerNumber(),
                        item.remark(), item.sourceUpdatedAt(), item.sourceFields(), hash(item)));
        return collected(SupplyDataObjectType.SUPPLIER, result.size(), pages(result.size()), result,
                null, null, null, null, null);
    }

    private Collected purchaseOrders(CallerIdentity caller, UUID id, int maxPages) {
        List<PurchaseOrder> result = collectPages(caller, id, "purchase-orders", maxPages, PAGE_SIZE,
                new ParameterizedTypeReference<DhbSupplyPageView<DhbPurchaseOrderView>>() { },
                item -> new PurchaseOrder(item.sourceId(), item.number(), item.supplierSourceId(),
                        item.supplierCode(), item.supplierName(), item.warehouseSourceId(),
                        item.warehouseCode(), item.warehouseName(), item.staffSourceId(), item.staffName(),
                        item.sourceStatus(), item.sourceStatusName(), item.paymentStatus(), item.paymentStatusName(),
                        item.deliveryAt(), item.sourceCreatedAt(), item.sourceUpdatedAt(), item.totalAmount(),
                        item.paidAmount(), item.goodsCount(), item.downloaded(), item.remark(),
                        item.internalCommunication(), item.lines().stream().map(line -> new PurchaseOrder.Line(
                        line.sourceLineId(), line.sourceGoodsId(), line.sourceGoodsGuid(), line.goodsCode(),
                        line.goodsName(), line.optionsId(), line.optionsGoodsCode(), line.optionsSummary(),
                        line.baseQuantity(), line.unitPrice(), line.purchaseUnitCode(), line.purchaseUnitName(),
                        line.purchaseUnitQuantity(), line.warehousedQuantity(), line.returnedQuantity(),
                        line.remark(), line.sourceFields(), hash(line))).toList(), item.sourceFields(), hash(item)));
        return collected(SupplyDataObjectType.PURCHASE_ORDER, result.size(), pages(result.size()), null,
                result, null, null, null, null);
    }

    private Collected purchaseReturns(CallerIdentity caller, UUID id, int maxPages) {
        List<PurchaseReturn> result = collectPages(caller, id, "purchase-returns", maxPages,
                PURCHASE_RETURN_PAGE_SIZE,
                new ParameterizedTypeReference<DhbSupplyPageView<DhbPurchaseReturnView>>() { },
                item -> new PurchaseReturn(item.sourceId(), item.number(), item.supplierSourceId(),
                        item.supplierCode(), item.supplierName(), item.warehouseSourceId(), item.warehouseCode(),
                        item.warehouseName(), item.staffSourceId(), item.staffName(), item.sourceStatus(),
                        item.sourceStatusName(), item.returnAmount(), item.discountAmount(), item.reason(),
                        item.sourceCreatedAt(), item.sendAt(), item.internalCommunication(), item.remark(),
                        item.detailCount(), item.contactName(), item.contactPhone(), item.contactAddress(),
                        item.cityIds(), item.cityNames(), item.sourceDevice(), item.parentReturnSourceId(),
                        item.parentCompanySourceId(), item.downloaded(), item.lines().stream().map(line ->
                        new PurchaseReturn.Line(line.sourceLineId(), line.sourceGoodsId(), line.goodsCode(),
                        line.goodsName(), line.optionsId(), line.optionsGoodsCode(), line.optionsSummary(),
                        line.requestedQuantity(), line.confirmedQuantity(), line.returnPrice(),
                        line.confirmedPrice(), line.unitCode(), line.unitName(), line.unitQuantity(),
                        line.confirmedUnitQuantity(), line.conversionNumber(), line.amount(), line.costPrice(),
                        line.purchaseOrderNo(), line.categoryName(), line.brandName(), line.remark(),
                        line.sourceFields(), hash(line))).toList(), item.sourceFields(), hash(item)));
        return collected(SupplyDataObjectType.PURCHASE_RETURN, result.size(),
                pages(result.size(), PURCHASE_RETURN_PAGE_SIZE), null,
                null, result, null, null, null);
    }

    private Collected warehousing(CallerIdentity caller, UUID id, int maxPages) {
        List<WarehousingReceipt> result = collectPages(caller, id, "warehousing-receipts", maxPages, PAGE_SIZE,
                new ParameterizedTypeReference<DhbSupplyPageView<DhbWarehousingReceiptView>>() { },
                item -> new WarehousingReceipt(item.sourceId(), item.number(), item.warehouseSourceId(),
                        item.warehouseName(), item.supplierSourceId(), item.supplierName(), item.typeId(),
                        item.typeName(), item.sourceStatus(), item.sourceStatusName(), item.staffName(),
                        item.clientSourceId(), item.accountSourceId(), item.collaboratorSourceId(),
                        item.collaboratorName(), item.logisticsSourceId(), item.expressNumber(), item.storageAt(),
                        item.sourceCreatedAt(), item.sourceUpdatedAt(), item.freightAmount(), item.totalAmount(),
                        item.costAmount(), item.apiFlag(), item.splitType(), item.remark(), item.lines().stream().map(line ->
                        new WarehousingReceipt.Line(line.sourceLineId(), line.sourceGoodsId(), line.goodsCode(),
                        line.goodsName(), line.optionsId(), line.optionsGoodsCode(), line.optionsSummary(),
                        line.baseQuantity(), line.unitQuantity(), line.unitCode(), line.unitName(),
                        line.conversionNumber(), line.costPrice(), line.unitCostPrice(), line.purchasePrice(),
                        line.wholesalePrice(), line.allocation(), line.barcode(), line.goodsModel(),
                        line.sourceRealQuantity(), line.sourceAvailableQuantity(), line.collaboratorSourceId(),
                        line.collaboratorName(), line.remark(), line.sourceFields(), hash(line))).toList(), item.purchaseLinks().stream()
                        .map(link -> new WarehousingReceipt.PurchaseLink(
                                link.sourcePurchaseId(), link.purchaseOrderNo())).toList(), item.sourceFields(), hash(item)));
        return collected(SupplyDataObjectType.WAREHOUSING_RECEIPT, result.size(), pages(result.size()), null,
                null, null, result, null, null);
    }

    private Collected warehouses(CallerIdentity caller, UUID id, int maxPages) {
        List<Warehouse> result = collectPages(caller, id, "warehouses", maxPages, PAGE_SIZE,
                new ParameterizedTypeReference<DhbSupplyPageView<DhbWarehouseView>>() { },
                item -> new Warehouse(item.sourceId(), item.sourceGuid(), item.code(), item.name(),
                        item.sourceStatus(), item.defaultFlag(), item.acreage(), item.phone(),
                        item.address(), item.collaboratorSourceId(), item.remark(), item.sourceFields(), hash(item)));
        return collected(SupplyDataObjectType.WAREHOUSE, result.size(), pages(result.size()), null,
                null, null, null, result, null);
    }

    private Collected inventory(CallerIdentity caller, UUID id, int maxPages, List<String> codes) {
        List<String> values = codes == null ? List.of() : codes.stream().filter(Objects::nonNull)
                .map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
        if (values.isEmpty()) throw new IllegalStateException("库存同步前必须先同步商品，当前没有订货宝商品来源编码");
        int required = (values.size() + INVENTORY_BATCH_SIZE - 1) / INVENTORY_BATCH_SIZE;
        if (required > maxPages) throw new IllegalStateException("库存同步需要" + required
                + "个批次，超过maxPages=" + maxPages);
        List<InventoryBalance> result = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += INVENTORY_BATCH_SIZE) {
            List<String> batch = values.subList(offset, Math.min(values.size(), offset + INVENTORY_BATCH_SIZE));
            DhbInventoryView response = post(caller, path(id, "inventory", "query"),
                    new DhbInventoryQueryCommand(batch), DhbInventoryView.class);
            response.items().stream().map(item -> new InventoryBalance(item.goodsGuid(), item.goodsCode(),
                    item.goodsName(), item.warehouseGuid(), item.warehouseCode(), item.warehouseName(),
                    item.firstOptionGuid(), item.firstOptionCode(), item.firstOptionName(),
                    item.secondOptionGuid(), item.secondOptionCode(), item.secondOptionName(),
                    item.availableQuantity(), item.realQuantity(), item.sourceFields(), hash(item))).forEach(result::add);
        }
        return collected(SupplyDataObjectType.INVENTORY, result.size(), required, null,
                null, null, null, null, result);
    }

    private <S, T> List<T> collectPages(CallerIdentity caller, UUID connectorId, String segment,
                                        int maxPages, int pageSize,
                                        ParameterizedTypeReference<DhbSupplyPageView<S>> type,
                                        java.util.function.Function<S, T> mapper) {
        List<T> result = new ArrayList<>();
        long total = -1;
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            int begin = pageNumber * pageSize;
            DhbSupplyPageView<S> page = post(caller, path(connectorId, segment, "query"),
                    new DhbSupplyPageQueryCommand(begin, pageSize), type);
            if (pageNumber == 0) total = page.total();
            List<S> items = page.items() == null ? List.of() : page.items();
            items.stream().map(mapper).forEach(result::add);
            boolean complete = total >= 0 ? begin + pageSize >= total : items.size() < pageSize;
            if (complete) {
                return result;
            }
        }
        throw new IllegalStateException("订货宝" + segment + "同步达到maxPages=" + maxPages + "但仍有后续数据");
    }

    private int pages(int size) { return pages(size, PAGE_SIZE); }

    private int pages(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    private URI path(UUID connectorId, String... suffix) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbSupplyChainApi.BASE_PATH).pathSegment(connectorId.toString());
        for (String segment : suffix) builder.pathSegment(segment);
        return builder.build().encode().toUri();
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, Class<T> type) {
        Map<String, String> headers = SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller);
        return restClient.post().uri(uri).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON).headers(target -> headers.forEach(target::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId()).body(body)
                .retrieve().body(type);
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, ParameterizedTypeReference<T> type) {
        Map<String, String> headers = SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller);
        return restClient.post().uri(uri).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON).headers(target -> headers.forEach(target::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId()).body(body)
                .retrieve().body(type);
    }

    private String hash(Object value) {
        return StablePayloadHasher.sha256(objectMapper, value);
    }

    private static Collected collected(SupplyDataObjectType type, long total, int pages,
                                       List<Supplier> suppliers, List<PurchaseOrder> orders,
                                       List<PurchaseReturn> returns, List<WarehousingReceipt> receipts,
                                       List<Warehouse> warehouses, List<InventoryBalance> inventory) {
        return new Collected(type, total, pages, suppliers, orders, returns, receipts, warehouses, inventory);
    }
}

package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbClient.Customer;
import com.rigour.integration.application.port.out.DhbClient.CustomerQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderDetail;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Payment;
import com.rigour.integration.application.port.out.DhbClient.PaymentQuery;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.Product;
import com.rigour.integration.application.port.out.DhbClient.ProductBrand;
import com.rigour.integration.application.port.out.DhbClient.ProductCategory;
import com.rigour.integration.application.port.out.DhbClient.ProductQuery;
import com.rigour.integration.application.port.out.DhbClient.ProductSpecification;
import com.rigour.integration.application.port.out.DhbClient.ProductTag;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.application.port.out.ProductMediaSyncStore.MediaItem;
import com.rigour.integration.application.port.out.ProductMediaSyncStore.ReusableMedia;
import com.rigour.integration.application.port.out.DhbClient.Receipt;
import com.rigour.integration.application.port.out.DhbClient.ReceiptQuery;
import com.rigour.integration.application.port.out.DhbClient.ReturnDetail;
import com.rigour.integration.application.port.out.DhbClient.ReturnQuery;
import com.rigour.integration.application.port.out.DhbClient.ReturnSummary;
import com.rigour.integration.application.port.out.DhbClient.Shipment;
import com.rigour.integration.application.port.out.DhbClient.ShipmentQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderView;
import com.rigour.integration.api.v1.model.DhbApiModels.PaymentPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.PaymentQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.PaymentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductBrandListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductBrandView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductCategoryListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductCategoryView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMasterDataQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductImageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSkuView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSpecificationPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSpecificationValueView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSpecificationView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductTagPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductTagView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductView;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReceiptPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReceiptQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ReceiptView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnLineView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.api.v1.model.DhbApiModels.WaitShipView;
import com.rigour.integration.api.v1.model.DhbApiModels.WaitShipsView;
import com.rigour.integration.api.v1.model.DhbApiModels.WaitStockView;
import com.rigour.integration.api.v1.model.DhbConnectionTestResult;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 订货宝数据同步用例；租户和权限只取Gateway签名上下文，不接受客户端传入。 */
public final class DhbIntegrationService {
    private final DhbIntegrationStore store;
    private final DhbClient client;
    private final DhbOrderSyncService orderSyncService;
    private final ProductMediaStorage productMediaStorage;
    private final ProductImageObjectKeyFactory productImageObjectKeyFactory;
    private final ProductMediaSyncStore productMediaSyncStore;

    public DhbIntegrationService(DhbIntegrationStore store, DhbClient client,
                                 DhbOrderSyncService orderSyncService,
                                 ProductMediaStorage productMediaStorage,
                                 ProductImageObjectKeyFactory productImageObjectKeyFactory,
                                 ProductMediaSyncStore productMediaSyncStore) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.orderSyncService = Objects.requireNonNull(orderSyncService, "orderSyncService cannot be null");
        this.productMediaStorage = Objects.requireNonNull(productMediaStorage,
                "productMediaStorage cannot be null");
        this.productImageObjectKeyFactory = Objects.requireNonNull(productImageObjectKeyFactory,
                "productImageObjectKeyFactory cannot be null");
        this.productMediaSyncStore = Objects.requireNonNull(productMediaSyncStore,
                "productMediaSyncStore cannot be null");
    }

    public List<ConnectorView> connectors() {
        CallerIdentity caller = requireReadCaller();
        return store.connectors(caller.tenantId());
    }

    public ConnectorView createConnector(ConnectorCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.createConnector(caller.tenantId(), caller.userId(), command);
    }

    /** 只验证订货宝认证，不返回 Secret 或令牌；调用需要写权限因为会触发外部请求。 */
    public DhbConnectionTestResult testConnection(UUID connectorId) {
        CallerIdentity caller = requireWriteCaller();
        ConnectorView connector = store.connector(caller.tenantId(), connectorId);
        ConnectionTestResult result = client.testConnection(new DhbClient.Connector(
                connector.tenantId(), connector.id(), connector.baseUrl(), connector.authSecretRef()));
        store.recordConnectionTest(caller.tenantId(), caller.userId(), connectorId, result);
        return new DhbConnectionTestResult(result.success(), result.code(), result.message(),
                result.tokenExpiresAt());
    }

    public ConnectorView updateConnector(UUID id, ConnectorCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.updateConnector(caller.tenantId(), caller.userId(), id, command);
    }

    /** 查询订货宝商品；认证、分页和字段兼容由 Integration 适配器处理。 */
    public ProductPageView products(UUID connectorId, ProductQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        ProductQuery query = command == null
                ? new ProductQuery(PageRequest.first(100), null, null, null, null, null, null)
                : productQuery(command);
        DhbClient.Connector connector = connector(caller, connectorId);
        UUID mediaJobId = command == null ? null : command.mediaJobId();
        if (mediaJobId != null) {
            ProductMediaSyncView mediaStatus = productMediaSyncStore.status(
                    caller.tenantId(), connectorId, mediaJobId);
            if (!"SUCCEEDED".equals(mediaStatus.status()) || mediaStatus.failedImages() > 0) {
                throw new IllegalStateException("商品图片异步任务尚未成功完成 jobId=" + mediaJobId
                        + " status=" + mediaStatus.status() + " failedImages=" + mediaStatus.failedImages());
            }
        }
        Page<Product> page = client.getProducts(connector, query);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "PRODUCT_SPU", item.sourceId(), null, item.attributes()));
        return new ProductPageView(page.total(), page.items().stream()
                .map(item -> productView(caller.tenantId().toString(), connector, item, mediaJobId))
                .toList());
    }

    /** 先拉取商品图片清单，图片字节由后台任务受控并发上传。 */
    public ProductMediaSyncView startProductMediaSync(UUID connectorId, ProductQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        if (command != null && command.mediaJobId() != null) {
            throw new IllegalArgumentException("创建商品图片任务时不能传入 mediaJobId");
        }
        DhbClient.Connector connector = connector(caller, connectorId);
        Page<Product> page = client.getProducts(connector, command == null
                ? new ProductQuery(PageRequest.first(100), null, null, null, null, null, null)
                : productQuery(command));
        List<MediaItem> items = page.items().stream()
                .flatMap(product -> product.images().stream()
                        .filter(image -> image.sourceUrl() != null && !image.sourceUrl().isBlank())
                        .map(image -> mediaItem(caller.tenantId(), connectorId, product.sourceId(), image)))
                .toList();
        return productMediaSyncStore.create(caller.tenantId(), caller.userId(), connectorId,
                items.size(), items);
    }

    public ProductMediaSyncView productMediaSyncStatus(UUID connectorId, UUID jobId) {
        CallerIdentity caller = requireReadCaller();
        return productMediaSyncStore.status(caller.tenantId(), connectorId, jobId);
    }

    private ProductView productView(String tenantId, DhbClient.Connector connector, Product item,
                                    UUID mediaJobId) {
        List<ProductImageView> images = item.images().stream()
                .map(image -> mediaJobId == null
                        ? uploadImage(tenantId, connector, item.sourceId(), image)
                        : completedImage(tenantId, connector, mediaJobId, item.sourceId(), image))
                .filter(Objects::nonNull)
                .toList();
        String mainImageKey = images.stream().findFirst().map(ProductImageView::objectKey).orElse(null);
        return new ProductView(item.sourceId(), item.code(), item.name(), item.putaway(),
                        item.barcode(), item.unit(), item.categorySourceId(), item.brandSourceId(),
                        item.model(), item.subtitle(), item.keywords(), item.allocation(),
                        mainImageKey, item.multiId(), item.orderPrice(), item.marketPrice(),
                        item.purchasePrice(), item.price4(), item.middleUnit(), item.bigUnit(),
                        item.middleBarcode(), item.bigBarcode(), item.conversionBarcode(),
                        item.baseToMiddleRate(), item.baseToBigRate(), item.minimumOrder(),
                        item.minimumOrderUnit(), item.inventoryLower(), item.inventoryUpper(),
                        item.safetyInventory(), item.middleOrderPrice(), item.bigOrderPrice(),
                        images, item.customFields(),
                        item.skus().stream().map(sku -> new ProductSkuView(
                                normalizedSkuSourceId(item.sourceId(), sku.sourceId()), sku.code(),
                                sku.barcode(), sku.firstSpecificationValueSourceId(),
                                sku.secondSpecificationValueSourceId(), sku.specificationName(),
                                sku.optionsId(), sku.orderPrice(), sku.marketPrice(), sku.purchasePrice(),
                                sku.middleOrderPrice(), sku.bigOrderPrice(), sku.middleBarcode(),
                                sku.bigBarcode(), sku.attributes())).toList(), item.attributes());
    }

    /** 订货宝 options_id 可能在不同商品下重复，SKU 来源键必须带上所属商品。 */
    static String normalizedSkuSourceId(String productSourceId, String skuSourceId) {
        if (productSourceId == null || productSourceId.isBlank()
                || skuSourceId == null || skuSourceId.isBlank()) {
            throw new IllegalArgumentException("订货宝商品或SKU来源ID不能为空");
        }
        return productSourceId.strip() + "::" + skuSourceId.strip();
    }

    private ProductImageView uploadImage(String tenantId, DhbClient.Connector connector,
                                         String sourceProductId,
                                         com.rigour.integration.application.port.out.DhbClient.ProductImage image) {
        if (image.sourceUrl() == null || image.sourceUrl().isBlank()) {
            return null;
        }
        ReusableMedia reusable = reusableMedia(UUID.fromString(tenantId), connector.connectorId(),
                sourceProductId, image);
        if (reusable != null) {
            return new ProductImageView(image.sourceResourceId(), image.sourceGoodsId(), image.originalName(),
                    image.fileName(), image.sortOrder(), reusable.objectKey());
        }
        DhbClient.DownloadedImage downloaded = client.downloadProductImage(connector, image.sourceUrl());
        String objectKey = productImageObjectKeyFactory.generate(tenantId, sourceProductId,
                image.sourceResourceId(), image.sortOrder(), downloaded.content(), image.fileName(),
                downloaded.contentType());
        productMediaStorage.put(tenantId, objectKey, image.originalName(), downloaded.contentType(),
                downloaded.content());
        return new ProductImageView(image.sourceResourceId(), image.sourceGoodsId(), image.originalName(),
                image.fileName(), image.sortOrder(), objectKey);
    }

    private MediaItem mediaItem(UUID tenantId, UUID connectorId, String sourceProductId,
                                com.rigour.integration.application.port.out.DhbClient.ProductImage image) {
        ReusableMedia reusable = reusableMedia(tenantId, connectorId, sourceProductId, image);
        return reusable == null
                ? new MediaItem(sourceProductId, image)
                : new MediaItem(sourceProductId, image, reusable.objectKey(), reusable.contentType());
    }

    private ReusableMedia reusableMedia(UUID tenantId, UUID connectorId, String sourceProductId,
                                        com.rigour.integration.application.port.out.DhbClient.ProductImage image) {
        ReusableMedia reusable = productMediaSyncStore.findReusable(tenantId, connectorId,
                sourceProductId, image);
        if (reusable == null || !productMediaStorage.exists(tenantId.toString(), reusable.objectKey())) {
            return null;
        }
        return reusable;
    }

    private ProductImageView completedImage(String tenantId, DhbClient.Connector connector,
                                            UUID mediaJobId, String sourceProductId,
                                            com.rigour.integration.application.port.out.DhbClient.ProductImage image) {
        String objectKey = productMediaSyncStore.completedObjectKey(
                UUID.fromString(tenantId), connector.connectorId(), mediaJobId,
                sourceProductId, image.sourceResourceId(), image.sortOrder());
        return objectKey == null ? null : new ProductImageView(image.sourceResourceId(),
                image.sourceGoodsId(), image.originalName(), image.fileName(), image.sortOrder(), objectKey);
    }

    private static ProductQuery productQuery(ProductQueryCommand command) {
        return new ProductQuery(page(command.begin(), command.step()), command.status(),
                command.putaway(), command.goodsCode(), command.barcode(), command.updatedFrom(),
                command.updatedTo());
    }

    /** getSite 是全量接口；分类层级和默认分类字段由 Integration 归一化后透传。 */
    public ProductCategoryListView productCategories(UUID connectorId) {
        CallerIdentity caller = requireReadCaller();
        List<ProductCategory> items = client.getProductCategories(connector(caller, connectorId));
        items.forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "CATEGORY", item.sourceId(), null, item.attributes()));
        return new ProductCategoryListView(items.stream()
                .map(item -> new ProductCategoryView(item.sourceId(), item.externalReferenceId(),
                        item.name(), item.categoryNumber(), item.parentSourceId(), item.defaultCategory(),
                        item.attributes())).toList());
    }

    /** getBrands 是全量接口；保留品牌编码、排序和说明字段。 */
    public ProductBrandListView productBrands(UUID connectorId) {
        CallerIdentity caller = requireReadCaller();
        List<ProductBrand> items = client.getProductBrands(connector(caller, connectorId));
        items.forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "BRAND", item.sourceId(), null, item.attributes()));
        return new ProductBrandListView(items.stream()
                .map(item -> new ProductBrandView(item.sourceId(), item.externalReferenceId(),
                        item.name(), item.brandNumber(), item.sortOrder(), item.description(),
                        item.attributes())).toList());
    }

    public ProductSpecificationPageView productSpecifications(
            UUID connectorId, ProductMasterDataQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        PageRequest request = page(command == null ? null : command.begin(),
                command == null ? null : command.step());
        Page<ProductSpecification> page = client.getProductSpecifications(
                connector(caller, connectorId), request);
        page.items().forEach(item -> {
            store.persistRawLanding(caller.tenantId(), connectorId, "SPECIFICATION",
                    item.sourceId(), null, item.attributes());
            item.values().forEach(value -> store.persistRawLanding(caller.tenantId(), connectorId,
                    "SPECIFICATION_VALUE", value.sourceId(), null, value.attributes()));
        });
        return new ProductSpecificationPageView(page.total(), page.items().stream()
                .map(item -> new ProductSpecificationView(item.sourceId(), item.code(), item.name(),
                        item.parentSourceId(), item.values().stream().map(value -> new ProductSpecificationValueView(
                                value.sourceId(), value.code(), value.name(), value.parentSourceId(),
                                value.attributes())).toList(), item.attributes())).toList());
    }

    /** getGoodsTag 返回分页对象；Integration 不把 rData 分页对象误当成数组。 */
    public ProductTagPageView productTags(UUID connectorId, ProductMasterDataQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        PageRequest request = page(command == null ? null : command.begin(),
                command == null ? null : command.step());
        Page<ProductTag> page = client.getProductTags(connector(caller, connectorId), request);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "TAG", item.sourceId(), null, item.attributes()));
        return new ProductTagPageView(page.total(), page.items().stream()
                .map(item -> new ProductTagView(item.sourceId(), item.code(), item.name(),
                        item.sortOrder(), item.relationCount(), item.createdAt(), item.updatedAt(),
                        item.groupSourceId(), item.groupName(), item.attributes())).toList());
    }

    /** 查询订货宝客户；联系方式等来源字段仅在调用方已有 Integration 权限时返回。 */
    public CustomerPageView customers(UUID connectorId, CustomerQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        CustomerQuery query = command == null
                ? new CustomerQuery(PageRequest.first(100), null, null, null, null, null, null, null)
                : new CustomerQuery(page(command.begin(), command.step()), command.status(),
                command.dataType(), command.timeType(), window("客户时间",
                command.updatedFrom(), command.updatedTo()), command.clientNo(), command.clientArea(),
                command.typeId());
        Page<Customer> page = client.getCustomers(connector(caller, connectorId), query);
        return new CustomerPageView(page.total(), page.items().stream()
                .map(item -> new CustomerView(item.sourceId(), item.account(), item.number(), item.name(),
                        item.status(), item.createdAt(), item.updatedAt(), item.attributes()))
                .toList());
    }

    /** 查询订货宝订单摘要；调用 getOrderList，不会调用订单明细副作用接口。 */
    public OrderPageView orders(UUID connectorId, OrderQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        OrderQuery query = command == null
                ? new OrderQuery(PageRequest.first(100), null, null, null, null, null, null, null)
                : new OrderQuery(page(command.begin(), command.step()), command.orderStatus(),
                window("订单创建时间", command.createdFrom(), command.createdTo()),
                window("订单更新时间", command.updatedFrom(), command.updatedTo()),
                command.exceptionStatus(), command.apiStatus(), command.payStatus(), command.splitType());
        Page<OrderSummary> page = client.getOrders(connector(caller, connectorId), query);
        return new OrderPageView(page.total(), page.items().stream()
                .map(item -> new OrderView(item.sourceId(), item.orderNumber(), item.status(), item.amount(),
                        item.createdAt(), item.updatedAt(), item.customerNumber(), item.paymentStatus(),
                        item.attributes()))
                .toList());
    }

    /**
     * 获取订单明细。订货宝文档说明该接口可能标记订单已获取/审核，因此必须具备写权限，
     * 且调用方必须显式传入两个副作用开关。
     */
    public OrderContentView orderContent(UUID connectorId, String orderNumber,
                                         OrderContentCommand command) {
        CallerIdentity caller = requireWriteOrServiceCaller();
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        boolean autoMarkDownloaded = command != null && Boolean.TRUE.equals(command.autoMarkDownloaded());
        boolean autoAudit = command != null && Boolean.TRUE.equals(command.autoAudit());
        OrderDetail detail = client.getOrderContent(connector(caller, connectorId), orderNumber,
                autoMarkDownloaded, autoAudit);
        return new OrderContentView(detail.orderNumber(), detail.status(), detail.amount(), detail.attributes());
    }

    /** 查询订货宝出库/发货单列表；对应 getShipsList，返回字段不包含凭据。 */
    public ShipmentPageView shipments(UUID connectorId, ShipmentQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        ShipmentQuery query = command == null
                ? ShipmentQuery.first(100, null, null)
                : new ShipmentQuery(page(command.begin(), command.step()), command.status(), command.isApi(),
                command.typeId(), window("出库单创建时间", command.createdFrom(), command.createdTo()),
                window("出库单更新时间", command.updatedFrom(), command.updatedTo()),
                command.clientNumber(), command.stockId(), command.stockNumber());
        Page<Shipment> page = client.getShipments(connector(caller, connectorId), query);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "SHIPMENT", item.shipmentNumber() == null ? item.sourceId() : item.shipmentNumber(),
                item.updatedAt(), item.attributes()));
        return new ShipmentPageView(page.total(), page.items().stream()
                .map(item -> new ShipmentView(item.sourceId(), item.shipmentNumber(), item.orderNumber(),
                        item.status(), item.statusName(), item.typeId(), item.typeName(), item.customerNumber(),
                        item.customerName(), item.customerGuid(), item.warehouseNumber(), item.warehouseName(),
                        item.warehouseGuid(), item.shipmentAt(), item.logisticsName(), item.trackingNumber(),
                        item.remark(), item.createdAt(), item.updatedAt(), item.attributes()))
                .toList());
    }

    /** 查询订货宝出库/发货单详情；对应 getShipsContent，ships_num为必填业务键。 */
    public ShipmentContentView shipmentContent(UUID connectorId, String shipmentNumber) {
        CallerIdentity caller = requireReadCaller();
        if (shipmentNumber == null || shipmentNumber.isBlank()) {
            throw new IllegalArgumentException("shipmentNumber is required");
        }
        com.rigour.integration.application.port.out.DhbClient.ShipmentDetail detail =
                client.getShipmentContent(connector(caller, connectorId), shipmentNumber);
        store.persistRawLanding(caller.tenantId(), connectorId, "SHIPMENT_CONTENT",
                detail.shipmentNumber() == null ? shipmentNumber : detail.shipmentNumber(), null,
                detail.attributes());
        return new ShipmentContentView(detail.shipmentNumber(), detail.attributes());
    }

    /** 查询指定订货单的出库/发货物流；对应订货宝getWaitShips，只读且按订单号查询。 */
    public WaitShipsView waitShips(UUID connectorId, String orderNumber) {
        CallerIdentity caller = requireReadCaller();
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        DhbClient.WaitShips result = client.getWaitShips(connector(caller, connectorId), orderNumber);
        return new WaitShipsView(result.orderNumber(), result.shipped().stream()
                .map(item -> new WaitShipView(item.sourceId(), item.shipmentNo(), item.status(),
                        item.logisticsName(), item.logisticsCode(), item.trackingNo(), item.shipmentAt(),
                        item.stockUpAt(), item.warehouseNo(), item.warehouseName(), item.lines().stream()
                                .map(line -> new com.rigour.integration.api.v1.model.DhbApiModels.WaitShipLineView(
                                        line.sourceLineId(), line.orderLineId(), line.productId(), line.skuNo(),
                                        line.listType(), line.productCode(), line.productName(), line.specification(),
                                        line.unit(), line.containerUnit(), line.conversionNumber(), line.quantity(),
                                        line.remark(), line.attributes())).toList(), item.attributes()))
                .toList(), result.waitStock().stream()
                .map(item -> new WaitStockView(item.sourceLineId(), item.productId(), item.skuNo(), item.listType(),
                        item.productCode(), item.productName(), item.specification(), item.unit(), item.containerUnit(),
                        item.conversionNumber(), item.warehouseNo(), item.warehouseName(), item.orderedQuantity(),
                        item.stockedQuantity(), item.realStock(), item.waitQuantity(), item.remark(), item.attributes()))
                .toList(), result.attributes());
    }

    /** 查询订货宝退货单列表；读取成功后将技术原始字段落入 Integration Raw Landing。 */
    public ReturnPageView returns(UUID connectorId, ReturnQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        ReturnQuery query = command == null
                ? ReturnQuery.first(100, null, null)
                : new ReturnQuery(page(command.begin(), command.step()), command.status(), command.isApi(),
                window("退货单创建时间", command.createdFrom(), command.createdTo()),
                window("退货单更新时间", command.updatedFrom(), command.updatedTo()),
                command.stockId(), command.stockNumber());
        Page<ReturnSummary> page = client.getReturns(connector(caller, connectorId), query);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "RETURN", item.returnNumber() == null ? item.sourceId() : item.returnNumber(),
                item.updatedAt(), item.attributes()));
        return new ReturnPageView(page.total(), page.items().stream().map(this::returnView).toList());
    }

    /** 查询退货单明细；对应 getReturnsContent，结果同时保存到技术原始数据表。 */
    public ReturnContentView returnContent(UUID connectorId, String returnNumber) {
        CallerIdentity caller = requireReadCaller();
        if (returnNumber == null || returnNumber.isBlank()) {
            throw new IllegalArgumentException("returnNumber is required");
        }
        ReturnDetail detail = client.getReturnContent(connector(caller, connectorId), returnNumber);
        store.persistRawLanding(caller.tenantId(), connectorId, "RETURN_CONTENT",
                detail.returnNumber() == null ? returnNumber : detail.returnNumber(),
                detail.summary() == null ? null : detail.summary().updatedAt(), detail.attributes());
        return new ReturnContentView(detail.returnNumber(), returnView(detail.summary()),
                detail.lines().stream().map(item -> new ReturnLineView(item.sourceId(), item.productGuid(),
                        item.skuNumber(), item.productCode(), item.productName(), item.quantity(),
                        item.confirmedQuantity(), item.unitPrice(), item.confirmedPrice(), item.unit(),
                        item.unitQuantity(), item.confirmedUnitQuantity(), item.conversionNumber(),
                        item.remark(), item.warehouseNumber(), item.warehouseName(), item.warehouseGuid(),
                        item.attributes())).toList(), detail.attributes());
    }

    /** 查询订货宝收款单列表；对应 getReceiptsList，并保存技术原始字段。 */
    public ReceiptPageView receipts(UUID connectorId, ReceiptQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        ReceiptQuery query = command == null
                ? ReceiptQuery.first(100)
                : new ReceiptQuery(page(command.begin(), command.step()), command.orderNumber(),
                window("收款单转账时间", command.createdFrom(), command.createdTo()),
                command.updatedFrom(), command.status());
        Page<Receipt> page = client.getReceipts(connector(caller, connectorId), query);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "RECEIPT", item.receiptNumber() == null ? item.sourceId() : item.receiptNumber(),
                item.updatedAt() == null ? item.createdAt() : item.updatedAt(), item.attributes()));
        return new ReceiptPageView(page.total(), page.items().stream().map(this::receiptView).toList());
    }

    /** 查询订货宝付款单列表；对应 getPaymentList，并保存技术原始字段。 */
    public PaymentPageView payments(UUID connectorId, PaymentQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        PaymentQuery query = command == null
                ? PaymentQuery.first(100)
                : new PaymentQuery(page(command.begin(), command.step()), command.orderNumber(),
                window("付款单转账时间", command.createdFrom(), command.createdTo()), command.status());
        Page<Payment> page = client.getPayments(connector(caller, connectorId), query);
        page.items().forEach(item -> store.persistRawLanding(caller.tenantId(), connectorId,
                "PAYMENT", item.paymentNumber() == null ? item.sourceId() : item.paymentNumber(),
                item.createdAt(), item.attributes()));
        return new PaymentPageView(page.total(), page.items().stream().map(this::paymentView).toList());
    }

    private ReturnView returnView(ReturnSummary item) {
        if (item == null) {
            return null;
        }
        return new ReturnView(item.sourceId(), item.returnNumber(), item.orderNumber(), item.status(),
                item.staffName(), item.returnAmount(), item.settlementAmount(), item.returnedAt(),
                item.updatedAt(), item.reason(), item.customerNumber(), item.customerGuid(), item.consignee(),
                item.phone(), item.address(), item.logisticsCompany(), item.logisticsNumber(), item.returnType(),
                item.deliveryMode(), item.attributes());
    }

    private ReceiptView receiptView(Receipt item) {
        return new ReceiptView(item.sourceId(), item.receiptNumber(), item.orderNumber(), item.customerNumber(),
                item.customerGuid(), item.businessType(), item.paymentMethod(), item.amount(), item.status(),
                item.transactionAt(), item.createdAt(), item.updatedAt(), item.serialNumber(), item.accountName(),
                item.bankName(), item.accountNumber(), item.remark(), item.attributes());
    }

    private PaymentView paymentView(Payment item) {
        return new PaymentView(item.sourceId(), item.paymentNumber(), item.receiptNumber(), item.orderNumber(),
                item.customerNumber(), item.customerGuid(), item.businessType(), item.paymentMethod(),
                item.amount(), item.status(), item.transactionAt(), item.createdAt(), item.serialNumber(),
                item.accountName(), item.bankName(), item.accountNumber(), item.remark(), item.attributes());
    }

    public List<SyncTaskView> syncTasks() {
        CallerIdentity caller = requireReadCaller();
        return store.syncTasks(caller.tenantId());
    }

    /**
     * 返回供领域服务使用的全局订货宝同步目标。
     *
     * <p>该用例只接受服务身份，避免把跨租户目标发现伪装成某个租户用户；具体租户的
     * 后续查询由Order Center或ERP使用带tenantId的服务身份执行。</p>
     */
    public List<SyncTargetView> syncTargets(String objectType) {
        requireServiceCaller();
        return store.activeSyncTargets(objectType);
    }

    public SyncTaskView createSyncTask(SyncTaskCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.createSyncTask(caller.tenantId(), caller.userId(), command);
    }

    public SyncTaskView updateSyncTask(UUID id, SyncTaskCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.updateSyncTask(caller.tenantId(), caller.userId(), id, command);
    }

    /** 手动执行第一阶段订单同步；定时调度和供应商写接口暂不在本用例启用。 */
    public SyncRunView runOrderPull(UUID taskId, SyncRunCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return orderSyncService.runOrderPull(caller, taskId, command);
    }

    public List<OrderMirrorView> orderMirrors(int limit, int offset) {
        CallerIdentity caller = requireReadCaller();
        return store.orderMirrors(caller.tenantId(), Math.max(1, Math.min(limit, 200)),
                Math.max(0, offset));
    }

    public List<SyncLogView> syncLogs(int limit) {
        CallerIdentity caller = requireReadCaller();
        return store.syncLogs(caller.tenantId(), Math.max(1, Math.min(limit, 500)));
    }

    public List<FieldMappingView> fieldMappings(UUID connectorId) {
        CallerIdentity caller = requireReadCaller();
        return store.fieldMappings(caller.tenantId(), connectorId);
    }

    public FieldMappingView saveFieldMapping(UUID id, FieldMappingCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.saveFieldMapping(caller.tenantId(), caller.userId(), id, command);
    }

    private DhbClient.Connector connector(CallerIdentity caller, UUID connectorId) {
        ConnectorView connector = store.connector(caller.tenantId(), connectorId);
        return new DhbClient.Connector(caller.tenantId(), connector.id(), connector.baseUrl(),
                connector.authSecretRef());
    }

    private static PageRequest page(Integer begin, Integer step) {
        return new PageRequest(begin == null ? 0 : begin, step == null ? 100 : step);
    }

    private static TimeWindow window(String field, java.time.Instant from, java.time.Instant to) {
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException(field + " from 和 to 必须同时提供");
        }
        return new TimeWindow(from, to);
    }

    private static CallerIdentity requireReadCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dhb:read");
        return caller;
    }

    private static CallerIdentity requireWriteCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        if (caller.userId() == null) {
            throw new com.rigour.shared.context.AuthorizationDeniedException("user-caller");
        }
        AuthorizationContext.requirePermission("integration:dhb:write");
        return caller;
    }

    /** 订单明细读取虽标记为写权限，但定时调度使用服务身份执行该受控外部调用。 */
    private static CallerIdentity requireWriteOrServiceCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dhb:write");
        return caller;
    }

    private static void requireTenant(CallerIdentity caller) {
        boolean tenantCaller = "TENANT".equals(caller.principalScope())
                && caller.tenantId() != null && caller.userId() != null;
        boolean tenantScopedService = "SERVICE".equals(caller.principalScope())
                && caller.tenantId() != null && caller.userId() == null;
        if (!tenantCaller && !tenantScopedService) {
            throw new com.rigour.shared.context.AuthorizationDeniedException("tenant-caller");
        }
    }

    private static CallerIdentity requireServiceCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() != null
                || caller.userId() != null || caller.platformUserId() != null) {
            throw new com.rigour.shared.context.AuthorizationDeniedException("service-caller");
        }
        AuthorizationContext.requirePermission("integration:dhb:sync-discovery");
        return caller;
    }
}

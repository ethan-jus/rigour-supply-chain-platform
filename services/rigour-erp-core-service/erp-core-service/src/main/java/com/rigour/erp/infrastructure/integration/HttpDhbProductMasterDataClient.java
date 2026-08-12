package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.ProductImage;
import com.rigour.erp.domain.model.product.Sku;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.SpecificationValue;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.integration.api.v1.DhbProductApi;
import com.rigour.integration.api.v1.model.DhbApiModels;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** 通过 Integration V1 契约收集订货宝商品主数据，不直接发送供应商 f/v 报文。 */
public final class HttpDhbProductMasterDataClient implements DhbProductMasterDataClient {
    /** 商品图片先异步处理；较小的商品页避免一次任务积压过多图片。 */
    private static final int PAGE_SIZE = 50;
    private static final Duration MEDIA_SYNC_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MEDIA_SYNC_MAX_WAIT = Duration.ofMinutes(30);

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final ObjectMapper objectMapper;
    private final URI integrationBaseUri;

    public HttpDhbProductMasterDataClient(RestClient.Builder builder, TrustedContextSigner signer,
                                          ObjectMapper objectMapper, String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper不能为空");
        this.integrationBaseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }

    @Override
    public Collected collect(CallerIdentity caller, UUID connectorId,
                             MasterDataObjectType objectType, int maxPages) {
        Objects.requireNonNull(caller, "caller不能为空");
        Objects.requireNonNull(connectorId, "connectorId不能为空");
        return switch (objectType) {
            case PRODUCT_SPU -> products(caller, connectorId, maxPages);
            case CATEGORY -> categories(caller, connectorId);
            case BRAND -> brands(caller, connectorId);
            case SPECIFICATION -> specifications(caller, connectorId, maxPages);
            case TAG -> tags(caller, connectorId, maxPages);
        };
    }

    private Collected products(CallerIdentity caller, UUID connectorId, int maxPages) {
        List<Product> result = new ArrayList<>();
        long total = 0;
        int pages = 0;
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ProductQueryCommand command =
                    new DhbApiModels.ProductQueryCommand(begin, PAGE_SIZE, "C", "A", null);
            DhbApiModels.ProductMediaSyncView mediaJob = post(caller,
                    path(connectorId, "media-sync"), command,
                    DhbApiModels.ProductMediaSyncView.class);
            awaitMediaSync(caller, connectorId, mediaJob);
            DhbApiModels.ProductQueryCommand completedCommand =
                    new DhbApiModels.ProductQueryCommand(begin, PAGE_SIZE, "C", "A", null,
                            null, null, null, mediaJob.jobId());
            DhbApiModels.ProductPageView page = post(caller,
                    path(connectorId, "query"),
                    completedCommand,
                    DhbApiModels.ProductPageView.class);
            pages++;
            if (pageNumber == 0) total = page.total();
            List<DhbApiModels.ProductView> items = page.items() == null ? List.of() : page.items();
            items.stream().map(this::product).forEach(result::add);
            if (items.isEmpty() || items.size() < PAGE_SIZE
                    || (total >= 0 && begin + items.size() >= total)) {
                return collected(MasterDataObjectType.PRODUCT_SPU, Math.max(0, total), pages, result,
                        null, null, null, null);
            }
        }
        throw new IllegalStateException("订货宝商品同步达到maxPages=" + maxPages
                + "，但供应商仍有后续数据；本次批次失败");
    }

    private void awaitMediaSync(CallerIdentity caller, UUID connectorId,
                                DhbApiModels.ProductMediaSyncView initial) {
        Instant deadline = Instant.now().plus(MEDIA_SYNC_MAX_WAIT);
        DhbApiModels.ProductMediaSyncView current = initial;
        while (!terminal(current.status())) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("商品图片异步任务等待超过30分钟 jobId=" + current.jobId());
            }
            sleep(MEDIA_SYNC_POLL_INTERVAL);
            current = get(caller, path(connectorId, "media-sync", current.jobId().toString()),
                    DhbApiModels.ProductMediaSyncView.class);
        }
        if (!"SUCCEEDED".equals(current.status()) || current.failedImages() > 0) {
            throw new IllegalStateException("商品图片异步任务失败 jobId=" + current.jobId()
                    + " status=" + current.status() + " failedImages=" + current.failedImages());
        }
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待商品图片异步任务时线程被中断", exception);
        }
    }

    private Collected categories(CallerIdentity caller, UUID connectorId) {
        DhbApiModels.ProductCategoryListView page = post(caller,
                path(connectorId, "categories", "query"),
                new DhbApiModels.ProductMasterDataQueryCommand(0, PAGE_SIZE),
                DhbApiModels.ProductCategoryListView.class);
        List<Category> items = page.items().stream()
                .map(item -> new Category(item.sourceId(), item.externalReferenceId(),
                        item.name(), item.categoryNumber(), item.parentSourceId(),
                        item.defaultCategory(), hash(item))).toList();
        return collected(MasterDataObjectType.CATEGORY, items.size(), 1,
                null, items, null, null, null);
    }

    private Collected brands(CallerIdentity caller, UUID connectorId) {
        DhbApiModels.ProductBrandListView page = post(caller,
                path(connectorId, "brands", "query"),
                new DhbApiModels.ProductMasterDataQueryCommand(0, PAGE_SIZE),
                DhbApiModels.ProductBrandListView.class);
        List<Brand> items = page.items().stream()
                .map(item -> new Brand(item.sourceId(), item.externalReferenceId(),
                        item.name(), item.brandNumber(), item.sortOrder(), item.description(),
                        hash(item))).toList();
        return collected(MasterDataObjectType.BRAND, items.size(), 1,
                null, null, items, null, null);
    }

    private Collected specifications(CallerIdentity caller, UUID connectorId, int maxPages) {
        List<Specification> result = new ArrayList<>();
        long total = 0;
        int pages = 0;
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ProductSpecificationPageView page = post(caller,
                    path(connectorId, "specifications", "query"),
                    new DhbApiModels.ProductMasterDataQueryCommand(begin, PAGE_SIZE),
                    DhbApiModels.ProductSpecificationPageView.class);
            pages++;
            if (pageNumber == 0) total = page.total();
            List<DhbApiModels.ProductSpecificationView> items =
                    page.items() == null ? List.of() : page.items();
            items.stream().map(this::specification).forEach(result::add);
            if (items.isEmpty() || items.size() < PAGE_SIZE
                    || (total >= 0 && begin + items.size() >= total)) {
                return collected(MasterDataObjectType.SPECIFICATION, Math.max(0, total), pages,
                        null, null, null, result, null);
            }
        }
        throw new IllegalStateException("订货宝规格同步达到maxPages=" + maxPages
                + "，但供应商仍有后续数据；本次批次失败");
    }

    private Collected tags(CallerIdentity caller, UUID connectorId, int maxPages) {
        List<Tag> result = new ArrayList<>();
        long total = 0;
        int pages = 0;
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ProductTagPageView page = post(caller,
                    path(connectorId, "tags", "query"),
                    new DhbApiModels.ProductMasterDataQueryCommand(begin, PAGE_SIZE),
                    DhbApiModels.ProductTagPageView.class);
            pages++;
            if (pageNumber == 0) total = page.total();
            List<DhbApiModels.ProductTagView> items = page.items() == null ? List.of() : page.items();
            items.stream().map(this::tag).forEach(result::add);
            if (items.isEmpty() || items.size() < PAGE_SIZE
                    || (total >= 0 && begin + items.size() >= total)) {
                return collected(MasterDataObjectType.TAG, Math.max(0, total), pages,
                        null, null, null, null, result);
            }
        }
        throw new IllegalStateException("订货宝标签同步达到maxPages=" + maxPages
                + "，但供应商仍有后续数据；本次批次失败");
    }

    private Product product(DhbApiModels.ProductView item) {
        List<Sku> skus = item.skus().stream()
                .map(sku -> new Sku(sku.sourceId(), sku.code(), sku.barcode(),
                        sku.firstSpecificationValueSourceId(), sku.secondSpecificationValueSourceId(),
                        sku.specificationName(), sku.optionsId(), sku.orderPrice(), sku.marketPrice(),
                        sku.purchasePrice(), sku.middleOrderPrice(), sku.bigOrderPrice(),
                        sku.middleBarcode(), sku.bigBarcode(), hash(sku))).toList();
        List<ProductImage> images = item.images().stream()
                .map(image -> new ProductImage(image.sourceResourceId(), image.sourceGoodsId(),
                        image.originalName(), image.fileName(), image.sortOrder(), image.objectKey()))
                .toList();
        return new Product(item.sourceId(), item.code(), item.name(), item.putaway(),
                item.barcode(), item.unit(), item.categorySourceId(), item.brandSourceId(),
                item.model(), item.subtitle(), item.keywords(), item.allocation(), item.mainImageKey(),
                item.multiId(), item.orderPrice(), item.marketPrice(), item.purchasePrice(), item.price4(),
                item.middleUnit(), item.bigUnit(), item.middleBarcode(), item.bigBarcode(),
                item.conversionBarcode(), item.baseToMiddleRate(), item.baseToBigRate(), item.minimumOrder(),
                item.minimumOrderUnit(), item.inventoryLower(), item.inventoryUpper(), item.safetyInventory(),
                item.middleOrderPrice(), item.bigOrderPrice(),
                images, item.customFields(), skus, hash(item));
    }

    private Specification specification(DhbApiModels.ProductSpecificationView item) {
        List<SpecificationValue> values = item.values().stream()
                .map(value -> new SpecificationValue(value.sourceId(), value.code(),
                        value.name(), value.parentSourceId(), hash(value))).toList();
        return new Specification(item.sourceId(), item.code(), item.name(),
                item.parentSourceId(), values, hash(item));
    }

    private Tag tag(DhbApiModels.ProductTagView item) {
        return new Tag(item.sourceId(), item.code(), item.name(), item.sortOrder(), item.relationCount(),
                item.createdAt(), item.updatedAt(), item.groupSourceId(), item.groupName(), hash(item));
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, Class<T> responseType) {
        Map<String, String> context = SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller);
        return restClient.post().uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .body(body).retrieve().body(responseType);
    }

    private <T> T get(CallerIdentity caller, URI uri, Class<T> responseType) {
        Map<String, String> context = SignedIntegrationRequest.signedHeaders(signer, "GET", uri, caller);
        return restClient.get().uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .retrieve().body(responseType);
    }

    private URI path(UUID connectorId, String... suffix) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbProductApi.BASE_PATH).pathSegment(connectorId.toString());
        for (String segment : suffix) builder.pathSegment(segment);
        return builder.build().encode().toUri();
    }

    private String hash(Object value) {
        return StablePayloadHasher.sha256(objectMapper, value);
    }

    private static Collected collected(MasterDataObjectType type, long total, int pages,
                                       List<Product> products,
                                       List<Category> categories,
                                       List<Brand> brands,
                                       List<Specification> specifications,
                                       List<Tag> tags) {
        return new Collected(type, total, pages, products, categories, brands, specifications, tags);
    }
}

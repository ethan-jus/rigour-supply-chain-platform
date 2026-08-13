package com.rigour.merchant.infrastructure.integration;

import com.rigour.integration.api.v1.DhbCustomerApi;
import com.rigour.integration.api.v1.DhbEmployeeApi;
import com.rigour.integration.api.v1.model.CustomerAreaListView;
import com.rigour.integration.api.v1.model.CustomerAreaView;
import com.rigour.integration.api.v1.model.CustomerPageView;
import com.rigour.integration.api.v1.model.CustomerQueryCommand;
import com.rigour.integration.api.v1.model.CustomerTypeListView;
import com.rigour.integration.api.v1.model.ShippingAddressPageView;
import com.rigour.integration.api.v1.model.ShippingAddressQueryCommand;
import com.rigour.integration.api.v1.model.StaffPageView;
import com.rigour.integration.api.v1.model.StaffQueryCommand;
import com.rigour.integration.api.v1.model.StaffView;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 通过 Integration 版本化契约读取订货宝 CRM 主数据。 */
public final class HttpDhbCrmMasterDataClient implements DhbCrmMasterDataClient {
    private static final int PAGE_SIZE = 500;

    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpDhbCrmMasterDataClient(RestClient.Builder builder, TrustedContextSigner signer,
                                      String integrationBaseUrl) {
        this.client = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedIntegrationRequest.baseUri(integrationBaseUrl);
    }

    @Override
    public Collected collect(CallerIdentity caller, UUID connectorId,
                             CrmMasterDataObjectType objectType, int maxPages) {
        return switch (objectType) {
            case CUSTOMER_TYPE -> customerTypes(caller, connectorId);
            case CUSTOMER_AREA -> customerAreas(caller, connectorId);
            case CUSTOMER -> customers(caller, connectorId, maxPages);
            case ADDRESS -> addresses(caller, connectorId, maxPages);
            case STAFF -> staff(caller, connectorId, maxPages);
        };
    }

    private Collected customerTypes(CallerIdentity caller, UUID connectorId) {
        CustomerTypeListView view = post(caller,
                customerPath(connectorId, "types", "query"), Map.of(),
                CustomerTypeListView.class);
        List<SourceRecord> items = view.items().stream().map(item -> source(
                item.sourceId(), item.erpId(), item.name(), null, null, null,
                item.sourceFields())).toList();
        return new Collected(CrmMasterDataObjectType.CUSTOMER_TYPE, items.size(), 1, items);
    }

    private Collected customerAreas(CallerIdentity caller, UUID connectorId) {
        CustomerAreaListView view = post(caller,
                customerPath(connectorId, "areas", "query"), Map.of(),
                CustomerAreaListView.class);
        List<SourceRecord> items = view.items().stream().map(item -> source(
                item.sourceId(), item.erpId(), item.name(), null, null, null,
                areaFields(item))).toList();
        return new Collected(CrmMasterDataObjectType.CUSTOMER_AREA, items.size(), 1, items);
    }

    private static Map<String, Object> areaFields(CustomerAreaView item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.putAll(item.sourceFields());
        if (item.parentSourceId() != null && !item.parentSourceId().isBlank()) {
            fields.putIfAbsent("parentID", item.parentSourceId());
        }
        return fields;
    }

    private Collected customers(CallerIdentity caller, UUID connectorId, int maxPages) {
        return collectPages(CrmMasterDataObjectType.CUSTOMER, maxPages, begin -> {
            CustomerQueryCommand command = new CustomerQueryCommand(
                    begin, PAGE_SIZE, 3, 3, null, null, null, null, null, null);
            return post(caller, customerPath(connectorId, "query"), command,
                    CustomerPageView.class);
        }, CustomerPageView::total, CustomerPageView::items,
                item -> source(item.sourceId(), item.number(), item.companyName(), item.status(),
                        item.createdAt(), item.updatedAt(), item.sourceFields()));
    }

    private Collected addresses(CallerIdentity caller, UUID connectorId, int maxPages) {
        return collectPages(CrmMasterDataObjectType.ADDRESS, maxPages, begin -> {
            ShippingAddressQueryCommand command =
                    new ShippingAddressQueryCommand(
                            begin, PAGE_SIZE, null, null, null, null, null);
            return post(caller, customerPath(connectorId, "shipping-addresses", "query"),
                    command, ShippingAddressPageView.class);
        }, ShippingAddressPageView::total,
                ShippingAddressPageView::items,
                item -> source(item.sourceId(), item.addressGuid(), item.consignee(), null,
                        null, item.updatedAt(), item.sourceFields()));
    }

    private Collected staff(CallerIdentity caller, UUID connectorId, int maxPages) {
        Collected listed = collectPages(CrmMasterDataObjectType.STAFF, maxPages, begin -> {
            StaffQueryCommand command = new StaffQueryCommand(
                    begin, PAGE_SIZE, null, null, null, null, null, null, null);
            return post(caller, employeePath(connectorId, "query"), command,
                    StaffPageView.class);
        }, StaffPageView::total, StaffPageView::items,
                item -> staffRecord(caller, connectorId, item));
        return listed;
    }

    private SourceRecord staffRecord(CallerIdentity caller, UUID connectorId,
                                     StaffView item) {
        if (item.accountId() == null || item.accountId().isBlank()) {
            return source(item.sourceId(), item.accountName(), item.staffName(), item.status(),
                    item.createdAt(), item.updatedAt(), item.sourceFields());
        }
        StaffView detail = post(caller,
                employeePath(connectorId, item.accountId(), "query"), Map.of(),
                StaffView.class);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.putAll(item.sourceFields());
        fields.putAll(detail.sourceFields());
        fields.put("_dhbStaffList", item.sourceFields());
        fields.put("_dhbStaffDetail", detail.sourceFields());
        // 详情接口可能只返回 accounts_id；来源主键始终沿用列表 staff_id，避免同一员工漂移为新记录。
        return source(item.sourceId(), first(detail.accountName(), item.accountName()),
                first(detail.staffName(), item.staffName()), first(detail.status(), item.status()),
                first(detail.createdAt(), item.createdAt()),
                first(detail.updatedAt(), item.updatedAt()), fields);
    }

    private <P, I> Collected collectPages(CrmMasterDataObjectType type, int maxPages,
                                           Function<Integer, P> fetch,
                                           Function<P, Long> total,
                                           Function<P, List<I>> items,
                                           Function<I, SourceRecord> mapper) {
        List<SourceRecord> result = new ArrayList<>();
        long providerTotal = -1;
        int pages = 0;
        for (int page = 0; page < maxPages; page++) {
            P response = fetch.apply(page * PAGE_SIZE);
            pages++;
            long currentTotal = total.apply(response);
            if (page == 0) providerTotal = currentTotal;
            List<I> current = items.apply(response);
            current.stream().map(mapper).forEach(result::add);
            boolean complete = providerTotal >= 0
                    ? (long) (page + 1) * PAGE_SIZE >= providerTotal
                    : current.size() < PAGE_SIZE;
            if (complete) return new Collected(type, Math.max(0, providerTotal), pages, result);
        }
        throw new IllegalStateException("订货宝" + type + "同步达到maxPages=" + maxPages
                + "，供应商仍有后续数据；本次批次失败且不推进游标");
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, Class<T> responseType) {
        Map<String, String> context = SignedIntegrationRequest.signedHeaders(signer, "POST", uri, caller);
        T response = client.post().uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedIntegrationRequest.requestId())
                .body(body).retrieve().body(responseType);
        if (response == null) throw new IllegalStateException("Integration返回空响应：" + uri.getPath());
        return response;
    }

    private URI customerPath(UUID connectorId, String... suffix) {
        return path(DhbCustomerApi.BASE_PATH, connectorId, suffix);
    }

    private URI employeePath(UUID connectorId, String... suffix) {
        return path(DhbEmployeeApi.BASE_PATH, connectorId, suffix);
    }

    private URI path(String basePath, UUID connectorId, String... suffix) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(baseUri)
                .path(basePath).pathSegment(connectorId.toString());
        for (String value : suffix) builder.pathSegment(value);
        return builder.build().encode().toUri();
    }

    private static SourceRecord source(String id, String code, String name, String status,
                                       java.time.Instant createdAt, java.time.Instant updatedAt,
                                       Map<String, Object> fields) {
        return new SourceRecord(id, code, name, status, createdAt, updatedAt, fields);
    }

    private static <T> T first(T preferred, T fallback) {
        if (preferred instanceof String value && value.isBlank()) return fallback;
        return preferred == null ? fallback : preferred;
    }
}

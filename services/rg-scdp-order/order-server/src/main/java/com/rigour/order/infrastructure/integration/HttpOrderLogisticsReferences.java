package com.rigour.order.infrastructure.integration;

import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Set;

@Component
public final class HttpOrderLogisticsReferences implements OrderLogisticsReferences {
    @Override
    public java.util.Map<String, List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView>>
            ledgerCustomersByCities(
                    java.util.Map<String, List<String>> codesByCity, String action) {
        if (codesByCity.isEmpty()) return java.util.Map.of();
        var batch = new java.util.LinkedHashMap<String, List<String>>();
        codesByCity.forEach(
                (city, codes) -> batch.put(city, codes.stream().distinct().sorted().toList()));
        var key = java.util.Map.copyOf(batch);
        // 保存前后的来源校验复用本次请求已读取的同批客户，权限与下次请求仍重新核验。
        return LogisticsRequestCache.get(
                AuthorizationContext.requireCurrent().tenantId().toString(),
                action,
                "customersByCities",
                key,
                () -> readLedgerCustomersByCities(key, action));
    }

    private java.util.Map<String, List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView>>
            readLedgerCustomersByCities(
                    java.util.Map<String, List<String>> codesByCity, String action) {
        var uri =
                UriComponentsBuilder.fromUriString(crm)
                        .path("/api/v1/crm/cost-customer-references/batch-cities")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "POST", uri, AuthorizationContext.requireCurrent());
        var response =
                client.post()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .body(codesByCity)
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                java.util.Map<
                                                        String,
                                                        List<
                                                                com.rigour.merchant.api.v1.model
                                                                        .CostCustomerView>>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("客户档案暂不可用，请重试");
        var result =
                new java.util.LinkedHashMap<
                        String, List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView>>();
        response.data()
                .forEach(
                        (city, rows) ->
                                result.put(
                                        city,
                                        rows.stream()
                                                .map(
                                                        r ->
                                                                new com.rigour.order.api.v1.model
                                                                        .LogisticsLedgerPartyView(
                                                                        r.id(),
                                                                        r.code(),
                                                                        r.name(),
                                                                        city,
                                                                        r.address(),
                                                                        r.contactName(),
                                                                        r.phone(),
                                                                        "CUSTOMER"))
                                                .toList()));
        return java.util.Map.copyOf(result);
    }

    @Override
    public List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView> ledgerCustomers(
            String city, List<String> codes, String action) {
        var uri =
                UriComponentsBuilder.fromUriString(crm)
                        .path("/api/v1/crm/cost-customer-references/batch")
                        .queryParam("cityCode", city)
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "POST", uri, AuthorizationContext.requireCurrent());
        var response =
                client.post()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .body(codes)
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                List<
                                                        com.rigour.order.api.v1.model
                                                                .LogisticsCostPartyView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("客户档案暂不可用，请重试");
        return response.data().stream()
                .map(
                        r ->
                                new com.rigour.order.api.v1.model.LogisticsLedgerPartyView(
                                        r.id(),
                                        r.code(),
                                        r.name(),
                                        city,
                                        r.address(),
                                        r.contactName(),
                                        r.phone(),
                                        "CUSTOMER"))
                .toList();
    }

    @Override
    public List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView> ledgerParties(
            String kind,
            String code,
            Long id,
            String keyword,
            String city,
            int begin,
            String action) {
        var key =
                java.util.List.of(
                        kind,
                        java.util.Objects.toString(code, ""),
                        java.util.Objects.toString(id, ""),
                        java.util.Objects.toString(keyword, ""),
                        java.util.Objects.toString(city, ""),
                        Integer.toString(begin));
        return LogisticsRequestCache.get(
                AuthorizationContext.requireCurrent().tenantId().toString(),
                action,
                "party",
                key,
                () -> readLedgerParties(kind, code, id, keyword, city, begin, action));
    }

    private List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView> readLedgerParties(
            String kind,
            String code,
            Long id,
            String keyword,
            String city,
            int begin,
            String action) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("action", action);
        values.put("begin", begin);
        if ("CUSTOMER".equals(kind)) {
            values.put("cityCode", city);
            values.put("keyword", code == null ? keyword : code);
            values.put("id", id);
            var rows =
                    readLedger(
                            crm,
                            "/api/v1/crm/cost-customer-references",
                            values,
                            new ParameterizedTypeReference<
                                    ApiResponse<
                                            List<
                                                    com.rigour.order.api.v1.model
                                                            .LogisticsCostPartyView>>>() {});
            return rows.stream()
                    .filter(r -> code == null || code.equals(r.code()))
                    .map(
                            r ->
                                    new com.rigour.order.api.v1.model.LogisticsLedgerPartyView(
                                            r.id(),
                                            r.code(),
                                            r.name(),
                                            city,
                                            r.address(),
                                            r.contactName(),
                                            r.phone(),
                                            "CUSTOMER"))
                    .toList();
        }
        values.put("kind", kind);
        values.put("code", code);
        values.put("id", id);
        values.put("keyword", keyword);
        return readLedger(
                erp,
                "/api/v1/erp/cost-ledger/parties",
                values,
                new ParameterizedTypeReference<
                        ApiResponse<
                                List<
                                        com.rigour.order.api.v1.model
                                                .LogisticsLedgerPartyView>>>() {});
    }

    @Override
    public List<LogisticsCostSourceView> ledgerSources(
            com.rigour.order.api.v1.model.LogisticsLedgerSourceQuery q, String action) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("action", action);
        values.put("orderTypeCode", q.orderTypeCode());
        values.put("documentNo", q.documentNo());
        values.put("partyName", q.partyName());
        values.put("sourceWarehouseCode", q.sourceWarehouseCode());
        values.put("targetWarehouseCode", q.targetWarehouseCode());
        values.put("cityCode", q.cityCode());
        values.put("dateFrom", q.dateFrom());
        values.put("dateTo", q.dateTo());
        values.put("begin", q.begin());
        values.put("exact", q.exact());
        values.put("historical", q.historical());
        return readLedger(
                erp,
                "/api/v1/erp/cost-ledger/sources",
                values,
                new ParameterizedTypeReference<ApiResponse<List<LogisticsCostSourceView>>>() {});
    }

    private <T> T readLedger(
            String base,
            String path,
            java.util.Map<String, Object> values,
            ParameterizedTypeReference<ApiResponse<T>> type) {
        var builder = UriComponentsBuilder.fromUriString(base).path(path);
        values.forEach(
                (key, value) -> {
                    if (value != null) builder.queryParam(key, value);
                });
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get().uri(uri).headers(h -> headers.forEach(h::set)).retrieve().body(type);
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("费用来源引用暂不可用，请重试");
        return response.data();
    }

    private final RestClient client;
    private final TrustedContextSigner signer;
    private final EffectiveDictionaryClient dictionary;
    private final String erp;
    private String crm;

    public HttpOrderLogisticsReferences(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erp,
            @Value("${rigour.settings.base-url:http://rigour-business-settings-service}")
                    String settings) {
        this.client = builder.build();
        this.signer = signer;
        this.erp = erp;
        this.dictionary =
                new EffectiveDictionaryClient(
                        builder, signer, settings, "rigour-order-center-service");
    }

    @org.springframework.beans.factory.annotation.Autowired
    void crmAddress(
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String value) {
        this.crm = value;
    }

    private String hr;

    @org.springframework.beans.factory.annotation.Autowired
    void hrAddress(@Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String value) {
        this.hr = value;
    }

    @Override
    public List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employees(String action) {
        var uri =
                UriComponentsBuilder.fromUriString(hr)
                        .path("/api/v1/hr/cost-references/employees")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                List<
                                                        com.rigour.order.api.v1.model
                                                                .LogisticsCostEmployeeView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("员工权限目录暂不可用");
        return response.data();
    }

    public String party(String kind, Long id, String city, String action) {
        var builder =
                UriComponentsBuilder.fromUriString("suppliers".equals(kind) ? erp : crm)
                        .path(
                                "suppliers".equals(kind)
                                        ? "/api/v1/erp/logistics-cost-supplier"
                                        : "/api/v1/crm/cost-customer-references")
                        .queryParam("id", id)
                        .queryParam("action", action);
        if (!"suppliers".equals(kind)) builder.queryParam("cityCode", city);
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        if ("suppliers".equals(kind)) {
            var response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<String>>() {});
            if (response == null || !"OK".equals(response.code()) || response.data() == null)
                throw new IllegalStateException("供应商引用暂不可用");
            return response.data();
        }
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                List<
                                                        com.rigour.order.api.v1.model
                                                                .LogisticsCostPartyView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("客户引用暂不可用");
        return response.data().stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new com.rigour.shared.core.exception.BusinessException(
                                        com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                                        "客户不存在、归属城市不符或无权限",
                                        List.of()))
                .name();
    }

    public String warehouse(Long id, String action) {
        return warehouseDetail(id, action).name();
    }

    public com.rigour.order.api.v1.model.LogisticsCostWarehouseView warehouseDetail(
            Long id, String action) {
        var uri =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/logistics-cost-warehouse-detail")
                        .queryParam("id", id)
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<
                                                com.rigour.order.api.v1.model
                                                        .LogisticsCostWarehouseView>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("仓库引用暂不可用");
        return response.data();
    }

    public Set<String> validCodes(String tenant, String code) {
        return LogisticsRequestCache.get(
                tenant, "dictionary", "codes", code, () -> dictionary.activeCodes(tenant, code));
    }

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action) {
        return sources(leg, keyword, ids, action, null, 0);
    }

    public List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action, String city, int begin) {
        var builder =
                UriComponentsBuilder.fromUriString(erp)
                        .path("/api/v1/erp/logistics-cost-sources")
                        .queryParam("legCode", leg)
                        .queryParam("keyword", keyword)
                        .queryParam("action", action);
        builder.queryParam("cityCode", city).queryParam("begin", Math.max(0, begin));
        if (!ids.isEmpty()) builder.queryParam("ids", ids);
        var uri = builder.build().encode().toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<LogisticsCostSourceView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("物流来源暂不可用");
        return response.data();
    }

    private record City(String code, String name) {}

    @Override
    public java.util.Map<String, String> cities(String action) {
        return LogisticsRequestCache.get(
                AuthorizationContext.requireCurrent().tenantId().toString(),
                action,
                "cities",
                "all",
                () -> readCities(action));
    }

    private java.util.Map<String, String> readCities(String action) {
        var uri =
                UriComponentsBuilder.fromUriString(crm)
                        .path("/api/v1/crm/cost-city-references")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        var response =
                client.get()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<List<City>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("授权城市目录暂不可用");
        return response.data().stream()
                .collect(java.util.stream.Collectors.toMap(City::code, City::name));
    }

    public String city(String code, String action) {
        String name = cities(action).get(code);
        if (name == null) throw new com.rigour.shared.context.AuthorizationDeniedException(action);
        return name;
    }
}

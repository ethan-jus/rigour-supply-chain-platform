package com.rigour.sales.infrastructure.integration;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;
import com.rigour.merchant.api.v1.model.FieldworkSources;
import com.rigour.sales.application.port.out.FieldworkDirectory;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.*;
import java.util.*;

@Component
public class HttpFieldworkDirectory implements FieldworkDirectory {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI org, crm;

    public HttpFieldworkDirectory(
            TrustedContextSigner signer,
            RestClient.Builder builder,
            @Value(
                            "${rigour.sales.identity.hr-base-url:${HR_PAYROLL_BASE_URL:http://rigour-hr-payroll-service}}")
                    String hr,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crm) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client = builder.requestFactory(factory).build();
        this.signer = signer;
        this.org = URI.create(hr.replaceAll("/+$", "") + "/internal/v1/hr/fieldwork/organization");
        this.crm = URI.create(crm.replaceAll("/+$", "") + "/internal/v1/crm/fieldwork/sources");
    }

    public List<HrFieldworkOrganization> organization(
            UUID tenant, Instant instant, List<String> codes) {
        URI uri =
                UriComponentsBuilder.fromUri(org)
                        .queryParam("at", instant.toString())
                        .build()
                        .toUri();
        return post(
                tenant,
                uri,
                "hr:employee:identity-read",
                codes,
                new ParameterizedTypeReference<>() {});
    }

    public Map<String, String> salesDepartments(UUID tenant) {
        return post(tenant, URI.create(org + "/sales-departments"), "hr:employee:identity-read",
                List.of(), new ParameterizedTypeReference<>() {});
    }

    public Map<Long, String> storeRegions(UUID tenant, List<Long> stores) {
        return post(tenant, crm.resolve("store-regions"), "crm:fieldwork:source-read", stores,
                new ParameterizedTypeReference<>() {});
    }

    public FieldworkSources.Result sources(UUID tenant, FieldworkSources.Query query) {
        return post(
                tenant,
                crm,
                "crm:fieldwork:source-read",
                query,
                new ParameterizedTypeReference<>() {});
    }

    private <T> T post(
            UUID tenant,
            URI uri,
            String action,
            Object body,
            ParameterizedTypeReference<ApiResponse<T>> type) {
        var response =
                client.post()
                        .uri(uri)
                        .headers(
                                h ->
                                        OutboundContextHeaders.service(
                                                        signer,
                                                        "POST",
                                                        uri,
                                                        "rigour-sales-work-service",
                                                        tenant.toString(),
                                                        action)
                                                .forEach(h::set))
                        .body(body)
                        .retrieve()
                        .body(type);
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("外勤来源未返回数据");
        return response.data();
    }
}

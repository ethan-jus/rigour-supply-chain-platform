package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.api.v1.model.WarehouseDepartmentView;
import com.rigour.erp.application.port.out.WarehouseDepartments;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Component
public final class HttpWarehouseDepartments implements WarehouseDepartments {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String hr;

    public HttpWarehouseDepartments(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hr) {
        this.client = builder.build();
        this.signer = signer;
        this.hr = hr;
    }

    public List<WarehouseDepartmentView> departments(String action) {
        var uri =
                UriComponentsBuilder.fromUriString(hr)
                        .path("/api/v1/hr/cost-references/warehouse-departments")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var response =
                client.get()
                        .uri(uri)
                        .headers(
                                h ->
                                        OutboundContextHeaders.caller(
                                                        signer,
                                                        "GET",
                                                        uri,
                                                        AuthorizationContext.requireCurrent())
                                                .forEach(h::set))
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponse<List<WarehouseDepartmentView>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("仓库归属部门目录暂不可用");
        return response.data();
    }
}

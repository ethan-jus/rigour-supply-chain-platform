package com.rigour.hr.infrastructure.integration;

import com.rigour.hr.application.port.out.HrAttendanceSource;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.settings.client.EffectiveDictionaryClient;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;

@Component
public class HttpHrAttendanceSource implements HrAttendanceSource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;
    private final EffectiveDictionaryClient dictionaries;

    public HttpHrAttendanceSource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.sales.base-url:http://rigour-sales-work-service}") String sales,
            @Value("${rigour.settings.base-url:http://rigour-business-settings-service}")
                    String settings) {
        this.client = builder.build();
        this.dictionaries =
                new EffectiveDictionaryClient(
                        builder, signer, settings, "rigour-hr-payroll-service");
        this.signer = signer;
        this.uri =
                UriComponentsBuilder.fromUriString(sales)
                        .path("/api/v1/sales/attendance-evidence")
                        .build()
                        .toUri();
    }

    public Set<String> leaveTypes(String tenant) {
        return dictionaries.activeCodes(tenant, "ATTENDANCE_LEAVE_TYPE");
    }

    public List<Evidence> evidence(String tenant, Query query) {
        var headers =
                OutboundContextHeaders.service(
                        signer,
                        "POST",
                        uri,
                        "rigour-hr-payroll-service",
                        tenant,
                        "sales:attendance:source-read");
        var response =
                client.post()
                        .uri(uri)
                        .headers(h -> headers.forEach(h::set))
                        .body(query)
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<List<Evidence>>>() {});
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new IllegalStateException("销售打卡暂不可用");
        return response.data();
    }
}

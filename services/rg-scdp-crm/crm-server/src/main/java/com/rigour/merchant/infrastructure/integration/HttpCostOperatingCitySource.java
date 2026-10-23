package com.rigour.merchant.infrastructure.integration;

import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.merchant.application.port.out.CostOperatingCitySource;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;

@Component
public final class HttpCostOperatingCitySource implements CostOperatingCitySource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String hr;

    public HttpCostOperatingCitySource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hr) {
        this.signer = signer;
        this.hr = hr;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = builder.requestFactory(factory).build();
    }

    public List<HrCostCityReference> cities(String action) {
        var uri =
                UriComponentsBuilder.fromUriString(hr)
                        .path("/api/v1/hr/cost-city-references")
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var headers =
                OutboundContextHeaders.caller(
                        signer, "GET", uri, AuthorizationContext.requireCurrent());
        try {
            var response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponse<List<HrCostCityReference>>>() {});
            if (response == null || !"OK".equals(response.code()) || response.data() == null)
                throw unavailable();
            return response.data();
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            throw new AuthorizationDeniedException(action);
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "经营城市目录暂不可用，请稍后重试", List.of());
    }
}

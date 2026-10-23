package com.rigour.hr.infrastructure.integration;

import com.rigour.hr.application.port.out.HrExpenseCitySource;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.OutboundContextHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;

/** 复用 CRM 授权城市目录，转发当前调用者，不使用服务身份扩大城市范围。 */
@Component
public final class HttpHrExpenseCitySource implements HrExpenseCitySource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String crm;

    public HttpHrExpenseCitySource(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crm) {
        this.signer = signer;
        this.crm = crm;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = builder.requestFactory(factory).build();
    }

    private record City(String code, String name) {}

    @Override
    public String cityName(String code, String action) {
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
        ApiResponse<List<City>> response;
        try {
            response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<List<City>>>() {});
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            throw new AuthorizationDeniedException(action);
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw unavailable();
        return response.data().stream()
                .filter(city -> code.equals(city.code()))
                .filter(city -> city.name() != null && !city.name().isBlank())
                .findFirst()
                .orElseThrow(() -> new AuthorizationDeniedException(action))
                .name();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "授权城市目录暂不可用，请稍后重试", List.of());
    }
}

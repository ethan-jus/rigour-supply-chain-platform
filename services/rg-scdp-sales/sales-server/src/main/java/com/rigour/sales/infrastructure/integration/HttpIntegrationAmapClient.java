package com.rigour.sales.infrastructure.integration;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Existing Sales callers retain their port; only Integration talks to Amap. */
@Component
public class HttpIntegrationAmapClient implements AmapPoiClient, TemporaryCheckinReverseGeocoder {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;
    private final String temporaryTenant;
    public HttpIntegrationAmapClient(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://rigour-integration-migration-service}") String base,
            @Value("${rigour.sales.temporary-checkin.tenant-id:}") String temporaryTenant) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(8));
        this.client=builder.requestFactory(factory).build(); this.signer=signer;
        this.base=base.replaceAll("/+$","")+"/internal/v1/integration/amap"; this.temporaryTenant=temporaryTenant;
    }
    public NearbyPoiPage searchAround(String keyword,BigDecimal longitude,BigDecimal latitude,int radius,int page,int size) {
        return get("/pois/around",Map.of("keyword",keyword==null?"":keyword,"longitude",longitude,
                "latitude",latitude,"radius",radius,"page",page,"size",size),new ParameterizedTypeReference<>(){});
    }
    public NearbyPoiPage searchText(String keyword,String city,int page,int size) {
        return get("/pois",Map.of("keyword",keyword,"city",city,"page",page,"size",size),new ParameterizedTypeReference<>(){});
    }
    public NearbyPoi detail(String id) {
        if(id==null || !id.matches("[A-Za-z0-9]{1,64}")) throw new AmapPoiException("POI 编码无效");
        return get("/pois/"+id,Map.of(),new ParameterizedTypeReference<>(){});
    }
    public GeocodeResult resolve(BigDecimal longitude,BigDecimal latitude) {
        try { return get("/reverse-geocode",Map.of("longitude",longitude,"latitude",latitude),new ParameterizedTypeReference<>(){}); }
        catch(AmapPoiException e) { return GeocodeResult.failed(e.reason()); }
    }
    private <T> T get(String path,Map<String,?> parameters,ParameterizedTypeReference<ApiResponse<T>> type) {
        var builder=UriComponentsBuilder.fromUriString(base+path); parameters.forEach(builder::queryParam);
        URI uri=builder.build().encode().toUri();
        String tenant=AuthorizationContext.current().filter(c->c.tenantId()!=null)
                .map(c->c.tenantId().toString()).orElse(temporaryTenant);
        if(tenant==null || tenant.isBlank()) throw new AmapPoiException("查询缺少租户上下文");
        try {
            var response=client.get().uri(uri).headers(h->OutboundContextHeaders.service(signer,"GET",uri,
                    "rigour-sales-work-service",tenant,"integration:amap:read").forEach(h::set)).retrieve().body(type);
            if(response!=null && "OK".equals(response.code()) && response.data()!=null) return response.data();
        } catch(RestClientResponseException e) {
            ApiResponse<Void> error=null;
            try { error=e.getResponseBodyAs(new ParameterizedTypeReference<ApiResponse<Void>>(){}); } catch(RuntimeException ignored) {}
            if(error!=null && error.details()!=null && error.details().stream().anyMatch(d->"AMAP_IP_NOT_ALLOWED".equals(d.reason())))
                throw new AmapPoiException("AMAP_IP_NOT_ALLOWED","高德拒绝集成服务出口 IP，请检查 Web 服务白名单");
        } catch(org.springframework.web.client.RestClientException e) {
            throw new AmapPoiException("AMAP_INTEGRATION_UNAVAILABLE","高德集成服务暂不可用");
        }
        throw new AmapPoiException("AMAP_INTEGRATION_UNAVAILABLE","高德集成服务暂不可用");
    }
}

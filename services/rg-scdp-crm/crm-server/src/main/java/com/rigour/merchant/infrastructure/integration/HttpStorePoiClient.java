package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.api.v1.model.StoreModels.Poi;
import com.rigour.merchant.application.port.out.StorePoiClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reuses Sales' server-side Amap configuration; no provider key reaches CRM or the browser. */
@Component
public class HttpStorePoiClient implements StorePoiClient {
    private static final Logger log=LoggerFactory.getLogger(HttpStorePoiClient.class);
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;
    public HttpStorePoiClient(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.sales.base-url:http://rigour-sales-work-service}") String base) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(8));
        this.client=builder.requestFactory(factory).build();this.signer=signer;this.base=base.replaceAll("/+$","");
    }
    public List<Poi> search(String tenant,String keyword,String areaCode) {
        URI uri=UriComponentsBuilder.fromUriString(base).path("/internal/v1/sales/store-pois")
                .queryParam("keyword",keyword).queryParam("areaCode",areaCode).build().encode().toUri();
        return get(tenant,uri,new ParameterizedTypeReference<ApiResponse<List<Poi>>>(){});
    }
    public Poi detail(String tenant,String id) {
        return get(tenant,URI.create(base+"/internal/v1/sales/store-pois/"+id),new ParameterizedTypeReference<ApiResponse<Poi>>(){});
    }
    private <T>T get(String tenant,URI uri,ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            var result=client.get().uri(uri).headers(h->OutboundContextHeaders.service(signer,"GET",uri,
                    "rigour-merchant-crm-service",tenant,"sales:store-poi:read").forEach(h::set))
                    .retrieve().body(type);
            if(result!=null&&"OK".equals(result.code())&&result.data()!=null)return result.data();
        } catch(RestClientResponseException error) {
            log.warn("门店 POI 服务调用失败 status={}",error.getStatusCode().value());
            ApiResponse<Void> failure=null;
            try {failure=error.getResponseBodyAs(new ParameterizedTypeReference<ApiResponse<Void>>(){});}
            catch(RuntimeException ignored) { /* Never expose arbitrary upstream error bodies. */ }
            if(failure!=null&&failure.details()!=null&&failure.details().stream()
                    .anyMatch(d->"amap".equals(d.field())&&"AMAP_IP_NOT_ALLOWED".equals(d.reason()))) {
                String message="高德拒绝当前服务器 IP（10005），请将 Sales 服务出口公网 IP 加入 Web 服务 Key 的 IP 白名单";
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,message,List.of(new ApiErrorDetail("amap","AMAP_IP_NOT_ALLOWED",message)));
            }
        } catch(RestClientException error) {
            log.warn("门店 POI 服务连接失败 reason={}",error.getClass().getSimpleName());
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"高德门店查询暂不可用，请检查销售服务及 Web 服务 Key 配置，或稍后重试",List.of());
    }
}

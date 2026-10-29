package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.StoreWorkAuthorizationClient;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpStoreWorkAuthorizationClient implements StoreWorkAuthorizationClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;
    public HttpStoreWorkAuthorizationClient(RestClient.Builder builder,TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String base){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(10));
        client=builder.requestFactory(factory).build();this.signer=signer;this.base=base.replaceAll("/+$","");
    }
    public OrderPermit authorize(String store,OrderAuthorization command){
        URI uri=URI.create(base+"/api/v1/crm/stores/"+store+"/order-authorization");
        var r=client.post().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"POST",uri,AuthorizationContext.requireCurrent()).forEach(h::set)).body(command).retrieve().body(new ParameterizedTypeReference<ApiResponse<OrderPermit>>(){});
        if(r==null||!"OK".equals(r.code())||r.data()==null)throw new IllegalStateException("CRM订单作业授权不可用");
        return r.data();
    }
}

package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.application.port.out.StoreVisitCaptureClient;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
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
public class HttpStoreVisitCaptureClient implements StoreVisitCaptureClient {
    private final RestClient client; private final TrustedContextSigner signer; private final String base;
    public HttpStoreVisitCaptureClient(RestClient.Builder builder,TrustedContextSigner signer,
            @Value("${rigour.sales.base-url:http://rigour-sales-work-service}") String base){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(5));
        this.client=builder.requestFactory(factory).build();this.signer=signer;this.base=base.replaceAll("/+$","");
    }
    @Override public Policy policy(){
        var uri=URI.create(base+"/internal/v1/sales/crm-visits/policy");
        var r=client.get().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"GET",uri,AuthorizationContext.requireCurrent()).forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<Policy>>(){});
        if(r==null||!"OK".equals(r.code())||r.data()==null||r.data().id()==null||r.data().radiusMeters()<=0)throw new IllegalStateException("拜访政策来源不可用");return r.data();
    }
    @Override public Facts facts(String tenant,String store,String ownership){
        var uri=URI.create(base+"/internal/v1/sales/crm-visits/facts/"+store+"?ownershipId="+ownership);
        var r=client.get().uri(uri).headers(h->OutboundContextHeaders.service(signer,"GET",uri,"rigour-merchant-crm-service",tenant,"sales:store-visit:source-read").forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<Facts>>(){});
        if(r==null||!"OK".equals(r.code())||r.data()==null)throw new IllegalStateException("拜访事实来源不可用");var f=r.data();
        if(!tenant.equals(f.tenantId())||!store.equals(f.storeId())||!ownership.equals(f.ownershipId())||f.checkedAt()==null||f.items()==null)throw new IllegalStateException("拜访事实范围不一致");return f;
    }
}

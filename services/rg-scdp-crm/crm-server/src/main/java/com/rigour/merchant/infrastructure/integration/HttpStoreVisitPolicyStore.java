package com.rigour.merchant.infrastructure.integration;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy;
import com.rigour.merchant.application.port.out.StoreVisitPolicyStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.StateConflictException;
import java.net.URI;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
/** New visits read Sales policy. Historical CRM policy snapshots are never rewritten. */
@Component
public class HttpStoreVisitPolicyStore implements StoreVisitPolicyStore {
    private final RestClient client;private final TrustedContextSigner signer;private final String base;
    public HttpStoreVisitPolicyStore(RestClient.Builder builder,TrustedContextSigner signer,@Value("${rigour.sales.base-url:http://rigour-sales-work-service}") String base) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(10));
        this.client=builder.requestFactory(factory).defaultStatusHandler(status->status.value()==403,(request,response)->{throw new AuthorizationDeniedException("sales:context:read");}).defaultStatusHandler(status->status.value()==409,(request,response)->{throw new StateConflictException("Sales 尚未配置适用的拜访规则，请联系管理员");}).build();this.signer=signer;this.base=base.replaceAll("/+$","");
    }
    public Policy active(StoreScope scope,String storeId,Instant now) {
        var caller=AuthorizationContext.requireCurrent();
        if(!scope.access().tenantId().equals(caller.tenantId().toString())) throw new AuthorizationDeniedException("sales:context:read");
        var uri=URI.create(base+"/api/v1/sales/me/visit-policy");
        try {
            var response=client.get().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"GET",uri,caller).forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<Policy>>(){});
            if(response==null || !"OK".equals(response.code()) || response.data()==null) throw new StateConflictException("Sales 拜访规则暂不可用，请稍后重试");
            return response.data();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

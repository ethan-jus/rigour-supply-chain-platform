package com.rigour.sales.infrastructure.integration;
import com.rigour.sales.application.port.out.CrmStoreVisitClient;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.http.HttpStatusCode;
import com.rigour.shared.core.exception.StateConflictException;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
@Component
public class HttpCrmStoreVisitClient implements CrmStoreVisitClient {
    private final RestClient client;private final TrustedContextSigner signer;private final String base;
    public HttpCrmStoreVisitClient(RestClient.Builder builder,TrustedContextSigner signer,@Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String base){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(10));
        client=builder.requestFactory(factory).defaultStatusHandler(HttpStatusCode::is4xxClientError,(request,response)->{
            int status=response.getStatusCode().value();
            ErrorCode code=switch(status){case 400 -> ErrorCode.BAD_REQUEST;case 403 -> ErrorCode.FORBIDDEN;case 404 -> ErrorCode.NOT_FOUND;case 409 -> ErrorCode.CONFLICT;default -> ErrorCode.SERVICE_UNAVAILABLE;};
            if(status==409){
                try{var body=new tools.jackson.databind.ObjectMapper().readTree(response.getBody());
                    if(ErrorCode.CRM_STORE_VISIT_OUTSIDE_RADIUS.getCode().equals(body.path("code").asText()))code=ErrorCode.CRM_STORE_VISIT_OUTSIDE_RADIUS;
                    if(ErrorCode.LOCATION_ACCURACY_INSUFFICIENT.getCode().equals(body.path("code").asText()))code=ErrorCode.LOCATION_ACCURACY_INSUFFICIENT;
                }catch(RuntimeException ignored){/* Unknown upstream bodies use the safe status message. */}
            }
            throw new BusinessException(code);
        }).build();this.signer=signer;this.base=base.replaceAll("/+$","");
    }
    public com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page ownVisits(java.time.LocalDate from,java.time.LocalDate to,String kind,String status,String keyword,int page,int size) {
        var uri=org.springframework.web.util.UriComponentsBuilder.fromUriString(base+"/api/v1/crm/store-visits/me")
            .queryParam("from",from).queryParam("to",to).queryParam("kind",kind).queryParam("status",status)
            .queryParam("keyword",keyword==null?"":keyword).queryParam("page",page).queryParam("size",size).build().encode().toUri();
        var response=client.get().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"GET",uri,AuthorizationContext.requireCurrent()).forEach(h::set))
            .retrieve().body(new ParameterizedTypeReference<ApiResponse<com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page>>(){});
        if(response==null||!"OK".equals(response.code())||response.data()==null)throw new StateConflictException("拜访事实查询暂不可用");
        return response.data();
    }
    public Visit checkIn(String store,CheckIn c){return post(store+"/visits",c);}
    public Visit cancel(String store,String visit,CancelVisit c){return post(store+"/visits/"+visit+"/cancel",c);}
    private Visit post(String path,Object command){var uri=URI.create(base+"/api/v1/crm/stores/"+path);var r=client.post().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"POST",uri,AuthorizationContext.requireCurrent()).forEach(h::set)).body(command).retrieve().body(new ParameterizedTypeReference<ApiResponse<Visit>>(){});if(r==null||!"OK".equals(r.code())||r.data()==null)throw new StateConflictException("CRM拜访受理未确认，请重试原请求");return r.data();}
    public Context context(String store,String visit){var uri=URI.create(base+"/api/v1/crm/stores/"+store+"/visits/"+visit+"/capture");var r=client.get().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"GET",uri,AuthorizationContext.requireCurrent()).forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<Context>>(){});if(r==null||!"OK".equals(r.code())||r.data()==null)throw new StateConflictException("CRM拜访上下文不可用");return r.data();}
    public String evidenceAccess(String store,String visit){var uri=URI.create(base+"/api/v1/crm/stores/"+store+"/visits/"+visit+"/evidence-access");var r=client.get().uri(uri).headers(h->OutboundContextHeaders.caller(signer,"GET",uri,AuthorizationContext.requireCurrent()).forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<String>>(){});if(r==null||!"OK".equals(r.code())||r.data()==null)throw new AuthorizationDeniedException("crm:store:history-read");return r.data();}

}

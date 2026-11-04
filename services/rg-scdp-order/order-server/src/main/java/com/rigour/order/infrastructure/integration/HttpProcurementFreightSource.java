package com.rigour.order.infrastructure.integration;
import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.order.application.port.out.ProcurementFreightSource;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
@Component
public final class HttpProcurementFreightSource implements ProcurementFreightSource {
    private final RestClient client;private final TrustedContextSigner signer;private final String erp;
    public HttpProcurementFreightSource(RestClient.Builder builder,TrustedContextSigner signer,@Value("${rigour.erp.base-url:http://rigour-erp-core-service}")String erp){this.client=builder.build();this.signer=signer;this.erp=erp;}
    public ProcurementCostSource source(String tenant,String code){
        var uri=UriComponentsBuilder.fromUriString(erp).path("/api/v1/erp/internal/cost-sources/procurements/{code}").buildAndExpand(code).encode().toUri();
        var headers=OutboundContextHeaders.service(signer,"GET",uri,"rigour-order-center-service",tenant,"erp:procurement:cost-source-read");
        var response=client.get().uri(uri).headers(h->headers.forEach(h::set)).retrieve().body(new ParameterizedTypeReference<ApiResponse<ProcurementCostSource>>(){});
        if(response==null || !"OK".equals(response.code()) || response.data()==null)throw new IllegalStateException("已确认采购来源暂不可用");
        return response.data();
    }
}

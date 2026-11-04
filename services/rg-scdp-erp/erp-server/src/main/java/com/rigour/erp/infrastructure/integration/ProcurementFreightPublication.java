package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.ProcurementCostSources;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 采购本身是可重读来源；失败继续重试，接收端唯一来源键保证多实例不重复建单。 */
@Component
public final class ProcurementFreightPublication {
    private static final Logger log=LoggerFactory.getLogger(ProcurementFreightPublication.class);
    private final ProcurementCostSources sources;
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String order;
    private long cursor;
    public ProcurementFreightPublication(ProcurementCostSources sources,RestClient.Builder builder,TrustedContextSigner signer,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String order){
        this.sources=sources;this.client=builder.build();this.signer=signer;this.order=order;
    }
    @Scheduled(initialDelayString="${rigour.cost.procurement-freight.initial-delay-ms:60000}",fixedDelayString="${rigour.cost.procurement-freight.retry-ms:60000}")
    public synchronized void retry() {
        var rows=sources.candidates(cursor,50);
        if(rows.isEmpty()){cursor=0;return;}
        for(var row:rows){
            try{publish(row.tenantId(),row.procurementNo());}
            catch(Exception e){log.warn("采购约定运费来源发布失败，下一轮重试 tenant={} code={} error={}",row.tenantId(),row.procurementNo(),e.getClass().getSimpleName());}
            cursor=row.id();
        }
        if(rows.size()<50)cursor=0;
    }
    private void publish(String tenant,String code){
        var uri=UriComponentsBuilder.fromUriString(order).path("/api/v1/orders/internal/logistics-costs/procurements/{code}").buildAndExpand(code).encode().toUri();
        var headers=OutboundContextHeaders.service(signer,"POST",uri,"rigour-erp-core-service",tenant,"order:logistics-cost:procurement-sync");
        var response=client.post().uri(uri).headers(h->headers.forEach(h::set)).retrieve().body(ApiResponse.class);
        if(response==null || !"OK".equals(response.code()))throw new IllegalStateException("采购运费来源未接收");
    }
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    static class Scheduling {}
}

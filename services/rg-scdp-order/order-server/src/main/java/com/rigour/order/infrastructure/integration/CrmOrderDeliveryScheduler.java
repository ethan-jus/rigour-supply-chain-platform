package com.rigour.order.infrastructure.integration;

import com.rigour.order.infrastructure.persistence.repository.JdbcStoreOrderOutbox;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="rigour.order.crm-delivery.enabled",havingValue="true")
public class CrmOrderDeliveryScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(CrmOrderDeliveryScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcStoreOrderOutbox outbox;
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;
    private final Clock clock;
    public CrmOrderDeliveryScheduler(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,
            JdbcStoreOrderOutbox outbox,RestClient.Builder builder,TrustedContextSigner signer,Clock clock,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String base) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.outbox=outbox; this.signer=signer; this.clock=clock;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(8));
        client=builder.requestFactory(factory).build(); uri=URI.create(base.replaceAll("/+$","")+"/internal/v1/crm/store-facts/orders");
    }
    @Scheduled(fixedDelayString="${rigour.order.crm-delivery.delay:5000}",initialDelayString="15000")
    public void tick() {
        outbox.expirePending();
        for(var row:jdbc.queryForList("SELECT tenant_id,id FROM order_sales_order WHERE store_id IS NOT NULL AND crm_fact_revision=0 ORDER BY id LIMIT 100"))
            outbox.enqueue(row.get("tenant_id").toString(),((Number)row.get("id")).longValue());
        var messages=tx.execute(status->{
            var rows=jdbc.queryForList("SELECT id,tenant_id,payload_json,retry_count FROM order_crm_result_outbox WHERE status_code='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED",Timestamp.from(clock.instant()));
            for(var row:rows) jdbc.update("UPDATE order_crm_result_outbox SET next_retry_at=? WHERE id=?",Timestamp.from(clock.instant().plusSeconds(240)),row.get("id"));
            return rows;
        });
        for(var message:Objects.requireNonNull(messages)) {
            try {
                var result=client.post().uri(uri).headers(h->OutboundContextHeaders.service(signer,"POST",uri,
                        "rigour-order-center-service",message.get("tenant_id").toString(),"crm:store:order-source-write").forEach(h::set))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(message.get("payload_json").toString())
                        .retrieve().body(new ParameterizedTypeReference<ApiResponse<Void>>(){});
                if(result==null || !"OK".equals(result.code())) throw new IllegalStateException("CRM receipt missing");
                jdbc.update("UPDATE order_crm_result_outbox SET status_code='DELIVERED',delivered_at=?,next_retry_at=NULL WHERE id=? AND status_code='PENDING'",Timestamp.from(clock.instant()),message.get("id"));
            } catch(RuntimeException e) {
                int attempts=((Number)message.get("retry_count")).intValue();
                jdbc.update("UPDATE order_crm_result_outbox SET retry_count=retry_count+1,next_retry_at=? WHERE id=? AND status_code='PENDING'",
                        Timestamp.from(clock.instant().plusSeconds(Math.min(300,5L<<Math.min(attempts,6)))),message.get("id"));
                LOG.warn("CRM order delivery pending cause={}",e.getClass().getSimpleName());
            }
        }
    }
}

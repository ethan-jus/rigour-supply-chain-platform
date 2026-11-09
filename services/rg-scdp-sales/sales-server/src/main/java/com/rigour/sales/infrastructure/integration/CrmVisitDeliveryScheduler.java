package com.rigour.sales.infrastructure.integration;

import com.rigour.sales.application.port.out.CrmVisitEvidenceOutbox;
import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
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
@ConditionalOnProperty(name="rigour.sales.crm-delivery.enabled",havingValue="true")
public class CrmVisitDeliveryScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(CrmVisitDeliveryScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CrmVisitEvidenceOutbox outbox;
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;
    private final Clock clock;
    public CrmVisitDeliveryScheduler(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,
            CrmVisitEvidenceOutbox outbox,RestClient.Builder builder,TrustedContextSigner signer,Clock clock,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String base) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.outbox=outbox; this.signer=signer; this.clock=clock;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(8));
        client=builder.requestFactory(factory).build(); uri=URI.create(base.replaceAll("/+$","")+"/internal/v1/crm/store-facts/visits");
    }
    @Scheduled(fixedDelayString="${rigour.sales.crm-delivery.delay:5000}",initialDelayString="15000")
    public void tick() {
        // Existing captures are seeded once; unfinished captures publish checkpoints for safe expiry.
        var captures=jdbc.queryForList("""
                SELECT v.tenant_id,v.id FROM sales_visit v WHERE v.crm_visit_id IS NOT NULL
                AND (v.crm_last_enqueued_at IS NULL OR ((v.checked_out_at IS NULL OR v.result_submitted_at IS NULL)
                AND v.crm_last_enqueued_at<?))
                AND NOT EXISTS(SELECT 1 FROM sales_outbox_event e WHERE e.tenant_id=v.tenant_id AND e.aggregate_id=v.id
                    AND e.event_type='CrmVisitEvidenceChanged' AND e.publish_status='PENDING')
                ORDER BY v.crm_last_enqueued_at,v.id LIMIT 50
                """,Timestamp.from(clock.instant().minusSeconds(30)));
        for(var capture:captures) {
            try { outbox.enqueue(SalesUuidCodec.decode((byte[])capture.get("tenant_id")),SalesUuidCodec.decode((byte[])capture.get("id"))); }
            catch(RuntimeException e) { LOG.warn("CRM evidence enqueue failed cause={}",e.getClass().getSimpleName()); }
        }
        var messages=tx.execute(status->{
            var rows=jdbc.queryForList("""
                    SELECT id,tenant_id,payload_json,retry_count FROM sales_outbox_event
                    WHERE event_type='CrmVisitEvidenceChanged' AND publish_status='PENDING'
                    AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY occurred_at,id LIMIT 20 FOR UPDATE SKIP LOCKED
                    """,Timestamp.from(clock.instant()));
            for(var row:rows) jdbc.update("UPDATE sales_outbox_event SET next_retry_at=? WHERE id=?",
                    Timestamp.from(clock.instant().plusSeconds(240)),row.get("id"));
            return rows;
        });
        for(var message:Objects.requireNonNull(messages)) {
            try {
                String tenant=SalesUuidCodec.decode((byte[])message.get("tenant_id")).toString();
                var result=client.post().uri(uri).headers(h->OutboundContextHeaders.service(signer,"POST",uri,
                        "rigour-sales-work-service",tenant,"crm:store:visit-source-write").forEach(h::set))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(message.get("payload_json").toString())
                        .retrieve().body(new ParameterizedTypeReference<ApiResponse<Void>>(){});
                if(result==null || !"OK".equals(result.code())) throw new IllegalStateException("CRM receipt missing");
                jdbc.update("UPDATE sales_outbox_event SET publish_status='PUBLISHED',published_at=?,next_retry_at=NULL WHERE id=? AND publish_status='PENDING'",
                        Timestamp.from(clock.instant()),message.get("id"));
            } catch(RuntimeException e) {
                int attempts=((Number)message.get("retry_count")).intValue();
                jdbc.update("UPDATE sales_outbox_event SET retry_count=retry_count+1,next_retry_at=? WHERE id=? AND publish_status='PENDING'",
                        Timestamp.from(clock.instant().plusSeconds(Math.min(300,5L<<Math.min(attempts,6)))),message.get("id"));
                LOG.warn("CRM evidence delivery pending cause={}",e.getClass().getSimpleName());
            }
        }
    }
}

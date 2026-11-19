package com.rigour.sales.application.service;
import com.rigour.sales.application.port.out.SalesFieldworkRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Clock;
import org.slf4j.LoggerFactory;

/** Versioned read model; UI lists never load each employee's raw route. */
@Component
@ConditionalOnProperty(name="rigour.sales.fieldwork.metrics-enabled",havingValue="true",matchIfMissing=true)
public class SalesFieldworkMetricsWorker {
    private final SalesFieldworkRepository summaries;
    private final SalesWorkAttendanceRepository points;
    private final SalesWorkQueryRepository policies;
    private final Clock clock;
    private final com.rigour.sales.application.port.out.SalesFieldworkReviewRepository reviews;
    private final tools.jackson.databind.ObjectMapper json;
    public SalesFieldworkMetricsWorker(SalesFieldworkRepository summaries,SalesWorkAttendanceRepository points,SalesWorkQueryRepository policies,Clock clock,com.rigour.sales.application.port.out.SalesFieldworkReviewRepository reviews,tools.jackson.databind.ObjectMapper json) {
        this.summaries=summaries;this.points=points;this.policies=policies;this.clock=clock;this.reviews=reviews;this.json=json;
    }
    @Scheduled(fixedDelayString="${rigour.sales.fieldwork.metrics-delay-ms:30000}",initialDelayString="${rigour.sales.fieldwork.metrics-delay-ms:30000}")
    public void refresh() {
        for(var day:summaries.pendingSummaries(20)) {
            try {
                var policy=policies.findFieldPolicy(day.tenantId(),day.fieldPolicyId()).orElseThrow();
                var result=SalesTrackMetrics.calculate(points.findLocationPoints(day.tenantId(),day.workDayId()),policy.locationSampleIntervalSeconds());
                for (var gap : result.gaps()) {
                    String key=day.workDayId()+":TRACK:"+gap.from()+":"+gap.to();
                    reviews.detect(day.tenantId(),day.salesProfileId(),day.workDayId(),null,key,"TRACK_GAP",
                            "轨迹缺失待核查",json.writeValueAsString(java.util.Map.of("from",gap.from().toString(),"to",gap.to().toString(),"reason",gap.reason(),"sourceVersion",day.version())),clock.instant());
                }
                summaries.saveSummary(day,result.available()?result.distanceMeters():null,result.trackedMinutes(),result.gaps().size(),result.rejected(),clock.instant());
            } catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Fieldwork metrics deferred workDayId={} type={}",day.workDayId(),ex.getClass().getSimpleName()); }
        }
    }
}

package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stop collection at the pinned cutoff; preserve missing checkout and evidence for review. */
@Component
public class SalesWorkDayExpiryWorker {
    private final SalesWorkAttendanceRepository days;
    private final SalesWorkVisitRepository visits;
    private final SalesFieldworkReviewRepository reviews;
    private final CrmVisitEvidenceOutbox outbox;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public SalesWorkDayExpiryWorker(SalesWorkAttendanceRepository days, SalesWorkVisitRepository visits,
            SalesFieldworkReviewRepository reviews, CrmVisitEvidenceOutbox outbox, Clock clock, PlatformTransactionManager manager) {
        this.days=days;this.visits=visits;this.reviews=reviews;this.outbox=outbox;this.clock=clock;
        this.transaction=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void expire() {
        for (var day : days.findExpiredWorkDays(clock.instant())) {
            try { expire(day); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Work day cutoff deferred day={} cause={}",day.workDayId(),error.getClass().getSimpleName()); }
        }
    }
    public void expireOwn(UUID tenant, UUID profile) {
        for (var day : days.findExpiredWorkDays(clock.instant(),tenant,profile)) expire(day);
    }
    private void expire(SalesWorkAttendanceRepository.ExpiryCandidate day) {
        transaction.executeWithoutResult(status -> {
            if(days.expireWorkDay(day.tenantId(),day.workDayId(),day.cutoff())==0) return;
            reviews.detect(day.tenantId(),day.salesProfileId(),day.workDayId(),null,"missing-checkout:"+day.workDayId(),
                    "ATTENDANCE","缺签退待核查","{}",clock.instant());
            for(var visit:visits.findVisitsByWorkDay(day.tenantId(),day.salesProfileId(),day.workDayId()))
                if("WORK_DAY_CUTOFF".equals(visit.finalReasonCode())) outbox.enqueue(day.tenantId(),visit.id());
        });
    }
}

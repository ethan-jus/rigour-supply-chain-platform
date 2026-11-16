package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesRecordingAnalysisModels.*;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SalesRecordingAnalysisService {
    private final SalesRecordingAnalysisRepository repository;
    private final SalesWorkVisitRepository visits;
    private final SalesWorkContextService context;
    private final SalesFieldworkService fieldwork;
    private final AuditSink audit;
    private final Clock clock;
    private final boolean enabled;

    public SalesRecordingAnalysisService(SalesRecordingAnalysisRepository repository, SalesWorkVisitRepository visits,
            SalesWorkContextService context, SalesFieldworkService fieldwork, AuditSink audit, Clock clock,
            @Value("${rigour.sales.fieldwork.ai-enabled:false}") boolean enabled) {
        this.repository=repository;this.visits=visits;this.context=context;this.fieldwork=fieldwork;
        this.audit=audit;this.clock=clock;this.enabled=enabled;
    }

    public Visit read(UUID visitId, boolean management) {
        var caller=authorize(visitId,management,false);
        if (management) audit(caller,visitId,"SALES_TRANSCRIPT_REVIEW_READ",Map.of());
        return result(caller,visitId);
    }

    public Visit retry(UUID visitId, UUID clipId, boolean management) {
        var caller=authorize(visitId,management,true);
        if (!enabled) throw new BusinessException(ErrorCode.SALES_ADMIN_INVALID,"录音识别服务尚未启用",java.util.List.of());
        if (repository.retry(caller.tenantId(),visitId,clipId,clock.instant())!=1)
            throw new BusinessException(ErrorCode.SALES_ADMIN_INVALID,"当前片段无需重试，请刷新状态",java.util.List.of());
        audit(caller,visitId,"SALES_TRANSCRIPT_RETRY",Map.of("clipId",clipId.toString()));
        return result(caller,visitId);
    }

    private CallerIdentity authorize(UUID visitId, boolean management, boolean write) {
        var caller=SalesWorkContextService.requireTenantCaller();
        if (management) {
            fieldwork.requireVisit(visitId,"sales:recording:sensitive:play");
            if (write) fieldwork.requireVisit(visitId,"sales:visit:review");
            if (visits.findTargetSnapshot(caller.tenantId(),visitId).isEmpty())
                throw new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND);
        } else {
            AuthorizationContext.requirePermission(write?"sales:recording:own:write":"sales:recording:own:read");
            var identity=context.resolveIdentity(caller,clock.instant());
            if (visits.findVisit(caller.tenantId(),identity.profile().id(),visitId).isEmpty())
                throw new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND);
        }
        return caller;
    }

    private Visit result(CallerIdentity caller, UUID visitId) {
        return new Visit(visitId,enabled,repository.list(caller.tenantId(),visitId).stream().map(row ->
                new Clip(row.clipId(),row.clipIndex(),row.status(),row.transcript(),
                        row.segments().stream().map(s->new Segment(s.startMs(),s.endMs(),s.text(),s.speakerId())).toList(),
                        row.summaryStatus(),row.summary(),row.summaryModel(),row.errorCode(),row.updatedAt())).toList());
    }

    private void audit(CallerIdentity caller, UUID visitId, String action, Map<String,String> detail) {
        audit.append(new AuditEvent(caller.tenantId().toString(),RequestContext.getRequestId(),caller.userId().toString(),
                action,"SALES_VISIT",visitId.toString(),detail,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
    }
}

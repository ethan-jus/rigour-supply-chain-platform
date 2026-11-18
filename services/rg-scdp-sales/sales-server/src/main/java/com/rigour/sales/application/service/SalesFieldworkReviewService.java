package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.*;
import com.rigour.sales.application.port.out.SalesFieldworkReviewRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesFieldworkReviewService {
    private final SalesFieldworkReviewRepository cases;
    private final SalesWorkAttendanceRepository days;
    private final SalesWorkContextService context;
    private final SalesFieldworkService fieldwork;
    private final AuditSink audit;
    private final Clock clock;

    public SalesFieldworkReviewService(SalesFieldworkReviewRepository cases, SalesWorkAttendanceRepository days,
            SalesWorkContextService context, SalesFieldworkService fieldwork, AuditSink audit, Clock clock) {
        this.cases=cases;this.days=days;this.context=context;this.fieldwork=fieldwork;this.audit=audit;this.clock=clock;
    }

    public List<Case> own(LocalDate date) {
        var caller=SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:context:read");
        var identity=context.resolveIdentity(caller,clock.instant());
        return days.findWorkDay(caller.tenantId(),identity.profile().id(),date)
                .map(day->cases.byDay(caller.tenantId(),day.id())).orElse(List.of());
    }

    public List<Case> managementDay(UUID profile, UUID day) {
        var caller=SalesWorkContextService.requireTenantCaller();
        fieldwork.requireProfile(profile,"sales:risk:read");
        if(days.findWorkDay(caller.tenantId(),profile,day).isEmpty()) throw new RequestValidationException("当日作业不存在");
        return cases.byDay(caller.tenantId(),day);
    }

    public List<Case> managementVisit(UUID visit) {
        var caller=SalesWorkContextService.requireTenantCaller();
        fieldwork.requireVisit(visit,"sales:risk:read");
        return cases.byVisit(caller.tenantId(),visit);
    }

    @Transactional
    public Case adjustment(UUID dayId, Statement command) {
        if (command == null) throw new RequestValidationException("请填写补正说明");
        var caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:work-day:write");
        var identity = context.resolveIdentity(caller, clock.instant());
        var day = days.findWorkDay(caller.tenantId(), identity.profile().id(), dayId)
                .orElseThrow(() -> new RequestValidationException("当日作业不存在"));
        String key = text(command.requestId(),100), note = text(command.note(),2000);
        String evidenceKey = "attendance-adjustment:" + dayId;
        var previous = cases.findEvidence(caller.tenantId(), evidenceKey);
        if (previous.isPresent() && replayed(caller,previous.get().id(),key,"STATEMENT",null,note))
            return detail(caller,previous.get());
        requireAdjustment(caller,day);
        cases.detect(caller.tenantId(),identity.profile().id(),dayId,null,evidenceKey,"ATTENDANCE",
                "打卡补正申请","{}",clock.instant());
        var found = cases.findEvidence(caller.tenantId(),evidenceKey).orElseThrow();
        var item = cases.find(caller.tenantId(),found.id(),true).orElseThrow();
        if (!replayed(caller,item.id(),key,"STATEMENT",null,note)) {
            cases.append(caller.tenantId(),item.id(),caller.userId(),key,"STATEMENT",null,note,"EXPLAINED",clock.instant());
            audit(caller,item.id(),"SALES_ATTENDANCE_ADJUSTMENT_REQUEST",Map.of("workDayId",dayId.toString()));
        }
        return detail(caller,item);
    }

    private void requireAdjustment(CallerIdentity caller, SalesWorkAttendanceRepository.WorkDaySnapshot day) {
        var policy = context.resolvePolicyVersion(caller.tenantId(), day.fieldPolicyVersionId());
        if (!policy.allowAdjustment() || policy.adjustmentDeadlineHours() == null)
            throw new RequestValidationException("该作业日未开放打卡补正");
        var deadline = day.businessDate().plusDays(1).atTime(policy.businessDayCutoff())
                .atZone(java.time.ZoneId.of(day.timezoneId())).toInstant().plusSeconds(policy.adjustmentDeadlineHours()*3600L);
        if (clock.instant().isAfter(deadline)) throw new RequestValidationException("已超过打卡补正申请期限");
    }

    @Transactional
    public Case start(UUID profile,UUID day,com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview command) {
        var caller=SalesWorkContextService.requireTenantCaller();
        fieldwork.requireProfile(profile,"sales:risk:write");
        if(command==null||!Set.of("DEVICE","LOCATION").contains(command.kind()==null?"":command.kind()))
            throw new RequestValidationException("核查类型无效");
        if(days.findWorkDay(caller.tenantId(),profile,day).isEmpty()) throw new RequestValidationException("当日作业不存在");
        if("LOCATION".equals(command.kind())) fieldwork.requireProfile(profile,"sales:location:sensitive:read");
        String key=text(command.requestId(),100),note=text(command.note(),2000);
        String evidenceKey="manual:"+day+":"+command.kind();
        cases.detect(caller.tenantId(),profile,day,null,evidenceKey,command.kind(),
                "DEVICE".equals(command.kind())?"设备使用核查":"出发与收工地点核查","{\"source\":\"MANUAL_REVIEW\"}",clock.instant());
        var found=cases.findEvidence(caller.tenantId(),evidenceKey).orElseThrow();
        var item=cases.find(caller.tenantId(),found.id(),true).orElseThrow();
        if(!replayed(caller,item.id(),key,"REVIEW","NEEDS_INFO",note)) {
            if(item.version()!=0) throw new RequestValidationException("已有核查事项，请刷新后在处理记录中继续");
            cases.append(caller.tenantId(),item.id(),caller.userId(),key,"REVIEW","NEEDS_INFO",note,"AWAITING_STATEMENT",clock.instant());
            audit(caller,item.id(),"SALES_FIELDWORK_REVIEW",Map.of("decision","NEEDS_INFO","kind",command.kind()));
        }
        return detail(caller,item);
    }

    @Transactional
    public Case statement(UUID id, Statement command) {
        if(command==null) throw new RequestValidationException("说明不能为空");
        var caller=SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:work-day:write");
        var identity=context.resolveIdentity(caller,clock.instant());
        var item=cases.find(caller.tenantId(),id,true).orElseThrow(()->new RequestValidationException("核查事项不存在"));
        if(!identity.profile().id().equals(item.salesProfileId())) throw new AuthorizationDeniedException("sales:work-day:write");
        String key=text(command.requestId(),100),note=text(command.note(),2000);
        if(!replayed(caller,id,key,"STATEMENT",null,note)) {
            if("ATTENDANCE".equals(item.kind())) requireAdjustment(caller,days.findWorkDay(caller.tenantId(),identity.profile().id(),item.workDayId()).orElseThrow());
            // A later explanation reopens review; previous conclusions remain in immutable history.
            cases.append(caller.tenantId(),id,caller.userId(),key,"STATEMENT",null,note,"EXPLAINED",clock.instant());
            audit(caller,id,"SALES_FIELDWORK_STATEMENT",Map.of());
        }
        return detail(caller,item);
    }

    @Transactional
    public Case review(UUID id, Review command) {
        if(command==null) throw new RequestValidationException("核查内容不能为空");
        var caller=SalesWorkContextService.requireTenantCaller();
        var item=cases.find(caller.tenantId(),id,true).orElseThrow(()->new RequestValidationException("核查事项不存在"));
        fieldwork.requireProfile(item.salesProfileId(),"sales:risk:write");
        String decision=text(command.decision(),32),key=text(command.requestId(),100),note=text(command.note(),2000);
        if(!Set.of("CLEARED","NEEDS_INFO","CONFIRMED").contains(decision)) throw new RequestValidationException("核查结论无效");
        if(!replayed(caller,id,key,"REVIEW",decision,note)) {
            if(item.version()!=command.expectedVersion()) throw new RequestValidationException("已新增说明或核查记录，请刷新后再处理");
            String status=switch(decision) { case "CLEARED"->"RESOLVED";case "NEEDS_INFO"->"AWAITING_STATEMENT";default->"CONFIRMED"; };
            cases.append(caller.tenantId(),id,caller.userId(),key,"REVIEW",decision,note,status,clock.instant());
            audit(caller,id,"SALES_FIELDWORK_REVIEW",Map.of("decision",decision));
        }
        return detail(caller,item);
    }

    private boolean replayed(CallerIdentity caller,UUID id,String key,String type,String decision,String note) {
        var previous=cases.command(caller.tenantId(),id,caller.userId(),key);
        if(previous.isEmpty()) return false;
        var command=previous.get();
        if(!Objects.equals(command.type(),type)||!Objects.equals(command.decision(),decision)||!Objects.equals(command.note(),note))
            throw new RequestValidationException("请求标识已用于不同内容");
        return true;
    }
    private Case detail(CallerIdentity caller,Case item) { return cases.byDay(caller.tenantId(),item.workDayId()).stream().filter(c->c.id().equals(item.id())).findFirst().orElseThrow(); }
    private static String text(String value,int max) { if(value==null||value.isBlank()||value.length()>max) throw new RequestValidationException("内容不能为空且不能超过"+max+"字");return value.strip(); }
    private void audit(CallerIdentity caller,UUID id,String action,Map<String,String> detail) {
        audit.append(new AuditEvent(caller.tenantId().toString(),RequestContext.getRequestId(),caller.userId().toString(),action,"SALES_FIELDWORK_CASE",id.toString(),detail,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
    }
}

package com.rigour.sales.application.service;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
/** 新入口只创建采集工作区，证据处理继续复用销售现有服务。 */
@Service
public class StoreVisitCaptureService {
    public record Command(String requestId,String storeId,String ownershipId,UUID workDayId,LocationEvidence location){}
    public record Created(String visitId,UUID captureId,String storeId,String ownershipId){}
    private final SalesWorkContextService context;private final SalesWorkAttendanceRepository days;private final SalesWorkQueryRepository queries;private final StoreVisitCaptureStore store;private final CrmStoreVisitClient crm;private final Clock clock;private final SalesWorkVisitRepository visits;
    public StoreVisitCaptureService(SalesWorkContextService context,SalesWorkAttendanceRepository days,SalesWorkQueryRepository queries,StoreVisitCaptureStore store,CrmStoreVisitClient crm,Clock clock,SalesWorkVisitRepository visits){this.context=context;this.days=days;this.queries=queries;this.store=store;this.crm=crm;this.clock=clock;this.visits=visits;}
    public Policy policy(){
        var actor=AuthorizationContext.requireCurrent();AuthorizationContext.requirePermission("sales:visit:own:write");
        var identity=context.resolveIdentity(actor,clock.instant());var policy=queries.findActiveVisitPolicy(actor.tenantId(),identity.profile().id(),identity.profile().cityOrgId(),clock.instant()).orElseThrow(()->new StateConflictException("未发布销售拜访政策"));
        return new Policy(policy.id().toString(),policy.checkInRadiusMeters());
    }
    public Created create(Command c){
        AuthorizationContext.requirePermission("sales:visit:own:write");var actor=AuthorizationContext.requireCurrent();
        if(c==null||c.requestId()==null||!c.requestId().matches("[A-Za-z0-9_-]{16,64}")||c.storeId()==null||!c.storeId().matches("[1-9][0-9]{0,18}")||c.ownershipId()==null||!c.ownershipId().matches("[1-9][0-9]{0,18}")||c.workDayId()==null||c.location()==null||c.location().longitude()==null||c.location().latitude()==null||c.location().accuracyMeters()==null)throw new RequestValidationException("新门店拜访参数无效");
        var identity=context.resolveIdentity(actor,clock.instant());
        var day=days.findWorkDay(actor.tenantId(),identity.profile().id(),c.workDayId()).orElseThrow(()->new StateConflictException("工作日不存在"));if(!"ACTIVE".equals(day.status()))throw new StateConflictException("请先开始工作日");
        UUID capture=UUID.nameUUIDFromBytes((actor.tenantId()+":crm-capture:"+c.requestId()).getBytes(StandardCharsets.UTF_8));
        var active=visits.findActiveVisit(actor.tenantId(),identity.profile().id());
        if(active.isPresent()&&!capture.equals(active.get().id()))throw new StateConflictException("请先结束当前进行中的拜访");
        var v=crm.checkIn(c.storeId(),new CheckIn(c.requestId(),c.ownershipId(),c.location().longitude(),c.location().latitude(),"GCJ02",c.location().accuracyMeters(),capture.toString()));
        var snapshot=crm.context(c.storeId(),v.id());
        if(!Objects.equals(snapshot.employeeCode(),identity.profile().employeeCode())||!capture.toString().equals(snapshot.captureRef())||!"GCJ02".equals(snapshot.coordinateSystem()))throw new StateConflictException("采集人与CRM拜访不一致");
        if(!"IN_PROGRESS".equals(snapshot.status())&&store.link(actor.tenantId(),capture)==null)throw new StateConflictException("原签到已结束或取消，请重新发起拜访");
        try{store.create(actor.tenantId(),identity.profile().id(),c.workDayId(),capture,snapshot,c.location());}
        catch(StateConflictException conflict){crm.cancel(c.storeId(),v.id(),new CancelVisit(snapshot.revision(),"采集工作区创建冲突，本次签到取消"));throw conflict;}
        return new Created(v.id(),capture,c.storeId(),c.ownershipId());
    }
    public void requireEvidenceAccess(UUID tenant,UUID capture){
        var link=store.link(tenant,capture);if(link==null)return;
        if(!capture.toString().equals(crm.evidenceAccess(link.storeId(),link.visitId())))throw new AuthorizationDeniedException("crm:store:history-read");
    }
    public void acceptResult(CallerIdentity actor,UUID capture,String outcome,String name,String phone,String intention,String note){
        var link=store.link(actor.tenantId(),capture);if(link==null)return;
        var snapshot=crm.context(link.storeId(),link.visitId());
        String result=("CONTACTED".equals(outcome)?"已接触负责人":"未接触负责人："+outcome)+(name==null?"":"；联系人："+name)+(phone==null?"":"；电话："+phone)+(intention==null?"":"；意向："+intention)+(note==null?"":"；说明："+note);
        // 先持久化CRM提交受理，阻止边界时刻的回收；采集写入失败可原请求重试。
        crm.submit(link.storeId(),link.visitId(),new SubmitVisit(snapshot.revision(),result,List.of()));
    }
}

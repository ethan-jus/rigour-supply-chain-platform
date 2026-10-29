package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.application.port.out.StoreVisitStore;
import com.rigour.merchant.application.port.out.StoreLifecycleStore;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class StoreWorkService {
    private final StorePoolService pools;
    private final StoreVisitStore visits;
    private final StoreLifecycleStore cycles;
    private final StoreLifecycleService lifecycle;
    private final Clock clock;
    private final com.rigour.merchant.application.port.out.StoreVisitCaptureClient captures;
    public StoreWorkService(StorePoolService pools,StoreVisitStore visits,StoreLifecycleStore cycles,StoreLifecycleService lifecycle,Clock clock,com.rigour.merchant.application.port.out.StoreVisitCaptureClient captures){this.pools=pools;this.visits=visits;this.cycles=cycles;this.lifecycle=lifecycle;this.clock=clock;this.captures=captures;}
    private StoreScope scope(String store,String action){
        id(store);var s=pools.scopeForStore(action,store);
        if(s.access().employeeId()==null&&action.endsWith("write"))throw new AuthorizationDeniedException("store-employee-required");
        return s;
    }
    private void checkExpiry(StoreScope s,String store){
        var cycle=cycles.current(s.access().tenantId(),store);
        if(cycle!=null&&!clock.instant().isBefore(cycle.deadline()))lifecycle.refresh(s.access().tenantId(),store);
    }
    public Visit checkIn(String store,CheckIn c){
        if(c==null)throw new RequestValidationException("签到参数不能为空");id(c.ownershipId());request(c.requestId());
        if(c.longitude()==null||c.latitude()==null||c.longitude().abs().doubleValue()>180||c.latitude().abs().doubleValue()>90||!Set.of("GCJ02","WGS84").contains(c.coordinateSystem()==null?"":c.coordinateSystem())||c.accuracyMeters()!=null&&c.accuracyMeters().signum()<0)throw new RequestValidationException("签到定位无效");
        if(c.captureRef()!=null)try{java.util.UUID.fromString(c.captureRef());}catch(IllegalArgumentException e){throw new RequestValidationException("采集标识无效");}
        var s=scope(store,"crm:store:visit-write");checkExpiry(s,store);
        com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy policy;
        try{policy=captures.policy();}catch(RuntimeException error){throw new com.rigour.shared.core.exception.StateConflictException("销售拜访政策暂不可用，请稍后重试签到");}
        return visits.checkIn(s,store,c,policy,clock.instant());
    }
    public com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context capture(String store,String visit){id(visit);return visits.captureContext(scope(store,"crm:store:visit-write"),store,visit);}
    public String evidenceAccess(String store,String visit){id(visit);return visits.evidenceCapture(scope(store,"crm:store:history-read"),store,visit);}
    public Visit submit(String store,String visit,SubmitVisit c){
        id(visit);if(c==null)throw new RequestValidationException("提交参数不能为空");text(c.resultSummary(),2000,"拜访结果");
        if(c.photoRefs()==null||c.photoRefs().size()>10)throw new RequestValidationException("照片引用最多10项");
        for(String ref:c.photoRefs())if(ref==null||!ref.matches("[A-Za-z0-9_./-]{1,500}")||ref.contains("..")||ref.startsWith("/"))throw new RequestValidationException("请使用受控文件引用");
        var s=scope(store,"crm:store:visit-write");checkExpiry(s,store);return visits.submit(s,store,visit,c,clock.instant());
    }
    public Visit cancel(String store,String visit,CancelVisit c){id(visit);if(c==null)throw new RequestValidationException("取消参数不能为空");text(c.reason(),1000,"取消原因");return visits.cancel(scope(store,"crm:store:visit-write"),store,visit,c,clock.instant());}
    public Visit review(String store,String visit,ReviewVisit c){
        id(visit);if(c==null||!Set.of("EFFECTIVE","INVALID","VOID").contains(c.decision()==null?"":c.decision()))throw new RequestValidationException("核验结论无效");text(c.reason(),1000,"核验依据");
        return visits.review(scope(store,"crm:store:visit-review"),store,visit,c,clock.instant());
    }
    public List<Visit> visits(String store,String view){pools.detail(store,view);return visits.visits(scope(store,"crm:store:history-read"),store);}
    public OrderPermit authorize(String store,OrderAuthorization c){
        if(c==null)throw new RequestValidationException("订单授权参数不能为空");id(c.orderId());id(c.ownershipId());request(c.requestId());
        var s=scope(store,"crm:store:order-write");checkExpiry(s,store);return visits.authorize(s,store,c,clock.instant());
    }
    public RefreshResult refresh(String store){pools.detail(store,"ALL");var s=scope(store,"crm:store:manage");return lifecycle.refresh(s.access().tenantId(),store);}
    private static void id(String id){if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw new RequestValidationException("业务ID无效");}
    private static void request(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{16,64}"))throw new RequestValidationException("请求标识无效");}
    private static void text(String s,int limit,String name){if(s==null||s.isBlank()||s.length()>limit)throw new RequestValidationException(name+"不能为空或过长");}
}

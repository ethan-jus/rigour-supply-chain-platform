package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.Contact;
import com.rigour.merchant.application.port.out.WecomStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WecomService {
    private final StorePoolService pools; private final WecomStore store; private final Clock clock;
    public WecomService(StorePoolService pools,WecomStore store,Clock clock){this.pools=pools;this.store=store;this.clock=clock;}
    private StoreScope scope(String view){
        if(!Set.of("MINE","ALL").contains(Objects.toString(view,"")))bad("企微视图无效");
        var s=pools.scope("MINE".equals(view)?"crm:pool:private-read":"crm:store:manage");
        if("MINE".equals(view)&&s.access().employeeId()==null)bad("当前账号未关联员工");
        // Unlinked customers have no geographic owner. Company overview requires company-wide access.
        if("ALL".equals(view)&&(s.access().personalLimitEnabled()||s.access().grants().stream().noneMatch(g->g.national()&&"ALL".equals(g.ownerScope()))))
            throw new AuthorizationDeniedException("wecom-company-scope");
        return s;
    }
    public StoreSummary storeSummary(String id,String view){
        if(!id(id))bad("门店标识无效");
        String action=switch(Objects.toString(view,"")){case "MINE"->"crm:pool:private-read";case "TEAM"->"crm:pool:team-read";case "ALL"->"crm:store:manage";default->throw new AuthorizationDeniedException("store-contact-private-access");};
        return store.storeSummary(pools.scopeForStore(action,id),id);
    }
    public Summary summary(String view){
        var now=clock.instant();var from=now.atZone(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        return store.summary(scope(view),"MINE".equals(view),from,now);
    }
    public Page list(String view,boolean pending,String cursor,int size){
        if(size<1||size>100||cursor!=null&&!cursor.isBlank()&&!id(cursor))bad("企微分页参数无效");
        return store.list(scope(view),"MINE".equals(view),pending,cursor,size);
    }
    public Contact bind(String identity,Bind c){
        if(!id(identity)||c==null)bad("关联参数无效");
        if(c.requestId()==null||!c.requestId().matches("[A-Za-z0-9_-]{16,64}")||!id(c.storeId())||c.contactId()!=null&&!id(c.contactId()))bad("关联标识无效");
        text(c.role(),50);text(c.reason(),500);if(c.contactId()==null)text(c.name(),100);
        return store.bind(pools.scopeForStore("crm:store:visit-write",c.storeId()),identity,c,clock.instant());
    }
    public void accept(Snapshot c){
        if(c==null)bad("同步参数不能为空");
        var caller=AuthorizationContext.requireCurrent();
        if(!"SERVICE".equals(caller.principalScope())||caller.tenantId()==null||!caller.tenantId().toString().equals(c.tenantId()))throw new AuthorizationDeniedException("crm:wecom:source-write");
        AuthorizationContext.requirePermission("crm:wecom:source-write");
        text(c.identityScope(),192);text(c.employeeId(),64);text(c.employeeName(),100);
        if(c.sequence()<1||!c.complete()||c.observedAt()==null||c.observedAt().isAfter(clock.instant().plusSeconds(30))||c.customers()==null||c.customers().size()>10000)bad("需要完整且有效的官方关系快照");
        var seen=new HashSet<String>();
        for(var f:c.customers()){
            if(f==null)bad("客户资料不能为空");text(f.externalUserId(),192);text(f.name(),100);
            if(!seen.add(f.externalUserId())||f.addedAt()==null||f.addedAt().isAfter(c.observedAt())||!Set.of("NEW","BASELINE","TRANSFER","RETURN").contains(Objects.toString(f.origin(),"")))bad("企微关系事实无效");
        }
        store.accept(c,clock.instant());
    }
    private static boolean id(String v){return v!=null&&v.matches("[1-9][0-9]{0,18}");}
    private static void text(String v,int n){if(v==null||v.isBlank()||v.length()>n)bad("必填字段为空或过长");}
    private static void bad(String m){throw new RequestValidationException(m);}
}

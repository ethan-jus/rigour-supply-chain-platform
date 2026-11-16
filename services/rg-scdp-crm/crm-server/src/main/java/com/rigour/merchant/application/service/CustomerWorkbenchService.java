package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.CustomerWorkbenchModels.*;
import com.rigour.merchant.api.v1.model.StoreModels;
import com.rigour.merchant.application.port.out.CustomerWorkbenchStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CustomerWorkbenchService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rigour.shared.cache.QueryCache queryCache=com.rigour.shared.cache.QueryCache.disabled();
    private final StorePoolService pools;
    private final CustomerWorkbenchStore store;
    private final Clock clock;
    public CustomerWorkbenchService(StorePoolService pools,CustomerWorkbenchStore store,Clock clock) {this.pools=pools;this.store=store;this.clock=clock;}
    private StoreScope scope() {
        var s=pools.scope("crm:pool:private-read");
        if(s.access().employeeId()==null)throw new RequestValidationException("当前账号未关联员工");
        return s;
    }
    private static void validate(LocalDate from,LocalDate to,String keyword,String cursor,int size) {
        if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>366||size<1||size>100
            ||keyword!=null&&keyword.length()>100||cursor!=null&&!cursor.isBlank()&&!cursor.matches("[1-9][0-9]{0,18}"))
            throw new RequestValidationException("客户查询条件无效");
    }
    private static Instant start(LocalDate day){return day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();}
    private Instant end(LocalDate day){var end=start(day.plusDays(1));return end.isAfter(clock.instant())?clock.instant():end;}
    public PublicCount publicCount() {
        // Date independent and public-only: use the existing authorized, versioned count cache.
        return new PublicCount(pools.count(pools.scope("crm:pool:public-read"), query("PUBLIC",null,null)));
    }
    public Summary summary(LocalDate from,LocalDate to) {
        validate(from,to,null,null,1);var s=scope();
        StoreScope publicScope=null;
        try {publicScope=pools.scope("crm:pool:public-read");}catch(AuthorizationDeniedException denied){/* Independent public permission. */}
        var visiblePublic=publicScope;
        boolean orders=AuthorizationContext.hasPermission("order:read");
        // Resolve both current authorities before every cache read; never cache the permission decision.
        return queryCache.read(s.access().tenantId(),"customer-overview",java.util.Arrays.asList(
            s.access(),s.catalog().version(),s.departmentMembers(),
            publicScope==null?null:publicScope.access(),publicScope==null?null:publicScope.catalog().version(),
            publicScope==null?null:publicScope.departmentMembers(),orders,from,to),10,
            new tools.jackson.core.type.TypeReference<Summary>() {},()->loadSummary(s,visiblePublic,orders,from,to));
    }
    private Summary loadSummary(StoreScope s,StoreScope publicScope,boolean orders,LocalDate from,LocalDate to) {
        var begin=start(from);var until=end(to);
        Long purchased=null,newly=null,returning=null,unknown=null,cooperated=null;
        if(orders) {
            purchased=store.customerCount(s,"PURCHASING",begin,until);newly=store.customerCount(s,"NEW",begin,until);
            returning=store.customerCount(s,"RETURNING",begin,until);unknown=store.customerCount(s,"UNKNOWN_HISTORY",begin,until);
            cooperated=store.customerCount(s,"COOPERATED",begin,until);
        }
        return new Summary(purchased,newly,returning,unknown,store.newContactCount(s,begin,until),null,
            pools.count(s,query("MINE",null,null)),cooperated,
            publicScope==null?null:pools.count(publicScope,query("PUBLIC",null,null)),
            pools.count(s,query("MINE","DUE_SOON",null)),pools.count(s,query("MINE",null,"DORMANT")),
            store.contactPending(s),"LINKED_STORE_ORDERS",clock.instant());
    }
    private static StoreModels.Query query(String view,String protection,String activity) {
        return new StoreModels.Query(view,null,null,null,null,null,null,null,activity,null,null,1,null,null,null,null,protection);
    }
    public Page customers(String kind,LocalDate from,LocalDate to,String keyword,String relation,String cursor,int size) {
        validate(from,to,keyword,cursor,size);
        if(!Set.of("COOPERATED","PURCHASING","NEW","RETURNING","UNKNOWN_HISTORY","CONTACT_PENDING").contains(kind)
                ||relation!=null&&!relation.isBlank()&&!Set.of("MINE","PUBLIC","TRANSFERRED").contains(relation))
            throw new RequestValidationException("客户筛选无效");
        if(!"CONTACT_PENDING".equals(kind))AuthorizationContext.requirePermission("order:read");
        return store.customers(scope(),kind,start(from),end(to),keyword,relation,cursor,size);
    }
    public NearbyModels.Page<Item> nearby(String kind,LocalDate from,LocalDate to,String keyword,String relation,int size,NearbyModels.Query near) {
        validate(from,to,keyword,null,size);
        if(!"COOPERATED".equals(kind)||relation!=null&&!relation.isBlank()&&!Set.of("MINE","PUBLIC","TRANSFERRED").contains(relation))throw new RequestValidationException("客户筛选无效");
        AuthorizationContext.requirePermission("order:read");var scope=scope();
        var origin=pools.nearbyOrigin(scope,near);
        return queryCache.read(scope.access().tenantId(),"customer-nearby",java.util.Arrays.asList(scope.access(),scope.catalog().version(),scope.departmentMembers(),from,to,keyword,relation,near.nearCursor(),size,origin),10,
                new tools.jackson.core.type.TypeReference<>(){},()->store.nearby(scope,kind,start(from),end(to),keyword,relation,near.nearCursor(),size,origin));
    }
    public Contacts contacts(boolean newly,LocalDate from,LocalDate to,String keyword,String cursor,int size) {
        validate(from,to,keyword,cursor,size);return store.contacts(scope(),newly,start(from),end(to),keyword,cursor,size);
    }
    public Orders orders(String id,String cursor,int size) {
        if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw new RequestValidationException("门店ID无效");
        validate(LocalDate.now(clock),LocalDate.now(clock),null,cursor,size);AuthorizationContext.requirePermission("order:read");
        return store.orders(scope(),id,cursor,size);
    }
}

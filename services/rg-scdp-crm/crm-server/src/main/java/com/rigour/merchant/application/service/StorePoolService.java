package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StorePoolService {
    private final StoreReferenceClient references;
    private final CrmEmployeeClient employees;
    private final StorePoolStore store;
    public StorePoolService(StoreReferenceClient references, CrmEmployeeClient employees, StorePoolStore store) {
        this.references=references;this.employees=employees;this.store=store;
    }
    public static String action(String view) {
        return switch(view) {case "PUBLIC"->"crm:pool:public-read";case "MINE"->"crm:pool:private-read";case "TEAM"->"crm:pool:team-read";case "COOPERATED"->"crm:store:cooperation-read";case "ALL"->"crm:store:manage";case "LEADS"->"crm:lead:read";case "COVERAGE"->"crm:coverage:read";default->throw new RequestValidationException("门店视图无效");};
    }
    public StoreScope scope(String action) {
        var access=references.access(action); var catalog=references.divisions(); var members=new HashMap<String,List<String>>();
        for(var grant:access.grants()) if("DEPARTMENT".equals(grant.ownerScope()))
            for(String department:grant.departmentIds()) members.computeIfAbsent(department+":"+grant.descendants(),d->employees.departmentMembers(access.tenantId(),List.of(department),grant.descendants()));
        return new StoreScope(access,catalog,members);
    }
    public StoreScope scopeForStore(String action,String id) {
        var scope=scope(action);store.detail(scope,id);return scope;
    }
    public void initializePolicy(){store.initializePolicy(scope("crm:store:manage"));}
    public List<Event> events(String id,String view){id(id);listView(view);store.detail(scope(action(view)),id);return store.events(scope("crm:store:history-read"),id);}
    public void update(String id,Update c){
        id(id);request(c.requestId());required(c.name(),200,"门店名称");required(c.address(),500,"门店地址");required(c.reason(),500,"修改原因");
        if(c.publicPhone()!=null&&c.publicPhone().length()>50)throw new RequestValidationException("电话过长");
        if(c.storeTypeCode()!=null&&!c.storeTypeCode().isBlank()&&!c.storeTypeCode().matches("[A-Z][A-Z0-9_]{0,31}"))throw new RequestValidationException("门店类型编码无效");
        var access=scope("crm:store:manage");
        if(c.storeTypeCode()!=null&&!c.storeTypeCode().isBlank()&&!references.storeTypeCodes(access.access().tenantId()).contains(c.storeTypeCode()))throw new RequestValidationException("请选择字典中启用的门店类型");
        store.update(access,id,c);
    }
    public Context context(String view) {return scope(action(view)).context();}
    public Page list(Query query) {
        listView(query.view());validate(query);var scope=scope(action(query.view()));
        return store.list(scope,query,typeCodes(scope,query));
    }
    public MapResult map(Query query,MapWindow window) {
        listView(query.view());validate(query);validateWindow(window,false);
        var scope=scope(action(query.view()));
        return store.map(scope,query,typeCodes(scope,query),window);
    }
    public Page mapStores(Query query,MapWindow window,String coordinateSystem) {
        listView(query.view());validate(query);validateWindow(window,true);
        if(!Set.of("GCJ02","WGS84").contains(coordinateSystem))throw new RequestValidationException("坐标系无效");
        var scope=scope(action(query.view()));
        return store.mapStores(scope,query,typeCodes(scope,query),window,coordinateSystem);
    }
    private Set<String> typeCodes(StoreScope scope,Query query) {
        String selected=query.storeType();
        if(selected==null||selected.isBlank()||"__UNCLASSIFIED__".equals(selected))return Set.of();
        var items=references.storeTypes(scope.access().tenantId());
        Set<String> codes=new LinkedHashSet<>();codes.add(selected);
        // 停用项仍可查询历史门店；父级包含全部后代及指向这些项的历史别名。
        boolean changed;
        do {
            changed=false;
            for(var item:items)if(codes.contains(item.parentDictionaryItemCode())||codes.contains(item.canonicalItemCode()))
                changed|=codes.add(item.dictionaryItemCode());
        }while(changed);
        return codes;
    }
    private static void validateWindow(MapWindow window,boolean required) {
        var bounds=Arrays.asList(window.west(),window.south(),window.east(),window.north());
        long supplied=bounds.stream().filter(Objects::nonNull).count();
        if((required&&supplied!=4)||(supplied!=0&&supplied!=4))throw new RequestValidationException("地图范围需要完整的四个边界");
        if(supplied==4&&(bounds.stream().anyMatch(n->!Double.isFinite(n))
                ||window.west() < -180||window.east()>180||window.south() < -90||window.north()>90
                ||window.west()>window.east()||window.south()>window.north()))throw new RequestValidationException("地图范围无效");
        if(window.zoom()!=null&&(!Double.isFinite(window.zoom())||window.zoom()<2||window.zoom()>20))throw new RequestValidationException("地图缩放级别无效");
    }
    public Row detail(String id,String view) {id(id);listView(view);return store.detail(scope(action(view)),id);}
    public List<Ownership> ownerships(String id,String view) {id(id);listView(view);store.detail(scope(action(view)),id);return store.ownerships(scope("crm:store:history-read"),id);}
    public void claim(String id,Claim command) {
        id(id);request(command.requestId());if(command.revision()<1)throw new RequestValidationException("记录版本无效");
        var scope=scope("crm:pool:claim");employee(scope);store.claim(scope,id,command);
    }
    public void release(String id,Release command) {
        id(id);request(command.requestId());required(command.reason(),500,"退回原因");
        var scope=scope("crm:pool:release");employee(scope);store.release(scope,id,command);
    }
    public List<Lead> leads(boolean review) {return store.leads(scope(review?"crm:lead:review":"crm:lead:read"),review);}
    public Lead submit(LeadCommand c) {
        request(c.requestId());required(c.name(),200,"门店名称");required(c.address(),500,"门店地址");
        if(c.longitude()==null||c.latitude()==null||c.longitude().abs().doubleValue()>180||c.latitude().abs().doubleValue()>90)
            throw new RequestValidationException("请填写有效的GCJ02经纬度");
        if(c.publicPhone()!=null&&c.publicPhone().length()>50)throw new RequestValidationException("公开电话过长");
        var scope=scope("crm:lead:create");employee(scope);
        var node=scope.nodes().get(c.areaCode());
        if(node==null||!scope.permitsArea(c.areaCode()))throw new AuthorizationDeniedException("store-area");
        boolean leaf="PREFECTURE".equals(node.level())&&scope.catalog().nodes().stream().noneMatch(n->node.code().equals(n.parentCode()));
        if(!"COUNTY".equals(node.level())&&!leaf)throw new RequestValidationException("请选择区县或合法的无县级建制城市");
        return store.submit(scope,c);
    }
    public Lead review(String id,Review c) {id(id);request(c.requestId());required(c.reason(),1000,"审核说明");return store.review(scope("crm:lead:review"),id,c);}
    public Coverage coverage(Query q) {validate(q);var scope=scope("crm:coverage:read");return store.coverage(scope,q,typeCodes(scope,q));}
    private static void listView(String view){if(!Set.of("PUBLIC","MINE","TEAM","COOPERATED","ALL").contains(view))throw new RequestValidationException("门店列表视图无效");}
    private static void employee(StoreScope s) {if(s.access().employeeId()==null)throw new RequestValidationException("当前账号未关联有效员工，不能认领或提报门店");}
    private static void id(String s) {if(s==null||!s.matches("[1-9][0-9]{0,18}"))throw new RequestValidationException("门店或记录ID无效");}
    private static void request(String s) {if(s==null||!s.matches("[A-Za-z0-9_-]{16,64}"))throw new RequestValidationException("请求标识无效");}
    private static void required(String s,int max,String label) {if(s==null||s.isBlank()||s.length()>max)throw new RequestValidationException(label+"不能为空且长度不能超过"+max);}
    public static void validate(Query q) {
        action(q.view());if(q.size()<1||q.size()>100)throw new RequestValidationException("每页需要1至100条");
        if(q.keyword()!=null&&q.keyword().length()>100)throw new RequestValidationException("查询关键词过长");
        if(q.storeType()!=null&&!q.storeType().isBlank()&&!"__UNCLASSIFIED__".equals(q.storeType())&&!q.storeType().matches("[A-Z][A-Z0-9_]{0,31}"))throw new RequestValidationException("门店类型编码无效");
        for(String code:Arrays.asList(q.province(),q.city(),q.county()))if(code!=null&&!code.isBlank()&&!code.matches("[0-9]{6}"))throw new RequestValidationException("行政编码无效");
        if(q.cursor()!=null&&!q.cursor().isBlank())id(q.cursor());
        oneOf(q.region(),Set.of("NORTH","NORTHEAST","EAST","CENTRAL","SOUTH","SOUTHWEST","NORTHWEST"));
        oneOf(q.cooperation(),Set.of("NEVER","COOPERATED"));oneOf(q.tier(),Set.of("A","B","C","UNRATED"));
        oneOf(q.activity(),Set.of("UNKNOWN","NEVER_ORDERED","ACTIVE","DORMANT"));oneOf(q.potential(),Set.of("UNKNOWN","HIGH","MEDIUM","LOW"));
    }
    private static void oneOf(String value,Set<String> allowed) {if(value!=null&&!value.isBlank()&&!allowed.contains(value))throw new RequestValidationException("筛选编码无效");}
}

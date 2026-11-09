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
    private final StorePoiClient pois;
    public StorePoolService(StoreReferenceClient references, CrmEmployeeClient employees, StorePoolStore store, StorePoiClient pois) {
        this.references=references;this.employees=employees;this.store=store;this.pois=pois;
    }
    public static String action(String view) {
        return switch(view) {case "PUBLIC"->"crm:pool:public-read";case "MINE"->"crm:pool:private-read";case "TEAM"->"crm:pool:team-read";case "COOPERATED"->"crm:store:cooperation-read";case "ALL"->"crm:store:library-read";case "LEADS"->"crm:lead:read";case "COVERAGE"->"crm:coverage:read";default->throw new RequestValidationException("门店视图无效");};
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
    public Context maintenanceContext(String id){id(id);return scopeForStore("crm:store:manage",id).context();}
    public List<Event> events(String id,String view){id(id);listView(view);store.detail(scope(action(view)),id);return store.events(scope("crm:store:history-read"),id);}
    public void update(String id,Update c){
        id(id);request(c.requestId());required(c.name(),200,"门店名称");required(c.address(),500,"门店地址");required(c.reason(),500,"修改原因");
        if(c.publicPhone()!=null&&c.publicPhone().length()>50)throw new RequestValidationException("电话过长");
        if(c.storeTypeCode()!=null&&!c.storeTypeCode().isBlank()&&!c.storeTypeCode().matches("[A-Z][A-Z0-9_]{0,31}"))throw new RequestValidationException("门店类型编码无效");
        var access=scope("crm:store:manage");
        store.detail(access,id);
        if(c.businessStatus()!=null)validateBusinessStatus(access,c.businessStatus().status(),c.businessStatus().reason());
        if(c.location()!=null) {
            maintenanceArea(access,c.location().areaCode());
            var l=c.location();
            if((l.longitude()==null)!=(l.latitude()==null)||(l.longitude()==null&&l.coordinateSystem()!=null)||l.longitude()!=null&&
                    (l.longitude().abs().doubleValue()>180||l.latitude().abs().doubleValue()>90||
                    !Set.of("GCJ02","WGS84").contains(Objects.toString(l.coordinateSystem(),""))))
                throw new RequestValidationException("请填写成对的有效经纬度并选择坐标系");
        }
        if(c.amapPoiId()!=null) {
            if(!c.amapPoiId().matches("[A-Za-z0-9]{1,64}"))throw new RequestValidationException("POI 编码无效");
            var poi=pois.detail(access.access().tenantId(),c.amapPoiId());
            if(poi==null||!c.amapPoiId().equals(poi.id())||!usablePoi(access,poi))
                throw new RequestValidationException("该 POI 无法核验或不在可维护地区范围");
            var l=c.location();
            if(l==null||!poi.areaCode().equals(l.areaCode())||!"GCJ02".equals(l.coordinateSystem())||
                    l.longitude()==null||l.latitude()==null||poi.longitude().compareTo(l.longitude())!=0||poi.latitude().compareTo(l.latitude())!=0)
                throw new RequestValidationException("所选 POI 的地区或坐标已变化，请重新查询并选择");
        }
        if(c.storeTypeCode()!=null&&!c.storeTypeCode().isBlank()&&!references.storeTypeCodes(access.access().tenantId()).contains(c.storeTypeCode()))throw new RequestValidationException("请选择字典中启用的门店类型");
        store.update(access,id,c);
    }
    public List<Poi> poiCandidates(String id,String keyword,String areaCode) {
        id(id);required(keyword,200,"门店名称");
        var scope=scopeForStore("crm:store:manage",id);
        maintenanceArea(scope,areaCode);
        return pois.search(scope.access().tenantId(),keyword.strip(),areaCode).stream()
                .filter(p->areaCode.equals(p.areaCode())&&usablePoi(scope,p)).toList();
    }
    private static boolean usablePoi(StoreScope scope,Poi poi) {
        return poi.areaCode()!=null&&scope.permitsArea(poi.areaCode())&&poi.longitude()!=null&&poi.latitude()!=null
                &&poi.longitude().abs().doubleValue()<=180&&poi.latitude().abs().doubleValue()<=90;
    }
    private static void maintenanceArea(StoreScope scope,String code) {
        var node=scope.nodes().get(code);
        if(node==null||!scope.permitsArea(code))throw new AuthorizationDeniedException("store-area");
        boolean leaf="PREFECTURE".equals(node.level())&&scope.catalog().nodes().stream().noneMatch(n->code.equals(n.parentCode()));
        if(!"COUNTY".equals(node.level())&&!leaf)throw new RequestValidationException("请选择区县或合法的无县级建制城市");
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
        id(id);request(command.requestId());required(command.reason(),500,"释放原因");
        var scope=scope("crm:pool:release");employee(scope);store.release(scope,id,command);
    }
    public LeadPage leads(boolean review,int page,int size,String keyword,String status) {
        if(page<1||size<1||size>100)throw new RequestValidationException("页码从1开始，每页需要1至100条");
        if(keyword!=null&&keyword.length()>100)throw new RequestValidationException("查询关键词不能超过100个字符");
        oneOf(status,Set.of("PENDING_REVIEW","APPROVED","REJECTED"));
        if(review&&status!=null&&!status.isBlank()&&!"PENDING_REVIEW".equals(status))
            throw new RequestValidationException("待我审核仅支持待审核状态");
        return store.leads(scope(review?"crm:lead:review":"crm:lead:read"),review,page,size,
                keyword==null?null:keyword.strip(),status==null||status.isBlank()?null:status);
    }
    public Context intakeContext(){return scope("crm:lead:create").context();}
    private void searchArea(StoreScope scope,String code) {
        var node=scope.nodes().get(code);
        if(node==null||scope.context().areas().stream().noneMatch(a->code.equals(a.code())))throw new AuthorizationDeniedException("store-area");
    }
    public List<StoreMatch> lookup(String keyword,String areaCode) {
        required(keyword,100,"门店名称");var access=scope("crm:lead:create");searchArea(access,areaCode);
        return store.lookup(access,keyword.strip(),areaCode);
    }
    public List<StoreMatch> reviewLookup(String keyword,String areaCode) {
        required(keyword,100,"门店名称");var access=scope("crm:lead:review");searchArea(access,areaCode);
        return store.lookup(access,keyword.strip(),areaCode);
    }
    public List<Poi> intakeCandidates(String keyword,String areaCode) {
        required(keyword,100,"门店名称");var access=scope("crm:lead:create");searchArea(access,areaCode);
        return pois.search(access.access().tenantId(),keyword.strip(),areaCode).stream()
                .filter(p->usablePoi(access,p)&&access.ancestry(p.areaCode()).stream().anyMatch(a->areaCode.equals(a.code())))
                .limit(20).toList();
    }
    public Admission admit(AmapAdmission c) {
        request(c.requestId());
        if(c.poiId()==null||!c.poiId().matches("[A-Za-z0-9]{1,64}"))throw new RequestValidationException("POI 编码无效");
        var access=scope("crm:lead:create");
        // Only the provider detail is authoritative; the client submits no writable POI fields.
        var poi=pois.detail(access.access().tenantId(),c.poiId());
        if(poi==null||!c.poiId().equals(poi.id())||!usablePoi(access,poi))throw new RequestValidationException("POI 无法核验或不在新增权限地区内");
        maintenanceArea(access,poi.areaCode());required(poi.name(),200,"门店名称");required(poi.address(),500,"门店地址");
        var codes=references.storeTypeCodes(access.access().tenantId());
        String type=poi.typeCode()==null?null:Arrays.stream(poi.typeCode().split("[;|]"))
                .map(String::strip).map(c1->"POI_"+c1).filter(codes::contains).findFirst().orElse(null);
        if(type==null)throw new RequestValidationException("高德分类暂不能映射门店字典，请手动提报核验");
        if(poi.publicPhone()!=null&&poi.publicPhone().length()>50)throw new RequestValidationException("高德电话需整理，请手动提报核验");
        return store.admit(access,c,poi,type);
    }
    private StoreScope validateLead(LeadCommand c) {
        request(c.requestId());required(c.name(),200,"门店名称");required(c.address(),500,"门店地址");
        if(c.longitude()==null||c.latitude()==null||c.longitude().abs().doubleValue()>180||c.latitude().abs().doubleValue()>90
                ||!Set.of("GCJ02","WGS84").contains(Objects.toString(c.coordinateSystem(),"")))
            throw new RequestValidationException("请定位门店并选择正确的坐标系");
        if(c.publicPhone()!=null&&c.publicPhone().length()>50)throw new RequestValidationException("公开电话过长");
        var access=scope("crm:lead:create");maintenanceArea(access,c.areaCode());
        if(c.storeTypeCode()==null||!references.storeTypeCodes(access.access().tenantId()).contains(c.storeTypeCode()))throw new RequestValidationException("请选择启用的门店分类");
        return access;
    }
    public Lead submit(LeadCommand c) {return store.submit(validateLead(c),c);}
    public Lead resubmit(String id,Resubmit c) {
        id(id);if(c.revision()<1||c.proposal()==null)throw new RequestValidationException("提报版本无效");
        return store.resubmit(validateLead(c.proposal()),id,c);
    }
    public Lead review(String id,Review c) {
        id(id);request(c.requestId());required(c.reason(),1000,"审核说明");
        if(c.existingStoreId()!=null)id(c.existingStoreId());
        return store.review(scope("crm:lead:review"),id,c);
    }
    public void businessStatus(String id,BusinessStatusChange c) {
        id(id);request(c.requestId());
        if(c.revision()<1)throw new RequestValidationException("记录版本无效");
        var access=scope(c.management()?"crm:store:manage":"crm:store:status-write");
        validateBusinessStatus(access,c.status(),c.reason());
        store.businessStatus(access,id,c);
    }
    private void validateBusinessStatus(StoreScope scope,String status,String reason) {
        required(reason,500,"经营状态核实依据");
        if(!Set.of("UNKNOWN","OPEN","TEMP_CLOSED","CLOSED").contains(Objects.toString(status,"")))throw new RequestValidationException("经营状态无效");
        if(!references.businessStatusCodes(scope.access().tenantId()).contains(status))throw new RequestValidationException("请选择字典中启用的经营状态");
    }
    public Coverage coverage(Query q) {validate(q);var scope=scope("crm:coverage:read");return store.coverage(scope,q,typeCodes(scope,q));}
    private static void listView(String view){if(!Set.of("PUBLIC","MINE","TEAM","COOPERATED","ALL").contains(view))throw new RequestValidationException("门店列表视图无效");}
    private static void employee(StoreScope s) {if(s.access().employeeId()==null)throw new RequestValidationException("当前账号未关联有效员工，不能进行销售作业");}
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
        oneOf(q.pool(),Set.of("PUBLIC","PRIVATE"));
        oneOf(q.businessStatus(),Set.of("UNKNOWN","OPEN","TEMP_CLOSED","CLOSED"));
        oneOf(q.recordStatus(),Set.of("ACTIVE","SUSPENDED","CLOSED","MERGED"));
        if(q.recordStatus()!=null&&!q.recordStatus().isBlank()&&!"ALL".equals(q.view()))
            throw new RequestValidationException("档案状态筛选仅供商家库管理使用");
        oneOf(q.phase(),Set.of("FIRST_VISIT","FOLLOW_UP","COOPERATING"));
        oneOf(q.protection(),Set.of("DUE_SOON","OVERDUE","HELD"));
    }
    private static void oneOf(String value,Set<String> allowed) {if(value!=null&&!value.isBlank()&&!allowed.contains(value))throw new RequestValidationException("筛选编码无效");}
}

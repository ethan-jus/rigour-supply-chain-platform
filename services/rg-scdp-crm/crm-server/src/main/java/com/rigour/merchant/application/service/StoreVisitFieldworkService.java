package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.shared.core.exception.RequestValidationException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StoreVisitFieldworkService {
    private static final List<String> DICTIONARIES=List.of("CRM_STORE_BUSINESS_STATUS","STORE_BUSINESS_TYPE","BUSINESS_LINE",
            "CUSTOMER_INTENTION_LEVEL","CUSTOMER_COOP_STATUS","VISIT_CONTACT_OUTCOME","VISIT_NO_CONTACT_REASON","VISIT_NEXT_ACTION","STORE_CONTACT_ROLE");
    private final StorePoolService pools; private final StoreVisitStore visits; private final StoreContactStore contacts;
    private final StoreVisitFieldworkStore store; private final StoreReferenceClient reference;
    public StoreVisitFieldworkService(StorePoolService pools,StoreVisitStore visits,StoreContactStore contacts,StoreVisitFieldworkStore store,StoreReferenceClient reference){this.pools=pools;this.visits=visits;this.contacts=contacts;this.store=store;this.reference=reference;}
    private Map<String,List<Option>> options(String tenant){
        var result=new LinkedHashMap<String,List<Option>>();
        for(var dictionary:reference.dictionaries(tenant,DICTIONARIES)) { String code=dictionary.dictionary().dictionaryCode(); result.put(code,dictionary.items().stream()
                .filter(i->!"CUSTOMER_INTENTION_LEVEL".equals(code)||Set.of("HIGH","MEDIUM","LOW","NONE").contains(i.dictionaryItemCode()))
                .filter(i->!"CUSTOMER_COOP_STATUS".equals(code)||"COOPERATED".equals(i.dictionaryItemCode()))
                .map(i->new Option(i.dictionaryItemCode(),i.dictionaryItemName())).toList()); }
        return result;
    }
    public Form form(String id,String visit){
        var scope=pools.scopeForStore("crm:store:visit-write",id);
        var context=visits.captureContext(scope,id,visit);
        var row=store.current(scope,id);String tenant=scope.access().tenantId();
        return new Form(row.revision(),row.businessStatus(),store.businessType(tenant,id),store.businesses(tenant,id),options(tenant),store.syncStatus(tenant,visit),store.submitted(tenant,visit),context.status());
    }
    public Profile profile(String id,String view){
        pools.detail(id,view);var scope=pools.scopeForStore("crm:store:history-read",id);String tenant=scope.access().tenantId();
        var dictionaries=options(tenant);String type=store.businessType(tenant,id);
        return new Profile(type,store.businesses(tenant,id),dictionaries);
    }
    public Accepted validate(String id,String visit,Draft d){
        if(d==null)bad("请填写拜访信息");
        var scope=pools.scopeForStore("crm:store:visit-write",id);visits.captureContext(scope,id,visit);
        var options=options(scope.access().tenantId());var labels=new LinkedHashMap<String,String>();
        select(options,labels,"CRM_STORE_BUSINESS_STATUS",d.businessStatus(),"请选择营业状态");
        select(options,labels,"STORE_BUSINESS_TYPE",d.businessTypeCode(),"请选择经营类型");
        select(options,labels,"VISIT_CONTACT_OUTCOME",d.contactOutcome(),"请选择沟通结果");
        limit(d.businessStatusReason(),500,"营业状态依据");limit(d.noContactDetail(),500,"未接触说明");
        if(Set.of("TEMP_CLOSED","CLOSED").contains(d.businessStatus())&&blank(d.businessStatusReason()))bad("请填写停业依据");
        if(d.contactIds().size()>20||new HashSet<>(d.contactIds()).size()!=d.contactIds().size())bad("接待人不能重复，最多20位");
        if(d.businesses().size()>30)bad("业务进展过多");
        boolean contacted="CONTACTED".equals(d.contactOutcome());
        var selected=contacts.list(scope,id).stream().filter(c->d.contactIds().contains(c.id())&&"ACTIVE".equals(c.status())).toList();
        if(contacted){
            if(selected.isEmpty())bad("请选择接待人");
            if(selected.size()!=d.contactIds().size())bad("接待人已变更，请重新选择");
            if(d.noIntention()&&!d.businesses().isEmpty())bad("暂无合作意向时请清除业务进展");
            if(!d.noIntention()&&d.businesses().isEmpty())bad("请填写业务进展，或选择暂无合作意向");
            var lines=new HashSet<String>();
            for(var b:d.businesses()){
                select(options,labels,"BUSINESS_LINE",b.businessLineCode(),"请选择有效业务");
                if(!lines.add(b.businessLineCode()))bad("同一业务不能重复填写");
                if(!blank(b.cooperationStatus())){
                    select(options,labels,"CUSTOMER_COOP_STATUS",b.cooperationStatus(),"请选择合作进展");
                    if(!blank(b.intentionLevel()))bad("已合作业务无需填写意向等级");
                }else select(options,labels,"CUSTOMER_INTENTION_LEVEL",b.intentionLevel(),"请选择业务意向等级");
            }
        }else{
            if(!d.contactIds().isEmpty()||!d.businesses().isEmpty()||d.noIntention())bad("未接触时不能提交接待人或合作进展");
            select(options,labels,"VISIT_NO_CONTACT_REASON",d.noContactReason(),"请选择未接触原因");
            if("OTHER".equals(d.noContactReason())&&blank(d.noContactDetail()))bad("请补充未接触原因");
        }
        if(!blank(d.nextAction())||d.nextContactDate()!=null){
            select(options,labels,"VISIT_NEXT_ACTION",d.nextAction(),"请选择下次跟进行动");
            if(d.nextContactDate()==null)bad("请选择下次跟进日期");
        }
        if(!"UNKNOWN".equals(d.businessStatus()))pools.scopeForStore("crm:store:status-write",id);
        store.validateCurrent(scope,id,visit,d);
        return new Accepted(d,scope.access().userId(),scope.access().employeeId(),labels,contacted?selected:List.of());
    }
    private static void select(Map<String,List<Option>> options,Map<String,String> labels,String dictionary,String code,String message){
        var item=options.getOrDefault(dictionary,List.of()).stream().filter(i->i.code().equals(code)).findFirst();
        if(item.isEmpty())bad(message);labels.put(dictionary+":"+code,item.orElseThrow().name());
    }
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static void limit(String s,int max,String name){if(s!=null&&s.length()>max)bad(name+"不能超过"+max+"字");}
    private static void bad(String message){throw new RequestValidationException(message);}
}

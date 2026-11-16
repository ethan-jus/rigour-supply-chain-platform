package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.port.out.StoreContactStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StoreContactService {
    private final StorePoolService pools;
    private final StoreContactStore contacts;
    private final Clock clock;
    public StoreContactService(StorePoolService pools,StoreContactStore contacts,Clock clock){this.pools=pools;this.contacts=contacts;this.clock=clock;}
    private StoreScope scope(String store,String view,boolean write){
        id(store);
        String action=switch(Objects.toString(view,"")){
            case "MINE" -> write?"crm:store:visit-write":"crm:pool:private-read";
            case "TEAM" -> write?"crm:store:manage":"crm:pool:team-read";
            case "ALL" -> "crm:store:manage";
            default -> throw new AuthorizationDeniedException("store-contact-private-access");
        };
        return pools.scopeForStore(action,store);
    }
    public List<Contact> list(String store,String view){return contacts.list(scope(store,view,false),store);}
    public Contact create(String store,String view,Create c){
        if(c==null)bad("联系人参数不能为空");request(c.requestId());text(c.name(),100,"姓名或称呼");text(c.role(),50,"门店角色");
        String phone=phone(c.phone());if(c.wechatHint()!=null&&c.wechatHint().length()>200)bad("微信线索不能超过200字");
        return contacts.create(scope(store,view,true),store,new Create(c.requestId(),c.name().strip(),c.role().strip(),c.primary(),phone,trim(c.wechatHint())),clock.instant());
    }
    public Contact update(String store,String contact,String view,Update c){
        id(contact);if(c==null)bad("联系人参数不能为空");request(c.requestId());revision(c.revision());revision(c.personRevision());
        text(c.name(),100,"姓名或称呼");text(c.role(),50,"门店角色");text(c.reason(),500,"变更原因");
        if(!Set.of("ACTIVE","LEFT").contains(Objects.toString(c.status(),""))||c.primary()&&!"ACTIVE".equals(c.status()))bad("联系人关系状态无效");
        return contacts.update(scope(store,view,true),store,contact,new Update(c.requestId(),c.revision(),c.personRevision(),c.name().strip(),c.role().strip(),c.primary(),c.status(),c.reason().strip()),clock.instant());
    }
    public Contact link(String store,String view,Link c){
        if(c==null)bad("关联参数不能为空");request(c.requestId());id(c.sourceStoreId());id(c.contactId());text(c.role(),50,"门店角色");text(c.reason(),500,"关联依据");
        var access=scope(store,view,true);scope(c.sourceStoreId(),view,true);
        return contacts.link(access,store,c,clock.instant());
    }
    public Contact addMethod(String store,String contact,String view,AddMethod c){
        id(contact);if(c==null)bad("联系方式不能为空");request(c.requestId());text(c.reason(),500,"采集说明");
        if(!Set.of("PHONE","WECHAT_HINT").contains(Objects.toString(c.type(),"")))bad("仅可手工录入电话或微信线索，企微建联须官方同步确认");
        text(c.value(),200,"联系方式");String value="PHONE".equals(c.type())?phone(c.value()):c.value().strip();
        return contacts.addMethod(scope(store,view,true),store,contact,new AddMethod(c.requestId(),c.type(),value,c.reason()),clock.instant());
    }
    public Contact verifyMethod(String store,String contact,String method,String view,VerifyMethod c){
        id(contact);id(method);if(c==null)bad("核验参数不能为空");request(c.requestId());revision(c.revision());text(c.reason(),500,"核验依据");
        if(!Set.of("VERIFIED","INVALID","PENDING").contains(Objects.toString(c.status(),"")))bad("核验状态无效");
        if("VERIFIED".equals(c.status())&&!Set.of("ON_SITE","PHONE_CALL").contains(Objects.toString(c.verificationMethod(),"")))bad("请选择现场确认或拨通核验");
        return contacts.verifyMethod(scope(store,view,true),store,contact,method,c,clock.instant());
    }
    public List<Change> changes(String store,String contact,String view){id(contact);return contacts.changes(scope(store,view,false),store,contact);}
    public List<Contact> reception(String store,String visit,String view){id(visit);return contacts.reception(scope(store,view,false),store,visit);}
    public List<Contact> reception(String store,String visit,Reception c){
        id(visit);if(c==null)bad("接待人参数不能为空");request(c.requestId());
        if(c.contactIds()==null||c.contactIds().size()>20||new HashSet<>(c.contactIds()).size()!=c.contactIds().size())bad("请选择最多20位不重复的接待联系人");
        c.contactIds().forEach(StoreContactService::id);
        return contacts.reception(scope(store,"MINE",true),store,visit,c,clock.instant());
    }
    public static String phone(String value){
        if(value==null)bad("请提供格式正确的电话；手填微信昵称不算企微建联");
        String normalized=value.strip().replaceAll("[\\s()（）-]","");
        if(!normalized.matches("(?:\\+?86)?1[3-9][0-9]{9}|(?:\\+?86)?0[1-9][0-9]{8,10}(?:#[0-9]{1,6})?|[48]00[0-9]{7}"))bad("请输入有效的手机或含区号的座机号码");
        return normalized;
    }
    private static String trim(String s){return s==null||s.isBlank()?null:s.strip();}
    private static void id(String s){if(s==null||!s.matches("[1-9][0-9]{0,18}"))bad("业务ID无效");}
    private static void request(String s){if(s==null||!s.matches("[A-Za-z0-9_-]{16,64}"))bad("请求标识无效");}
    private static void revision(long n){if(n<1)bad("记录版本无效");}
    private static void text(String s,int max,String name){if(s==null||s.isBlank()||s.length()>max)bad(name+"不能为空或过长");}
    private static void bad(String message){throw new RequestValidationException(message);}
}

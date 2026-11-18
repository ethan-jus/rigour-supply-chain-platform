package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.port.out.StoreContactStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Repository
public class JdbcStoreContactStore implements StoreContactStore {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rigour.shared.cache.QueryCache queryCache=com.rigour.shared.cache.QueryCache.disabled();
    private final JdbcTemplate jdbc;
    private final JdbcStorePoolStore pools;
    private final ObjectMapper json=new ObjectMapper();
    public JdbcStoreContactStore(JdbcTemplate jdbc,JdbcStorePoolStore pools){this.jdbc=jdbc;this.pools=pools;}
    private String tenant(StoreScope s){return s.access().tenantId();}
    void access(StoreScope s,String store,boolean write){
        var row=pools.detail(s,store);
        boolean manager="crm:store:manage".equals(s.access().action());
        if(!manager&&!"PRIVATE".equals(row.poolType()))throw new AuthorizationDeniedException("store-contact-private-access");
        if(write&&!manager&&(!Objects.equals(row.ownerEmployeeId(),s.access().employeeId())||s.access().employeeId()==null))throw new AuthorizationDeniedException("store-current-owner");
        if(write&&!manager&&row.deadlineAt()!=null&&!Instant.now().isBefore(row.deadlineAt()))throw new StateConflictException("当前归属已到期，请先刷新归属");
    }
    private void lock(StoreScope s,String store){
        jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",tenant(s),store);
        access(s,store,true);
    }
    private void lockPerson(StoreScope s,String person){jdbc.queryForList("SELECT id FROM crm_contact_person WHERE tenant_id=? AND id=? FOR UPDATE",tenant(s),person);}
    @Override public List<Contact> list(StoreScope s,String store){access(s,store,false);return rows(s,store);}
    private List<Contact> rows(StoreScope s,String store){
        return jdbc.query("SELECT p.id,p.name,p.revision person_revision,r.role_name,r.is_primary,r.status_code,r.revision FROM crm_store_contact r JOIN crm_contact_person p ON p.tenant_id=r.tenant_id AND p.id=r.contact_id WHERE r.tenant_id=? AND r.store_id=? ORDER BY r.status_code,r.is_primary DESC,p.id",
            (r,n)->new Contact(r.getString("id"),r.getString("name"),r.getLong("person_revision"),r.getString("role_name"),r.getBoolean("is_primary"),r.getString("status_code"),r.getLong("revision"),methods(s,r.getString("id")),wecom(s,r.getString("id"))),tenant(s),store);
    }
    private List<WecomRelation> wecom(StoreScope s,String person){
        return jdbc.query("SELECT DISTINCT r.employee_id,r.employee_name,r.added_at FROM crm_wecom_identity i JOIN crm_wecom_relation r ON r.tenant_id=i.tenant_id AND r.identity_id=i.id WHERE i.tenant_id=? AND i.contact_id=? AND r.active=1 ORDER BY r.added_at",(r,n)->new WecomRelation(r.getString("employee_id"),r.getString("employee_name"),"ACTIVE",r.getTimestamp("added_at").toInstant()),tenant(s),person);
    }
    // Called only after the caller's official identity relation and target-store write access are locked and verified.
    Contact linkWecom(StoreScope s,String store,String person,String role,String reason,String request,Instant now){
        lockPerson(s,person);var before=rows(s,store);var old=before.stream().filter(p->p.id().equals(person)).findFirst();
        if(old.isPresent()&&"ACTIVE".equals(old.get().status()))return old.get();
        if(old.isPresent())jdbc.update("UPDATE crm_store_contact SET status_code='ACTIVE',role_name=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND store_id=? AND contact_id=?",role,Timestamp.from(now),s.access().userId(),tenant(s),store,person);
        else relation(s,store,person,role,false,now);
        var result=contact(s,store,person);audit(s,store,person,"LINK_WECOM",request,hash("LINK_WECOM",store,person,role,reason),reason,before,result,now);return result;
    }
    private List<Method> methods(StoreScope s,String person){
        return jdbc.query("SELECT * FROM crm_contact_method WHERE tenant_id=? AND contact_id=? ORDER BY id",(r,n)->new Method(r.getString("id"),r.getString("method_type"),r.getString("method_value"),r.getString("status_code"),r.getString("verification_method"),r.getTimestamp("verified_at")==null?null:r.getTimestamp("verified_at").toInstant(),r.getString("verified_by"),r.getLong("revision")),tenant(s),person);
    }
    private Contact contact(StoreScope s,String store,String person){return rows(s,store).stream().filter(c->c.id().equals(person)).findFirst().orElseThrow(()->new StateConflictException("该联系人未关联当前门店"));}
    private void active(Contact c){if(!"ACTIVE".equals(c.status()))throw new StateConflictException("联系人已离职或关系失效，请先核实门店关系");}
    private void primary(StoreScope s,String store,boolean primary,Instant now){
        if(primary)jdbc.update("UPDATE crm_store_contact SET is_primary=false,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND store_id=? AND is_primary=true",Timestamp.from(now),s.access().userId(),tenant(s),store);
    }
    private void relation(StoreScope s,String store,String person,String role,boolean primary,Instant now){
        primary(s,store,primary,now);
        jdbc.update("INSERT INTO crm_store_contact(tenant_id,store_id,contact_id,role_name,is_primary,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",tenant(s),store,person,role,primary,Timestamp.from(now),Timestamp.from(now),s.access().userId(),s.access().userId());
    }
    private void method(StoreScope s,String person,String type,String value,Instant now){
        jdbc.update("INSERT INTO crm_contact_method(id,tenant_id,contact_id,method_type,method_value,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",IdWorker.getId(),tenant(s),person,type,value,Timestamp.from(now),Timestamp.from(now),s.access().userId(),s.access().userId());
    }
    @Override @Transactional public Contact create(StoreScope s,String store,Create c,Instant now){
        lock(s,store);String key=hash("CREATE",store,c);var replay=replay(s,store,c.requestId(),key);
        if(replay!=null)return json.readValue(replay,Contact.class);
        var before=rows(s,store);String id=Long.toString(IdWorker.getId());
        jdbc.update("INSERT INTO crm_contact_person(id,tenant_id,name,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",id,tenant(s),c.name(),Timestamp.from(now),Timestamp.from(now),s.access().userId(),s.access().userId());
        relation(s,store,id,c.role(),c.primary(),now);if(c.phone()!=null)method(s,id,"PHONE",c.phone(),now);
        if(c.wechatHint()!=null&&!c.wechatHint().isBlank())method(s,id,"WECHAT_HINT",c.wechatHint(),now);
        var result=contact(s,store,id);audit(s,store,id,"CREATE",c.requestId(),key,"手工采集，联系方式待核实",before,result,now);return result;
    }
    @Override @Transactional public Contact update(StoreScope s,String store,String person,Update c,Instant now){
        lock(s,store);lockPerson(s,person);String key=hash("UPDATE",store,person,c);var replay=replay(s,store,c.requestId(),key);
        if(replay!=null)return json.readValue(replay,Contact.class);
        var before=rows(s,store);var old=contact(s,store,person);
        if(c.revision()!=old.revision()||c.personRevision()!=old.personRevision())throw new StateConflictException("联系人已变化，请刷新后再修改");
        if(!Objects.equals(c.name(),old.name()))jdbc.update("UPDATE crm_contact_person SET name=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND id=?",c.name(),Timestamp.from(now),s.access().userId(),tenant(s),person);
        if(c.primary()&&!old.primary())primary(s,store,true,now);
        jdbc.update("UPDATE crm_store_contact SET role_name=?,is_primary=?,status_code=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND store_id=? AND contact_id=?",c.role(),c.primary(),c.status(),Timestamp.from(now),s.access().userId(),tenant(s),store,person);
        var result=contact(s,store,person);audit(s,store,person,"UPDATE",c.requestId(),key,c.reason(),before,result,now);return result;
    }
    @Override @Transactional public Contact link(StoreScope s,String store,Link c,Instant now){
        // Lock both stores in stable order; linking never grants access to another store.
        new TreeSet<>(List.of(store,c.sourceStoreId())).forEach(id->lock(s,id));lockPerson(s,c.contactId());
        String key=hash("LINK",store,c);var replay=replay(s,store,c.requestId(),key);if(replay!=null)return json.readValue(replay,Contact.class);
        active(contact(s,c.sourceStoreId(),c.contactId()));
        if(rows(s,store).stream().anyMatch(p->p.id().equals(c.contactId())))throw new StateConflictException("该联系人已关联当前门店，请直接维护现有关系");
        var before=rows(s,store);relation(s,store,c.contactId(),c.role(),c.primary(),now);
        var result=contact(s,store,c.contactId());audit(s,store,c.contactId(),"LINK",c.requestId(),key,c.reason(),before,result,now);return result;
    }
    @Override @Transactional public Contact addMethod(StoreScope s,String store,String person,AddMethod c,Instant now){
        lock(s,store);lockPerson(s,person);String key=hash("ADD_METHOD",store,person,c);var replay=replay(s,store,c.requestId(),key);if(replay!=null)return json.readValue(replay,Contact.class);
        var before=contact(s,store,person);active(before);
        if(before.methods().stream().anyMatch(m->m.type().equals(c.type())&&m.value().equals(c.value())))throw new StateConflictException("该联系方式已存在，请核验或更新其状态");
        method(s,person,c.type(),c.value(),now);
        var result=contact(s,store,person);audit(s,store,person,"ADD_METHOD",c.requestId(),key,c.reason(),before,result,now);return result;
    }
    @Override @Transactional public Contact verifyMethod(StoreScope s,String store,String person,String method,VerifyMethod c,Instant now){
        lock(s,store);lockPerson(s,person);String key=hash("VERIFY_METHOD",store,person,method,c);var replay=replay(s,store,c.requestId(),key);if(replay!=null)return json.readValue(replay,Contact.class);
        var before=contact(s,store,person);active(before);
        var old=before.methods().stream().filter(m->m.id().equals(method)).findFirst().orElseThrow(()->new StateConflictException("联系方式不存在"));
        if(old.revision()!=c.revision())throw new StateConflictException("联系方式已变化，请刷新后再核验");
        if(!"PHONE".equals(old.type())&&"VERIFIED".equals(c.status()))throw new StateConflictException("手填微信线索不能标记为已建联，须由官方同步确认");
        jdbc.update("UPDATE crm_contact_method SET status_code=?,verification_method=?,verified_at=?,verified_by=?,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND contact_id=? AND id=?",c.status(),"VERIFIED".equals(c.status())?c.verificationMethod():null,"VERIFIED".equals(c.status())?Timestamp.from(now):null,"VERIFIED".equals(c.status())?s.access().userId():null,Timestamp.from(now),s.access().userId(),tenant(s),person,method);
        var result=contact(s,store,person);audit(s,store,person,"VERIFY_METHOD",c.requestId(),key,c.reason(),before,result,now);return result;
    }
    @Override public List<Change> changes(StoreScope s,String store,String person){
        access(s,store,false);contact(s,store,person);
        return jdbc.query("SELECT * FROM crm_contact_change WHERE tenant_id=? AND store_id=? AND contact_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100",(r,n)->new Change(r.getString("id"),r.getString("contact_id"),r.getString("operation"),r.getString("actor_user_id"),r.getString("actor_employee_id"),r.getString("reason"),r.getTimestamp("occurred_at").toInstant()),tenant(s),store,person);
    }
    private Map<String,Object> visit(StoreScope s,String store,String visit){
        var rows=jdbc.queryForList("SELECT salesperson_id,status_code FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=?",tenant(s),store,visit);
        if(rows.isEmpty())throw new StateConflictException("拜访不存在");return rows.getFirst();
    }
    @Override public List<Contact> reception(StoreScope s,String store,String visit){access(s,store,false);visit(s,store,visit);return receptionRows(s,visit);}
    private List<Contact> receptionRows(StoreScope s,String visit){
        return jdbc.query("SELECT contact_snapshot FROM crm_visit_contact WHERE tenant_id=? AND visit_id=? ORDER BY contact_id",(r,n)->json.readValue(r.getString(1),Contact.class),tenant(s),visit);
    }
    @Override @Transactional public List<Contact> reception(StoreScope s,String store,String visit,Reception c,Instant now){
        lock(s,store);
        jdbc.queryForList("SELECT id FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=? FOR UPDATE",tenant(s),store,visit);
        var v=visit(s,store,visit);
        if(s.access().employeeId()==null||!s.access().employeeId().equals(v.get("salesperson_id").toString()))throw new AuthorizationDeniedException("visit-owner");
        String key=hash("RECEPTION",store,visit,c);var replay=replay(s,store,c.requestId(),key);
        if(replay!=null)return json.readValue(replay,new TypeReference<List<Contact>>(){});
        if(!"IN_PROGRESS".equals(v.get("status_code")))throw new StateConflictException("拜访已提交，不能修改接待人快照");
        var selected=c.contactIds().stream().sorted().map(id->{lockPerson(s,id);var p=contact(s,store,id);active(p);return p;}).toList();
        var before=receptionRows(s,visit);
        jdbc.update("DELETE FROM crm_visit_contact WHERE tenant_id=? AND visit_id=?",tenant(s),visit);
        for(var p:selected)jdbc.update("INSERT INTO crm_visit_contact(tenant_id,visit_id,contact_id,contact_snapshot,recorded_at,recorded_by) VALUES(?,?,?,?,?,?)",tenant(s),visit,p.id(),json.writeValueAsString(p),Timestamp.from(now),s.access().userId());
        audit(s,store,null,"RECEPTION",c.requestId(),key,"拜访 "+visit+" 接待人记录；未留资也可提交拜访",before,selected,now);return selected;
    }
    private String replay(StoreScope s,String store,String request,String hash){
        var rows=jdbc.queryForList("SELECT store_id,command_hash,actor_user_id,after_data FROM crm_contact_change WHERE tenant_id=? AND request_id=?",tenant(s),request);
        if(rows.isEmpty())return null;var old=rows.getFirst();
        if(!store.equals(old.get("store_id").toString())||!s.access().userId().equals(old.get("actor_user_id"))||!hash.equals(old.get("command_hash")))throw new StateConflictException("请求标识已用于其他操作，请刷新后重试");
        return old.get("after_data").toString();
    }
    private String hash(Object... fields){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void audit(StoreScope s,String store,String person,String operation,String request,String hash,String reason,Object before,Object after,Instant now){
        queryCache.changed(tenant(s));
        jdbc.update("INSERT INTO crm_contact_change(id,tenant_id,store_id,contact_id,operation,request_id,command_hash,actor_user_id,actor_employee_id,reason,before_data,after_data,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",IdWorker.getId(),tenant(s),store,person,operation,request,hash,s.access().userId(),s.access().employeeId(),reason,json.writeValueAsString(before),json.writeValueAsString(after),Timestamp.from(now));
    }
}

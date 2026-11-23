package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels;
import com.rigour.merchant.application.port.out.WecomStore;
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

@Repository
public class JdbcWecomStore implements WecomStore {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rigour.shared.cache.QueryCache queryCache=com.rigour.shared.cache.QueryCache.disabled();
    private final JdbcTemplate jdbc; private final JdbcStoreContactStore contacts;
    private final ObjectMapper json=new ObjectMapper();
    public JdbcWecomStore(JdbcTemplate jdbc,JdbcStoreContactStore contacts){this.jdbc=jdbc;this.contacts=contacts;}
    private static final String BASE=" FROM crm_wecom_identity i WHERE i.tenant_id=? AND EXISTS (SELECT 1 FROM crm_wecom_relation r WHERE r.tenant_id=i.tenant_id AND r.identity_id=i.id AND r.active=1";
    private static final String LINKED=" EXISTS (SELECT 1 FROM crm_store_contact c WHERE c.tenant_id=i.tenant_id AND c.contact_id=i.contact_id AND c.status_code='ACTIVE')";
    private record Filter(String sql,List<Object> args) {}
    private Filter filter(StoreScope s,boolean mine){
        var args=new ArrayList<Object>();args.add(s.access().tenantId());if(mine)args.add(s.access().employeeId());
        return new Filter(BASE+(mine?" AND r.employee_id=?":"")+")",args);
    }
    private long count(Filter f,String extra,Object... more){var args=new ArrayList<>(f.args());Collections.addAll(args,more);return jdbc.queryForObject("SELECT COUNT(DISTINCT COALESCE(i.contact_id,i.id))"+f.sql()+extra,Long.class,args.toArray());}
    @Override @Transactional(readOnly=true) public StoreSummary storeSummary(StoreScope s,String store){
        var people=contacts.list(s,store).stream().filter(p->"ACTIVE".equals(p.status())).toList();
        boolean connected=jdbc.queryForObject("SELECT COUNT(*) FROM crm_wecom_sync WHERE tenant_id=?",Long.class,s.access().tenantId())>0;
        return new StoreSummary(people.size(),people.stream().filter(p->p.methods().stream().anyMatch(m->"PHONE".equals(m.type())&&!"INVALID".equals(m.status()))).count(),connected?people.stream().filter(p->!p.wecomRelations().isEmpty()).count():null,connected?"PARTIAL":"NOT_CONNECTED");
    }
    @Override @Transactional(readOnly=true) public Summary summary(StoreScope s,boolean mine,Instant from,Instant now){
        var args=new ArrayList<Object>();args.add(s.access().tenantId());if(mine)args.add(s.access().employeeId());
        var times=jdbc.query("SELECT MIN(observed_at) observed_at FROM crm_wecom_sync WHERE tenant_id=?"+(mine?" AND employee_id=?":""), (r,n)->r.getTimestamp(1),args.toArray());
        var time=times.isEmpty()?null:times.getFirst();
        if(time==null)return new Summary("NOT_CONNECTED",null,null,null,null,null,null);
        var f=filter(s,mine);long active=count(f,"");long linked=count(f," AND "+LINKED);
        var access=StoreScopeSql.compile(s);var coverageArgs=new ArrayList<Object>();coverageArgs.add(s.access().tenantId());coverageArgs.add(s.catalog().version());coverageArgs.addAll(access.args());
        if(mine){coverageArgs.add(s.access().employeeId());coverageArgs.add(s.access().employeeId());}
        long covered=jdbc.queryForObject("SELECT COUNT(DISTINCT q.store_id) FROM crm_store_summary q JOIN crm_store_contact c ON c.tenant_id=q.tenant_id AND c.store_id=q.store_id WHERE q.tenant_id=? AND q.admin_area_version=? AND q.record_status='ACTIVE' AND c.status_code='ACTIVE' AND ("+access.sql()+")"+(mine?" AND q.pool_type='PRIVATE' AND q.owner_employee_id=?":"")+" AND EXISTS (SELECT 1 FROM crm_wecom_identity i JOIN crm_wecom_relation r ON r.tenant_id=i.tenant_id AND r.identity_id=i.id WHERE i.tenant_id=c.tenant_id AND i.contact_id=c.contact_id AND r.active=1"+(mine?" AND r.employee_id=?":"")+")",Long.class,coverageArgs.toArray());
        var newArgs=new ArrayList<Object>();newArgs.add(s.access().tenantId());newArgs.add(Timestamp.from(from));newArgs.add(Timestamp.from(now));if(mine)newArgs.add(s.access().employeeId());
        long newly=jdbc.queryForObject("SELECT COUNT(DISTINCT COALESCE(i.contact_id,i.id)) FROM crm_wecom_identity i WHERE i.tenant_id=? AND EXISTS (SELECT 1 FROM crm_wecom_relation n WHERE n.tenant_id=i.tenant_id AND n.identity_id=i.id AND n.acquisition=1 AND n.added_at>=? AND n.added_at<?"+(mine?" AND n.employee_id=?":"")+")",Long.class,newArgs.toArray());
        return new Summary(time.toInstant().isBefore(now.minusSeconds(86400))?"STALE":mine?"READY":"PARTIAL",time.toInstant(),active,linked,active-linked,covered,newly);
    }
    @Override @Transactional(readOnly=true) public Page list(StoreScope s,boolean mine,boolean pending,String cursor,int size){
        var f=filter(s,mine);String where=pending?" AND NOT "+LINKED:"";
        long total=count(f,where);var args=new ArrayList<>(f.args());
        String having="";if(cursor!=null&&!cursor.isBlank()){having=" HAVING MIN(i.id)<?";args.add(cursor);}args.add(size+1);
        var ids=jdbc.queryForList("SELECT MIN(i.id)"+f.sql()+where+" GROUP BY COALESCE(i.contact_id,i.id)"+having+" ORDER BY MIN(i.id) DESC LIMIT ?",String.class,args.toArray());
        boolean more=ids.size()>size;if(more)ids=ids.subList(0,size);
        var items=ids.stream().map(id->person(s,id,mine)).toList();return new Page(total,items,more?ids.getLast():null);
    }
    private Person person(StoreScope s,String id,boolean mine){
        return jdbc.queryForObject("SELECT i.id,i.contact_id,COALESCE(p.name,i.display_name) name,"+LINKED+" linked FROM crm_wecom_identity i LEFT JOIN crm_contact_person p ON p.tenant_id=i.tenant_id AND p.id=i.contact_id WHERE i.tenant_id=? AND i.id=?",(r,n)->{
            String contact=r.getString("contact_id");
            return new Person(id,contact,r.getString("name"),r.getBoolean("linked"),contact==null?List.of():stores(s,contact,mine),relations(s,id,contact,mine));
        },s.access().tenantId(),id);
    }
    private List<Store> stores(StoreScope s,String contact,boolean mine){
        var access=StoreScopeSql.compile(s);var args=new ArrayList<Object>();args.add(s.access().tenantId());args.add(contact);args.add(s.catalog().version());args.addAll(access.args());if(mine)args.add(s.access().employeeId());
        return jdbc.query("SELECT q.store_id,m.name,c.role_name FROM crm_store_contact c JOIN crm_store_summary q ON q.tenant_id=c.tenant_id AND q.store_id=c.store_id JOIN crm_store_master m ON m.tenant_id=q.tenant_id AND m.id=q.store_id WHERE c.tenant_id=? AND c.contact_id=? AND c.status_code='ACTIVE' AND q.record_status='ACTIVE' AND q.admin_area_version=? AND ("+access.sql()+")"+(mine?" AND q.pool_type='PRIVATE' AND q.owner_employee_id=?":"")+" ORDER BY m.name,q.store_id",(r,n)->new Store(r.getString(1),r.getString(2),r.getString(3)),args.toArray());
    }
    private List<StoreContactModels.WecomRelation> relations(StoreScope s,String id,String contact,boolean mine){
        var args=new ArrayList<Object>();args.add(s.access().tenantId());args.add(contact==null?id:contact);if(mine)args.add(s.access().employeeId());
        return jdbc.query("SELECT DISTINCT r.employee_id,r.employee_name,r.added_at FROM crm_wecom_identity i JOIN crm_wecom_relation r ON r.tenant_id=i.tenant_id AND r.identity_id=i.id WHERE i.tenant_id=? AND "+(contact==null?"i.id=?":"i.contact_id=?")+" AND r.active=1"+(mine?" AND r.employee_id=?":"")+" ORDER BY r.added_at",(r,n)->new StoreContactModels.WecomRelation(r.getString(1),r.getString(2),"ACTIVE",r.getTimestamp(3).toInstant()),args.toArray());
    }
    @Override @Transactional public StoreContactModels.Contact bind(StoreScope s,String identity,Bind c,Instant now){
        String tenant=s.access().tenantId();
        jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",tenant,c.storeId());contacts.access(s,c.storeId(),true);
        var identityRows=jdbc.queryForList("SELECT contact_id FROM crm_wecom_identity WHERE tenant_id=? AND id=? FOR UPDATE",tenant,identity);
        if(identityRows.isEmpty()||jdbc.queryForObject("SELECT COUNT(*) FROM crm_wecom_relation WHERE tenant_id=? AND identity_id=? AND employee_id=? AND active=1",Long.class,tenant,identity,s.access().employeeId())==0)throw new AuthorizationDeniedException("wecom-own-relation");
        String key=hash(identity+json.writeValueAsString(c));
        var previous=jdbc.queryForList("SELECT contact_id,command_hash FROM crm_wecom_link_change WHERE tenant_id=? AND request_id=?",tenant,c.requestId());
        if(!previous.isEmpty()){
            if(!key.equals(previous.getFirst().get("command_hash")))throw new StateConflictException("请求标识已用于其他关联操作");
            String person=previous.getFirst().get("contact_id").toString();return contacts.list(s,c.storeId()).stream().filter(p->p.id().equals(person)).findFirst().orElseThrow(()->new StateConflictException("关联已变化，请刷新"));
        }
        Object existing=identityRows.getFirst().get("contact_id");
        if(existing!=null&&!existing.toString().equals(c.contactId()))throw new StateConflictException("该企微身份已关联联系人，请维护现有门店关系，不能重复新建");
        StoreContactModels.Contact result;
        if(c.contactId()==null)result=contacts.create(s,c.storeId(),new StoreContactModels.Create(c.requestId(),c.name().strip(),c.role().strip(),false,null,null),now);
        else if(existing!=null)result=contacts.linkWecom(s,c.storeId(),c.contactId(),c.role(),c.reason(),c.requestId(),now);
        else result=contacts.list(s,c.storeId()).stream().filter(p->p.id().equals(c.contactId())&&"ACTIVE".equals(p.status())).findFirst().orElseThrow(()->new StateConflictException("请选择当前门店的有效联系人"));
        jdbc.update("UPDATE crm_wecom_identity SET contact_id=? WHERE tenant_id=? AND id=?",result.id(),tenant,identity);
        jdbc.update("INSERT INTO crm_wecom_link_change(tenant_id,request_id,identity_id,contact_id,store_id,actor_user_id,reason,command_hash,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",tenant,c.requestId(),identity,result.id(),c.storeId(),s.access().userId(),c.reason().strip(),key,Timestamp.from(now));
        queryCache.changed(tenant);
        String person=result.id();return contacts.list(s,c.storeId()).stream().filter(p->p.id().equals(person)).findFirst().orElseThrow();
    }
    @Override @Transactional public void accept(Snapshot c,Instant now){
        String scope=hash(c.identityScope()),body=hash(json.writeValueAsString(c));
        jdbc.update("INSERT IGNORE INTO crm_wecom_sync(tenant_id,scope_key,employee_id,sequence_no,observed_at,payload_hash,coverage_from) VALUES(?,?,?,0,?,?,?)",c.tenantId(),scope,c.employeeId(),Timestamp.from(c.observedAt()),body,Timestamp.from(c.observedAt()));
        var sync=jdbc.queryForObject("SELECT sequence_no,observed_at,payload_hash FROM crm_wecom_sync WHERE tenant_id=? AND scope_key=? AND employee_id=? FOR UPDATE",(r,n)->new Sync(r.getLong(1),r.getTimestamp(2).toInstant(),r.getString(3)),c.tenantId(),scope,c.employeeId());
        long sequence=sync.sequence();
        if(c.sequence()<sequence)return;
        if(c.sequence()==sequence){if(!body.equals(sync.hash()))throw new StateConflictException("相同同步版本内容不一致");return;}
        if(sequence>0&&c.observedAt().isBefore(sync.observedAt()))throw new StateConflictException("同步时间不能倒退");
        jdbc.update("UPDATE crm_wecom_relation SET active=0,observed_at=? WHERE tenant_id=? AND scope_key=? AND employee_id=? AND active=1",Timestamp.from(c.observedAt()),c.tenantId(),scope,c.employeeId());
        for(var f:c.customers().stream().sorted(Comparator.comparing(CustomerFact::externalUserId)).toList()){
            String identityKey=hash(json.writeValueAsString(List.of(c.identityScope(),f.externalUserId())));
            jdbc.update("INSERT IGNORE INTO crm_wecom_identity(id,tenant_id,identity_key,display_name,created_at) VALUES(?,?,?,?,?)",IdWorker.getId(),c.tenantId(),identityKey,f.name(),Timestamp.from(now));
            String identity=jdbc.queryForObject("SELECT id FROM crm_wecom_identity WHERE tenant_id=? AND identity_key=? FOR UPDATE",String.class,c.tenantId(),identityKey);
            boolean prior=jdbc.queryForObject("SELECT COUNT(*) FROM crm_wecom_relation WHERE tenant_id=? AND identity_id=? AND employee_id=?",Long.class,c.tenantId(),identity,c.employeeId())>0;
            jdbc.update("UPDATE crm_wecom_identity SET display_name=? WHERE tenant_id=? AND id=?",f.name(),c.tenantId(),identity);
            jdbc.update("INSERT INTO crm_wecom_relation(tenant_id,identity_id,scope_key,employee_id,employee_name,added_at,active,acquisition,observed_at) VALUES(?,?,?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE active=1,employee_name=VALUES(employee_name),observed_at=VALUES(observed_at)",c.tenantId(),identity,scope,c.employeeId(),c.employeeName(),Timestamp.from(f.addedAt()),sequence>0&&!prior&&"NEW".equals(f.origin()),Timestamp.from(c.observedAt()));
        }
        jdbc.update("UPDATE crm_wecom_sync SET sequence_no=?,observed_at=?,payload_hash=? WHERE tenant_id=? AND scope_key=? AND employee_id=?",c.sequence(),Timestamp.from(c.observedAt()),body,c.tenantId(),scope,c.employeeId());
        queryCache.changed(c.tenantId());
    }
    private record Sync(long sequence,Instant observedAt,String hash) {}
    private static String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}

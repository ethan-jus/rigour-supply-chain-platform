package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreVisitVerificationModels.*;
import com.rigour.merchant.application.port.out.StoreVisitVerificationStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStoreVisitVerificationStore implements StoreVisitVerificationStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rigour.shared.cache.QueryCache cache=com.rigour.shared.cache.QueryCache.disabled();
    public JdbcStoreVisitVerificationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public boolean belongsTo(StoreScope scope,String store,String visit) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=? AND salesperson_id=?",Long.class,scope.access().tenantId(),store,visit,scope.access().employeeId())==1;
    }
    @Override public Verification read(StoreScope s,String store,String visit){
        var rows=jdbc.queryForList("SELECT salesperson_id,verification_state,review_reason,revision,review_source,submitted_at FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=?",s.access().tenantId(),store,visit);
        if(rows.isEmpty())throw new StateConflictException("拜访不存在");
        var row=rows.getFirst();
        var appeals=jdbc.query("SELECT * FROM crm_visit_appeal WHERE tenant_id=? AND visit_id=? ORDER BY submitted_at DESC,id DESC",(r,n)->appeal(r),s.access().tenantId(),visit);
        boolean pending=appeals.stream().anyMatch(a->"PENDING".equals(a.status()));
        String state=row.get("verification_state").toString();
        boolean own=Objects.equals(s.access().employeeId(),row.get("salesperson_id").toString());
        return new Verification(visit,pending?"APPEALED":state,(String)row.get("review_reason"),((Number)row.get("revision")).longValue(),
                own&&row.get("submitted_at")!=null&&!pending&&Set.of("FAILED","INSUFFICIENT").contains(state)&&!"CANCELLED".equals(row.get("review_source")),appeals);
    }
    @Override @Transactional public Verification appeal(StoreScope s,String store,String visit,AppealCommand c,Instant now){
        // Same lock order as evidence delivery and manual decisions.
        jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",s.access().tenantId(),store);
        var v=read(s,store,visit);
        String owner=jdbc.queryForObject("SELECT salesperson_id FROM crm_visit WHERE tenant_id=? AND store_id=? AND id=?",String.class,s.access().tenantId(),store,visit);
        if(!Objects.equals(owner,s.access().employeeId()))throw new AuthorizationDeniedException("visit-owner");
        var prior=jdbc.queryForList("SELECT visit_id,reason FROM crm_visit_appeal WHERE tenant_id=? AND request_id=?",s.access().tenantId(),c.requestId());
        if(!prior.isEmpty()) {
            if(!visit.equals(prior.getFirst().get("visit_id").toString())||!c.reason().strip().equals(prior.getFirst().get("reason")))throw new StateConflictException("请求标识已用于其他申诉");
            return v;
        }
        if(!v.canAppeal()||v.revision()!=c.revision())throw new StateConflictException("当前拜访不能重复申诉，请刷新结果");
        jdbc.update("INSERT INTO crm_visit_appeal(id,tenant_id,store_id,visit_id,salesperson_id,request_id,reason,original_state,original_reason,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                IdWorker.getId(),s.access().tenantId(),store,visit,owner,c.requestId(),c.reason().strip(),v.state(),v.reason(),Timestamp.from(now));
        jdbc.update("UPDATE crm_visit SET revision=revision+1,updated_at=? WHERE tenant_id=? AND id=?",Timestamp.from(now),s.access().tenantId(),visit);
        cache.changed(s.access().tenantId());
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,request_id,event_seq,reason) VALUES(?,?,?,'VISIT',?,'VISIT_APPEALED',?,?,?,?,1,?)",
                IdWorker.getId(),s.access().tenantId(),store,visit,s.access().userId(),owner,Timestamp.from(now),c.requestId(),c.reason().strip());
        return read(s,store,visit);
    }
    @Override public Queue queue(StoreScope s,String status,int page,int size){
        var scope=StoreScopeSql.compile(s);
        String from=" FROM crm_visit_appeal a JOIN crm_visit v ON v.tenant_id=a.tenant_id AND v.id=a.visit_id JOIN crm_store_summary q ON q.tenant_id=a.tenant_id AND q.store_id=a.store_id JOIN crm_store_master m ON m.tenant_id=q.tenant_id AND m.id=q.store_id WHERE a.tenant_id=? AND a.status=? AND ("+scope.sql()+")";
        var args=new ArrayList<Object>(List.of(s.access().tenantId(),status));args.addAll(scope.args());
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact(page-1,size));
        var rows=jdbc.query("SELECT a.*,m.name store_name,v.capture_ref,v.employee_code_snapshot,v.visited_at"+from+" ORDER BY a.submitted_at,a.id LIMIT ? OFFSET ?",(r,n)->new QueueRow(r.getString("store_id"),r.getString("store_name"),r.getString("visit_id"),r.getString("capture_ref"),r.getString("employee_code_snapshot"),r.getTimestamp("visited_at").toInstant(),appeal(r)),args.toArray());
        return new Queue(rows,total,page,size);
    }
    private static Appeal appeal(ResultSet r)throws SQLException {
        var reviewed=r.getTimestamp("reviewed_at");
        return new Appeal(r.getString("id"),r.getString("status"),r.getString("reason"),r.getString("original_state"),r.getString("original_reason"),r.getTimestamp("submitted_at").toInstant(),r.getString("decision"),r.getString("review_note"),reviewed==null?null:reviewed.toInstant());
    }
}

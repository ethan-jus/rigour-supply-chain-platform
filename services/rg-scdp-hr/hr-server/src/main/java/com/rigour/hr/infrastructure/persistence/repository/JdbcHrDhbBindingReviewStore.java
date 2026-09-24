package com.rigour.hr.infrastructure.persistence.repository;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrDhbBindingReviewStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Repository
public class JdbcHrDhbBindingReviewStore implements HrDhbBindingReviewStore {
    private final JdbcTemplate jdbc;
    private final HrDataScope scopes;
    public JdbcHrDhbBindingReviewStore(JdbcTemplate jdbc,HrDataScope scopes) { this.jdbc=jdbc; this.scopes=scopes; }
    @Override
    public List<DhbBindingReviewView> pending(String tenant) {
        var p=scopes.employeePredicate("hr:employee:read","e.");
        var args=new ArrayList<Object>(); args.add(tenant); args.addAll(p.args());
        return jdbc.query("SELECT b.*,e.id employee_id,e.employee_name,e.mobile,e.department_name_snapshot "
                + "FROM hr_employee_source_binding b JOIN hr_employee e ON e.tenant_id=b.tenant_id AND e.employee_code=b.employee_code "
                + "WHERE b.tenant_id=? AND b.source_system='DINGHUOBAO' AND b.deleted=0 AND e.deleted=0 "
                + "AND b.review_required=1 AND " + p.sql() + " ORDER BY b.id",
                (r,n)->new DhbBindingReviewView(r.getLong("id"),r.getLong("review_version"),r.getLong("employee_id"),
                        r.getString("employee_code"),r.getString("employee_name"),r.getString("mobile"),r.getString("department_name_snapshot"),
                        r.getString("source_employee_id"),r.getString("source_account_name"),r.getString("source_employee_name"),
                        r.getString("source_mobile"),r.getString("review_reason")),args.toArray());
    }
    @Override @Transactional
    public void confirm(String tenant,long bindingId,DhbBindingReviewCommand command,String actor) {
        var bindings=jdbc.queryForList("SELECT * FROM hr_employee_source_binding WHERE tenant_id=? AND id=? "
                + "AND source_system='DINGHUOBAO' AND deleted=0 FOR UPDATE",tenant,bindingId);
        if(bindings.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"员工关联不存在",List.of());
        var b=bindings.getFirst();
        var current=jdbc.queryForList("SELECT id FROM hr_employee WHERE tenant_id=? AND employee_code=? AND deleted=0",tenant,b.get("employee_code"));
        if(current.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT,"原员工已变更，请重新核对",List.of());
        scopes.requireEmployee(tenant,((Number)current.getFirst().get("id")).longValue(),"hr:employee:update");
        scopes.requireEmployee(tenant,command.targetEmployeeId(),"hr:employee:update");
        String target=jdbc.queryForObject("SELECT employee_code FROM hr_employee WHERE tenant_id=? AND id=? AND deleted=0",String.class,tenant,command.targetEmployeeId());
        int changed=jdbc.update("UPDATE hr_employee_source_binding SET employee_code=?,review_required=0,review_reason=NULL,"
                + "review_version=review_version+1,updated_by=?,updated_time=UTC_TIMESTAMP(6) "
                + "WHERE tenant_id=? AND id=? AND review_version=? AND review_required=1 AND deleted=0",
                target,actor,tenant,bindingId,command.expectedVersion());
        if(changed!=1) throw new BusinessException(ErrorCode.CONFLICT,"关联已更新，请刷新后重新核对",List.of());
        jdbc.update("INSERT INTO hr_dhb_binding_review_audit(tenant_id,binding_id,before_employee_code,after_employee_code,"
                + "source_employee_name,source_mobile,source_account_name,review_reason,actor_id,reviewed_at,review_version) "
                + "VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?)",tenant,bindingId,b.get("employee_code"),target,
                b.get("source_employee_name"),b.get("source_mobile"),b.get("source_account_name"),b.get("review_reason"),actor,command.expectedVersion());
    }
}

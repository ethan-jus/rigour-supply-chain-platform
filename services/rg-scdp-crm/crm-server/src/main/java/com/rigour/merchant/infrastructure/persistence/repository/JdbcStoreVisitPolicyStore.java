package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy;
import com.rigour.merchant.application.port.out.StoreVisitPolicyStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcStoreVisitPolicyStore implements StoreVisitPolicyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcStoreVisitPolicyStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Policy active(StoreScope scope,String storeId,Instant now) {
        String tenant=scope.access().tenantId();
        String area=jdbc.queryForObject("SELECT admin_area_code FROM crm_store_master WHERE tenant_id=? AND id=?",String.class,tenant,storeId);
        var ancestors=scope.ancestry(area).stream().map(a->a.code()).toList();
        var rows=jdbc.queryForList("""
                SELECT p.id,p.policy_json FROM crm_visit_policy_version p
                WHERE p.tenant_id=? AND p.status_code='PUBLISHED'
                AND (p.effective_from IS NULL OR p.effective_from<=?)
                AND (p.effective_to IS NULL OR p.effective_to>?)
                ORDER BY p.version_no DESC,p.id
                """,tenant,Timestamp.from(now),Timestamp.from(now));
        var scopes=jdbc.queryForList("SELECT policy_id,scope_type,scope_key FROM crm_visit_policy_scope WHERE tenant_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)",tenant,Timestamp.from(now),Timestamp.from(now));
        for(var row:rows) {
            boolean allowed=scopes.isEmpty() || scopes.stream().filter(s->row.get("id").equals(s.get("policy_id"))).anyMatch(s->switch(s.get("scope_type").toString()) {
                case "ALL" -> true;
                case "EMPLOYEE" -> Objects.equals(scope.access().employeeId(),s.get("scope_key"));
                case "DEPARTMENT" -> Objects.equals(scope.access().departmentId(),s.get("scope_key"));
                case "AREA" -> ancestors.contains(s.get("scope_key").toString());
                default -> false;
            });
            if(allowed) return json.readValue(row.get("policy_json").toString(),Policy.class);
        }
        throw new StateConflictException("CRM 尚未发布适用的拜访政策，请联系管理员");
    }
}

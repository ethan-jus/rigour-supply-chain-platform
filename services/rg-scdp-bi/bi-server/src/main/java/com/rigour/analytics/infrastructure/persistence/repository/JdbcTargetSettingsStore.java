package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.analytics.application.port.out.TargetSettingsStore;
import com.rigour.analytics.application.service.OperatingWorkspaceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class JdbcTargetSettingsStore implements TargetSettingsStore {
    private final JdbcTemplate jdbc;
    public JdbcTargetSettingsStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public boolean permitted(String tenant,String type,String code,String action) {
        var policy=com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(action);
        if(policy==null) return false;
        boolean city="CITY".equals(type);
        var predicate=com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(policy,city?"CITY":"EMPLOYEE");
        String condition=predicate.text().replace("a.department_id","f.department_id")
                .replace("a.department_path","f.department_path");
        if(!city) condition=condition.replace("a.employee_code","f.employee_code");
        String table=city?"bi_sales_contact_city_dim":"bi_employee_dim";
        String region=city?"f.source_region_code":"f.region_code";
        var args=new ArrayList<Object>(); args.add(tenant); args.add(code); args.addAll(predicate.args());
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" f LEFT JOIN bi_region_authority a ON a.tenant_id=f.tenant_id AND a.region_code="+region
                +" WHERE f.tenant_id=? AND f."+(city?"region_code":"employee_code")+"=? AND "+condition,Integer.class,args.toArray())==1;
    }
    @Override public List<Subject> subjects(String tenant) {
        var result=new ArrayList<>(jdbc.query("""
                SELECT region_code,city_name FROM bi_sales_contact_city_dim
                 WHERE tenant_id=? AND TRIM(region_code)<>'' ORDER BY city_name
                """,(r,n)->new Subject("CITY",r.getString(1),r.getString(2),r.getString(1),
                        r.getString(2),null,null,false),tenant));
        result.addAll(jdbc.query("""
                SELECT e.employee_code,e.employee_name,e.department_name,e.employment_status,
                       CASE WHEN COUNT(DISTINCT c.region_code)=1 THEN MAX(c.region_code) END city_code,
                       CASE WHEN COUNT(DISTINCT c.region_code)=1 THEN MAX(c.city_name) ELSE '归属待核对' END city_name
                  FROM bi_employee_dim e
                  LEFT JOIN bi_sales_contact_city_dim c ON c.tenant_id=e.tenant_id
                   AND (e.department_id=c.department_id OR
                     REPLACE(REPLACE(REPLACE(e.department_path,'[',','),']',','),' ','') LIKE CONCAT('%,',c.department_id,',%'))
                 WHERE e.tenant_id=? AND TRIM(e.employee_code)<>''
                   AND (c.region_code IS NOT NULL OR EXISTS (SELECT 1 FROM bi_business_target t
                     WHERE t.tenant_id=e.tenant_id AND t.dimension_type='SALES_OWNER' AND t.dimension_code=e.employee_code))
                 GROUP BY e.employee_code,e.employee_name,e.department_name,e.employment_status
                 ORDER BY city_name,e.employee_name
                """,(r,n)->new Subject("SALES_OWNER",r.getString(1),r.getString(2),r.getString("city_code"),
                        r.getString("city_name"),r.getString(3),r.getString(4),false),tenant));
        return result;
    }
    @Override public List<TargetOverride> overrides(String tenant,String month) {
        return jdbc.query("""
                SELECT dimension_type,dimension_code,metric_code,target_value,revision,deleted,updated_by,updated_time
                  FROM bi_business_target WHERE tenant_id=? AND target_month=?
                """,(r,n)->new TargetOverride(r.getString(1),r.getString(2),r.getString(3),r.getBigDecimal(4),
                        r.getInt(5),r.getBoolean(6),r.getString(7),r.getTimestamp(8).toInstant()),tenant,YearMonth.parse(month).atDay(1));
    }
    @Override public List<DefaultRule> defaults(String tenant) {
        return jdbc.query("""
                SELECT dimension_type,effective_month,metric_code,target_value,revision
                  FROM bi_target_default WHERE tenant_id=? ORDER BY effective_month
                """,(r,n)->new DefaultRule(r.getString(1),r.getDate(2).toLocalDate().toString().substring(0,7),
                        r.getString(3),r.getBigDecimal(4),r.getInt(5)),tenant);
    }
    @Override public void save(String tenant,String actor,String month,Subject subject,Change c,String reason,Instant now) {
        var date=YearMonth.parse(month).atDay(1); var at=Timestamp.from(now);
        boolean reset=c.value()==null;
        if(c.expectedRevision()==0) {
            try {
                // Keep a revisioned tombstone for a reset, even when there was no explicit override.
                jdbc.update("""
                        INSERT INTO bi_business_target(tenant_id,target_month,dimension_type,dimension_code,dimension_name,
                          metric_code,target_value,source_system_code,remark,synced_time,revision,created_by,updated_by,created_time,updated_time,deleted)
                        VALUES(?,?,?,?,?,?,?,'BI_MANUAL',?,?,1,?,?,?,?,?)
                        """,tenant,date,c.dimensionType(),c.code(),subject.name(),c.metric(),reset?java.math.BigDecimal.ZERO:c.value(),
                        reason,at,actor,actor,at,at,reset?1:0);
            } catch(DuplicateKeyException e) { throw OperatingWorkspaceService.conflict(); }
        } else {
            int changed=jdbc.update("""
                    UPDATE bi_business_target SET target_value=?,deleted=?,dimension_name=?,remark=?,revision=revision+1,
                        updated_by=?,updated_time=?,synced_time=?,source_system_code='BI_MANUAL'
                     WHERE tenant_id=? AND target_month=? AND dimension_type=? AND dimension_code=? AND metric_code=? AND revision=?
                    """,reset?java.math.BigDecimal.ZERO:c.value(),reset?1:0,subject.name(),reason,actor,at,at,
                    tenant,date,c.dimensionType(),c.code(),c.metric(),c.expectedRevision());
            if(changed!=1) throw OperatingWorkspaceService.conflict();
        }
        jdbc.update("""
                INSERT INTO bi_business_target_event(id,tenant_id,target_id,revision,target_value,dimension_name,remark,deleted,actor,occurred_at)
                SELECT ?,tenant_id,id,revision,target_value,dimension_name,remark,deleted,?,?
                  FROM bi_business_target WHERE tenant_id=? AND target_month=? AND dimension_type=? AND dimension_code=? AND metric_code=?
                """,UUID.randomUUID().toString(),actor,at,tenant,date,c.dimensionType(),c.code(),c.metric());
    }
    @Override public void saveDefault(String tenant,String actor,String month,String type,DefaultChange c,String reason,Instant now) {
        var date=YearMonth.parse(month).atDay(1); var at=Timestamp.from(now);
        if(c.expectedRevision()==0) {
            try { jdbc.update("""
                    INSERT INTO bi_target_default(tenant_id,effective_month,dimension_type,metric_code,target_value,revision,updated_by,updated_at)
                    VALUES(?,?,?,?,?,1,?,?)
                    """,tenant,date,type,c.metric(),c.value(),actor,at);
            } catch(DuplicateKeyException e) { throw OperatingWorkspaceService.conflict(); }
        } else if(jdbc.update("""
                UPDATE bi_target_default SET target_value=?,revision=revision+1,updated_by=?,updated_at=?
                 WHERE tenant_id=? AND effective_month=? AND dimension_type=? AND metric_code=? AND revision=?
                """,c.value(),actor,at,tenant,date,type,c.metric(),c.expectedRevision())!=1) throw OperatingWorkspaceService.conflict();
        jdbc.update("""
                INSERT INTO bi_target_default_event(id,tenant_id,effective_month,dimension_type,metric_code,target_value,revision,reason,actor,occurred_at)
                SELECT ?,tenant_id,effective_month,dimension_type,metric_code,target_value,revision,?,?,?
                  FROM bi_target_default WHERE tenant_id=? AND effective_month=? AND dimension_type=? AND metric_code=?
                """,UUID.randomUUID().toString(),reason,actor,at,tenant,date,type,c.metric());
    }
    @Override public List<History> history(String tenant,String month,String type,String code) {
        if("DEFAULT".equals(code)) return jdbc.query("""
                SELECT metric_code,target_value,0 deleted,revision,reason,actor,occurred_at
                  FROM bi_target_default_event WHERE tenant_id=? AND effective_month=? AND dimension_type=?
                 ORDER BY occurred_at DESC,revision DESC
                """,(r,n)->new History(r.getString(1),r.getBigDecimal(2),r.getBoolean(3),r.getInt(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant()),
                tenant,YearMonth.parse(month).atDay(1),type);
        return jdbc.query("""
                SELECT t.metric_code,e.target_value,e.deleted,e.revision,e.remark,e.actor,e.occurred_at
                  FROM bi_business_target_event e JOIN bi_business_target t ON t.tenant_id=e.tenant_id AND t.id=e.target_id
                 WHERE t.tenant_id=? AND t.target_month=? AND t.dimension_type=? AND t.dimension_code=?
                 ORDER BY e.occurred_at DESC,e.revision DESC
                """,(r,n)->new History(r.getString(1),r.getBigDecimal(2),r.getBoolean(3),r.getInt(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant()),
                tenant,YearMonth.parse(month).atDay(1),type,code);
    }
}

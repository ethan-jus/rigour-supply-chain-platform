package com.rigour.hr.infrastructure.persistence.settings;
import com.rigour.hr.application.port.out.SupplyReadinessStore;
import com.rigour.tenant.iam.api.v1.model.SupplyReadinessView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** 本域只读切换检查；查询失败必须阻止启用，不返回伪成功。 */
@Repository
public class JdbcSupplyReadinessStore implements SupplyReadinessStore {
 private final JdbcTemplate jdbc; public JdbcSupplyReadinessStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(readOnly=true)
 public SupplyReadinessView inspect(String tenant){
  String schema=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(SUM(checksum),0)) FROM flyway_schema_history WHERE success=1",String.class);
  String data=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(access_version),0)) FROM hr_employee WHERE tenant_id=?",String.class,tenant);
  var checks=new ArrayList<SupplyReadinessView.Check>();checks.add(new SupplyReadinessView.Check("EMPLOYEE_DEPARTMENT","WARNING",jdbc.queryForObject("SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND deleted=0 AND employment_status='ACTIVE' AND department_id IS NULL",Long.class,tenant),"在职员工尚未分配部门；关联账号前请在 HR 完善"));
  return new SupplyReadinessView("hr",1,schema+":"+data,checks);
 }
}

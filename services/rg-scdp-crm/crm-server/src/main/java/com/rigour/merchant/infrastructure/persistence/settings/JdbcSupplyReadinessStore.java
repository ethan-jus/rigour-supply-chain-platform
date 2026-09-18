package com.rigour.merchant.infrastructure.persistence.settings;
import com.rigour.merchant.application.port.out.SupplyReadinessStore;
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
  String data=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM crm_customer WHERE tenant_id=?",String.class,tenant);
  var checks=new ArrayList<SupplyReadinessView.Check>();checks.add(new SupplyReadinessView.Check("CUSTOMER_OWNER","WARNING",jdbc.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=? AND deleted=0 AND (owner_employee_code IS NULL OR owner_employee_code='')",Long.class,tenant),"未分配主责的客户不能直接提交新订单，地区负责人仍可按地区管理"));
  return new SupplyReadinessView("crm",1,schema+":"+data,checks);
 }
}

package com.rigour.erp.infrastructure.persistence.settings;
import com.rigour.erp.application.port.out.SupplyReadinessStore;
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
  String data=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM erp_inventory_warehouse WHERE tenant_id=?",String.class,tenant);
  var checks=new ArrayList<SupplyReadinessView.Check>();
  return new SupplyReadinessView("erp",1,schema+":"+data,checks);
 }
}

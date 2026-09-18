package com.rigour.order.infrastructure.persistence.settings;
import com.rigour.order.application.port.out.SupplyReadinessStore;
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
  String data=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM order_sales_order WHERE tenant_id=?",String.class,tenant);
  var checks=new ArrayList<SupplyReadinessView.Check>();checks.add(new SupplyReadinessView.Check("ORDER_HISTORY","WARNING",jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_order o LEFT JOIN order_attribution_snapshot s ON s.tenant_id=o.tenant_id AND s.order_id=o.id WHERE o.tenant_id=? AND o.deleted=0 AND o.order_status_code<>'DRAFT' AND (s.state IS NULL OR s.state<>'FROZEN')",Long.class,tenant),"历史归属未冻结的订单保留待核对状态，不能按当前客户主责补写历史"));
  return new SupplyReadinessView("order",1,schema+":"+data,checks);
 }
}

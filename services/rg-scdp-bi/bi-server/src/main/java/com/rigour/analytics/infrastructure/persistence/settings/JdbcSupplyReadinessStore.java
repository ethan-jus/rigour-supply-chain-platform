package com.rigour.analytics.infrastructure.persistence.settings;
import com.rigour.analytics.application.port.out.SupplyReadinessStore;
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
  String data=jdbc.queryForObject("SELECT CONCAT(COUNT(*),':',COALESCE(MAX(source_version),'')) FROM bi_order_authority WHERE tenant_id=?",String.class,tenant);
  var checks=new ArrayList<SupplyReadinessView.Check>();checks.add(new SupplyReadinessView.Check("BI_HISTORY","WARNING",jdbc.queryForObject("SELECT COUNT(*) FROM bi_sales_order_fact f LEFT JOIN bi_order_authority a ON a.tenant_id=f.tenant_id AND a.order_id=f.order_id WHERE f.tenant_id=? AND (a.attribution_state IS NULL OR a.attribution_state<>'FROZEN')",Long.class,tenant),"报表内部分订单缺少冻结归属；受限角色不显示这些归属未核对的数据"));
  return new SupplyReadinessView("bi",1,schema+":"+data,checks);
 }
}

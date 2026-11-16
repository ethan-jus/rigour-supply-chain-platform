package com.rigour.order.infrastructure.persistence.repository;
import com.rigour.order.api.v1.CustomerOrders;
import com.rigour.order.api.v1.OrderWorkbenchApi.Row;
import com.rigour.order.application.port.out.CustomerOrderStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JdbcCustomerOrderStore implements CustomerOrderStore {
 private final JdbcTemplate jdbc;private final OrderDataScope scopes;
 public JdbcCustomerOrderStore(JdbcTemplate jdbc,OrderDataScope scopes){this.jdbc=jdbc;this.scopes=scopes;}
 @Override @Transactional(readOnly=true) public CustomerOrders read(String tenant,String id,boolean unpaid,int begin,int size){
  var policy=scopes.policy("order:read");
  if(policy==null||policy.employeeCode()==null)throw new AuthorizationDeniedException("customer-own-orders");
  var scope=scopes.predicate("order:read","o.",null);
  String from=" FROM order_sales_order o LEFT JOIN order_attribution_snapshot a ON a.tenant_id=o.tenant_id AND a.order_id=o.id AND a.state='FROZEN' WHERE o.tenant_id=? AND o.store_id=? AND COALESCE(a.employee_code,o.owner_employee_code)=? AND o.deleted=0 AND o.order_status_code IN ('SUBMITTED','COMPLETED') AND "+scope.sql();
  var args=new ArrayList<Object>(List.of(tenant,id,policy.employeeCode()));args.addAll(scope.args());
  if(unpaid)from+=" AND o.unpaid_amount>0";
  var totals=jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(o.payable_amount),0) amount,COALESCE(SUM(o.unpaid_amount),0) unpaid"+from,(r,n)->new CustomerOrders(r.getLong("total"),r.getBigDecimal("amount"),r.getBigDecimal("unpaid"),List.of()),args.toArray());
  args.add(size);args.add(begin);
  var rows=jdbc.query("SELECT o.id,o.order_no,o.customer_name_snapshot,o.order_date,"+(unpaid?"o.unpaid_amount":"o.payable_amount")+" amount,o.payment_status_code"+from+" ORDER BY o.order_date DESC,o.id DESC LIMIT ? OFFSET ?",(r,n)->new Row(r.getString("id"),r.getString("id"),r.getString("order_no"),r.getString("customer_name_snapshot"),r.getTimestamp("order_date")==null?null:r.getTimestamp("order_date").toInstant(),r.getBigDecimal("amount"),r.getString("payment_status_code")),args.toArray());
  return new CustomerOrders(totals.total(),totals.amount(),totals.unpaid(),rows);
 }
}

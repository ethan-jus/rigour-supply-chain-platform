package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.application.port.out.OrderRegisterStore.PaymentCriteria;
import com.rigour.order.application.service.sales.RegisterPage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 未入账的作废来源单只做查询投影，不创建订单、资金或回款事实。 */
final class CancelledSourceReceiptQuery {
    private CancelledSourceReceiptQuery() {}

    static OrderRegisterPage<OrderRegisterPaymentView> merge(JdbcTemplate jdbc, OrderDataScope scopes,
            String tenant, int begin, int step, PaymentCriteria c,
            OrderRegisterPage<OrderRegisterPaymentView> business) {
        var args = new ArrayList<Object>();
        args.add(tenant);
        var where = new StringBuilder("r.tenant_id=? AND r.state='CANCELLED' AND r.source_status='CANCELLED'"
                + " AND NOT EXISTS(SELECT 1 FROM order_payment_record p WHERE p.tenant_id=r.tenant_id"
                + " AND p.connector_id=r.connector_id AND (p.source_record_id=r.receipt_no"
                + " OR p.source_document_no=r.receipt_no OR p.source_document_no LIKE CONCAT(r.receipt_no,'#%')))");
        filter(where,args,"r.source_order_no LIKE ?",like(c.orderNo()));
        filter(where,args,"r.customer_id=?",c.customerId());
        filter(where,args,"customer.customer_name LIKE ?",like(c.customerName()));
        filter(where,args,"customer.customer_code LIKE ?",like(c.customerCode()));
        filter(where,args,"r.receipt_no LIKE ?",like(c.paymentNo()));
        filter(where,args,"r.occurred_at>=?",timestamp(c.paymentTimeFrom()));
        filter(where,args,"r.occurred_at<?",timestamp(c.paymentTimeTo()));
        // 无关联订单、凭证和归属证据，不能在这些筛选下冒充命中。
        if (present(c.regionCode()) || present(c.ownerEmployeeCode()) || c.departmentIds()!=null
                || c.orderDateFrom()!=null || c.orderDateTo()!=null || present(c.orderStatusCode())
                || present(c.transactionNo()) || present(c.createdBy()) || c.productIds()!=null
                || c.productVariantId()!=null || c.productVariantIds()!=null) where.append(" AND 1=0");
        // 空订单仍经过相同授权谓词；仅拥有全域回款权限的用户可见未归属来源单。
        var scope=scopes.predicate(RegisterPage.PAYMENTS.permission(),"o.",null);
        where.append(" AND (").append(scope.sql()).append(')');
        args.addAll(scope.args());
        String from=" FROM order_sync_receipt r"
                + " LEFT JOIN order_sales_order o ON o.tenant_id=r.tenant_id AND 1=0"
                + " LEFT JOIN (SELECT tenant_id,customer_id,MAX(customer_name_snapshot) customer_name,"
                + " MAX(customer_code_snapshot) customer_code FROM order_sales_order WHERE tenant_id=?"
                + " AND deleted=0 GROUP BY tenant_id,customer_id) customer"
                + " ON customer.tenant_id=r.tenant_id AND customer.customer_id=r.customer_id WHERE "+where;
        args.addFirst(tenant);
        long count=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        String sort=switch(c.sortBy()==null?"":c.sortBy()) {
            case "createdTime", "syncedAt" -> "NULL";
            default -> "r.occurred_at";
        };
        String direction="asc".equalsIgnoreCase(c.sortDirection())?" ASC":" DESC";
        var pageArgs=new ArrayList<>(args);pageArgs.add(begin+step);
        var source=jdbc.query("SELECT r.*,customer.customer_name,customer.customer_code"+from
                +" ORDER BY "+sort+direction+",r.receipt_no,r.connector_id LIMIT ?",(rs,n)->new OrderRegisterPaymentView(
                null,rs.getString("receipt_no"),rs.getString("receipt_no"),null,
                rs.getString("source_order_no"),rs.getString("source_order_no"),
                rs.getObject("customer_id",Long.class),rs.getString("customer_code"),rs.getString("customer_name"),
                null,null,null,null,null,null,null,null,rs.getBigDecimal("amount"),"CANCELLED",
                instant(rs.getTimestamp("occurred_at")),null,List.of(),List.of(),null,
                null,null,instant(rs.getTimestamp("source_updated_at")),
                null,null,null,null,rs.getInt("revision")),pageArgs.toArray());
        var rows=new ArrayList<>(business.items());rows.addAll(source);
        Comparator<Instant> dates=Comparator.nullsFirst(Comparator.naturalOrder());
        if (!"asc".equalsIgnoreCase(c.sortDirection())) dates=dates.reversed();
        Comparator<OrderRegisterPaymentView> order=Comparator.comparing(row -> switch(c.sortBy()==null?"":c.sortBy()) {
            case "createdTime" -> row.createdTime();
            case "syncedAt" -> row.syncedAt();
            default -> row.paymentTime();
        },dates);
        rows.sort(order.thenComparing(OrderRegisterPaymentView::id,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OrderRegisterPaymentView::paymentNo));
        return new OrderRegisterPage<>(business.total()+count,begin,step,
                rows.stream().skip(begin).limit(step).toList(),business.totals(),business.coverage());
    }

    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static String like(String value){return present(value)?"%"+value+"%":null;}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static void filter(StringBuilder sql,List<Object> args,String clause,Object value){
        if(value!=null){sql.append(" AND ").append(clause);args.add(value);}
    }
}

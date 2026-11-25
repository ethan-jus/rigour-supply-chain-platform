package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.port.out.PaymentFilterStore;
import com.rigour.order.application.service.sales.RegisterPage;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentFilterStore implements PaymentFilterStore {
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    public JdbcPaymentFilterStore(JdbcTemplate jdbc, OrderDataScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }
    @Override public Visible visible(String tenant, RegisterPage page) {
        var scope = scopes.registerPredicate(page);
        var args = new ArrayList<Object>(List.of(tenant));
        args.addAll(scope.args());
        boolean payment = page == RegisterPage.PAYMENTS;
        String from = " FROM order_sales_order o LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id"
                + " AND snap.order_id=o.id AND snap.state='FROZEN'"
                + (payment ? " JOIN order_payment_record p ON p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0" : "");
        String where = " WHERE o.tenant_id=? AND o.deleted=0 AND (" + scope.sql() + ")";
        String lines = " JOIN order_sales_order_line l ON l.tenant_id=o.tenant_id AND l.order_id=o.id AND l.deleted=0";
        String fields = payment ? "p.payment_owner_code,p.payment_owner_name,p.payment_owner_department_id,p.payment_owner_department_name"
                : "COALESCE(snap.employee_code,o.owner_employee_code),COALESCE(snap.employee_name,o.owner_employee_name_snapshot),"
                  + "COALESCE(snap.department_id," + OrderDataScope.recordedDepartment("o.", false) + "),"
                  + "COALESCE(snap.department_name," + OrderDataScope.recordedDepartment("o.", true) + ")";
        var employees = new TreeMap<String,Person>();
        var departments = new TreeMap<Long,String>();
        var creators = new TreeSet<String>();
        var areas = new TreeSet<String>();
        jdbc.query("SELECT DISTINCT " + fields + ",COALESCE(snap.region_code,o.region_code),"
                + "COALESCE(NULLIF(TRIM(o.source_creator_name),'')," + (payment ? "p" : "o") + ".created_by)"
                + from + (page == RegisterPage.LINES ? lines : "") + where, rs -> {
            String code = rs.getString(1), name = rs.getString(2), area = rs.getString(5), creator = rs.getString(6);
            if (code != null) employees.put(code,new Person(code, name == null ? code : name, area));
            Long dept = rs.getObject(3,Long.class);
            if (dept != null) departments.put(dept,rs.getString(4));
            if (area != null) areas.add(area);
            if (creator != null && !creator.isBlank()) creators.add(creator.trim());
        },args.toArray());
        // 成交和回款可由不同人负责。订单页的回款人选项来自可见订单。
        var collectors = new TreeMap<String,Person>();
        if (!payment) jdbc.query("SELECT DISTINCT pp.payment_owner_code,pp.payment_owner_name" + from
                + " JOIN order_payment_record pp ON pp.order_id=o.id AND pp.tenant_id=o.tenant_id AND pp.deleted=0"
                + (page == RegisterPage.LINES ? lines : "") + where + " AND pp.payment_owner_code IS NOT NULL", rs -> {
                    String code=rs.getString(1),name=rs.getString(2);
                    collectors.putIfAbsent(code,new Person(code,name==null?code:name,null));
                },args.toArray());
        Map<String,Product> products = new LinkedHashMap<>();
        if (page != RegisterPage.ORDERS) jdbc.query("SELECT l.product_id,MAX(l.product_code_snapshot),MAX(l.product_name_snapshot),"
                + "l.product_variant_id,MAX(l.specification_snapshot)" + from + lines + where
                + " AND l.product_id IS NOT NULL GROUP BY l.product_id,l.product_variant_id ORDER BY l.product_id,l.product_variant_id", rs -> {
            String id=rs.getString(1),code=rs.getString(2),name=rs.getString(3);
            var product=products.computeIfAbsent(id,k -> new Product(k,code,name,null,new ArrayList<>()));
            if (rs.getString(4)!=null) product.variants().add(new Variant(rs.getString(4),rs.getString(5)));
        },args.toArray());
        return new Visible(List.copyOf(employees.values()),List.copyOf(creators),areas,departments,List.copyOf(products.values()),List.copyOf(collectors.values()));
    }
}

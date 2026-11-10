package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.port.out.PaymentFilterStore;
import com.rigour.order.application.service.sales.PaymentReadPermission;
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
    @Override public Parties parties(String tenant) {
        var scope = scopes.predicate(PaymentReadPermission.current(), "o.", null);
        var args = new ArrayList<Object>(List.of(tenant));
        args.addAll(scope.args());
        var employees = new LinkedHashSet<Person>();
        var creators = new TreeSet<String>();
        jdbc.query("""
                SELECT DISTINCT p.payment_owner_code,p.payment_owner_name,
                  COALESCE(p.payment_owner_region_code COLLATE utf8mb4_general_ci,snap.region_code,o.region_code) AS region_code,
                  COALESCE(NULLIF(TRIM(o.source_creator_name),''),p.created_by) AS creator
                FROM order_payment_record p
                JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0
                LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN'
                WHERE p.tenant_id=? AND p.deleted=0 AND (
                """ + scope.sql() + ") ORDER BY p.payment_owner_name", rs -> {
            String code = rs.getString("payment_owner_code"), name = rs.getString("payment_owner_name");
            if (code != null && !code.isBlank()) employees.add(new Person(code, name == null ? code : name, rs.getString("region_code")));
            String creator = rs.getString("creator");
            if (creator != null && !creator.isBlank()) creators.add(creator);
        }, args.toArray());
        return new Parties(List.copyOf(employees), List.copyOf(creators));
    }
}

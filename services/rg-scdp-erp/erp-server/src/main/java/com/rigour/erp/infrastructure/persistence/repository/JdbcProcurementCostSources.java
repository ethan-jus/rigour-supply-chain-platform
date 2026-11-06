package com.rigour.erp.infrastructure.persistence.repository;

import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.erp.application.port.out.ProcurementCostSources;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcProcurementCostSources implements ProcurementCostSources {
    private final JdbcTemplate jdbc;
    public JdbcProcurementCostSources(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ProcurementCostSource> source(String tenant, String code) {
        return jdbc.query("""
            SELECT d.procurement_no,d.revision,COALESCE(d.purchase_date,DATE(d.created_time)) purchase_date,
              s.supplier_code,s.supplier_name,s.address,s.contact_name,s.contact_phone,
              w.id warehouse_id,w.warehouse_code,w.warehouse_name,w.region_code,
              d.receiver_name,d.receiver_phone,d.receiving_address,d.freight_bearer_code,d.freight_amount
            FROM erp_procurement_order d
            JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id AND s.deleted=0
              AND (s.supplier_code=d.supplier_code OR (d.supplier_code IS NULL AND s.id=d.supplier_id))
            JOIN erp_inventory_warehouse w ON w.tenant_id COLLATE utf8mb4_general_ci=d.tenant_id AND w.deleted=0
              AND (w.warehouse_code COLLATE utf8mb4_general_ci=d.target_warehouse_code OR (d.target_warehouse_code IS NULL AND w.id=d.target_warehouse_id))
            WHERE d.tenant_id=? AND d.procurement_no=? AND d.deleted=0 AND d.confirmation_status_code='CONFIRMED' AND d.status_code<>'CANCELLED'
            """, (r,n) -> new ProcurementCostSource(r.getString("procurement_no"),r.getLong("revision"),
                r.getDate("purchase_date").toLocalDate(),r.getString("supplier_code"),r.getString("supplier_name"),
                r.getString("address"),r.getString("contact_name"),r.getString("contact_phone"),
                r.getLong("warehouse_id"),r.getString("warehouse_code"),r.getString("warehouse_name"),r.getString("region_code"),
                r.getString("receiver_name"),r.getString("receiver_phone"),r.getString("receiving_address"),
                r.getString("freight_bearer_code"),r.getBigDecimal("freight_amount")),tenant,code).stream().findFirst();
    }
}

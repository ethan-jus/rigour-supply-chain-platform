package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView;
import com.rigour.merchant.application.port.out.CustomerPaymentOwnerStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 使用历史有效时刻，不引用crm_customer当前主责推断历史。 */
@Repository
public class JdbcCustomerPaymentOwnerStore implements CustomerPaymentOwnerStore {
    private final JdbcTemplate jdbc;

    public JdbcCustomerPaymentOwnerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CustomerPaymentOwnerView at(String tenant, long customer, Instant at) {
        if (at == null) return new CustomerPaymentOwnerView(null, null, null, "缺少实际回款时间");
        var rows =
                jdbc.queryForList(
                        "SELECT id,new_employee_code,new_employee_name,effective_at FROM"
                            + " crm_customer_responsibility_history WHERE tenant_id=? AND"
                            + " customer_id=? AND effective_at<=? AND change_type IN"
                            + " ('CREATE','OWNER','OWNER_AND_REGION') ORDER BY effective_at DESC,id"
                            + " DESC LIMIT 2",
                        tenant,
                        customer,
                        LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        if (rows.isEmpty())
            return new CustomerPaymentOwnerView(null, null, null, "该时点没有已确认门店业务员历史");
        var row = rows.getFirst();
        if (rows.size() > 1 && row.get("effective_at").equals(rows.get(1).get("effective_at")))
            return new CustomerPaymentOwnerView(null, null, null, "同一生效时刻存在多条归属变更，需核对");
        return new CustomerPaymentOwnerView(
                (String) row.get("new_employee_code"),
                (String) row.get("new_employee_name"),
                "CRM主责历史#" + row.get("id"),
                null);
    }
}

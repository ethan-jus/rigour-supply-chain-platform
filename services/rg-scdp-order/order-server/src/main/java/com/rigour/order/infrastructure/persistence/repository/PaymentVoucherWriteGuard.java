package com.rigour.order.infrastructure.persistence.repository;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 已核实移除的凭证不能因来源重放或编辑重新关联到同一笔回款。 */
@Component
public class PaymentVoucherWriteGuard {
    private final JdbcTemplate jdbc;
    public PaymentVoucherWriteGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> retainedKeys(String tenantId, long paymentId, List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        var retained = new LinkedHashSet<>(keys);
        retained.removeAll(jdbc.queryForList(
                "SELECT voucher_key FROM order_payment_voucher_exclusion WHERE tenant_id=? AND payment_id=?",
                String.class, tenantId, paymentId));
        return List.copyOf(retained);
    }
}

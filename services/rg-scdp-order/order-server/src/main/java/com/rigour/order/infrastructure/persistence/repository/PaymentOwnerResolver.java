package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.shared.context.AuthorizationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 独立于来源经办人的业绩归属；只补空值，已确认快照不会随客户变更漂移。 */
@Component
public class PaymentOwnerResolver {
    private final JdbcTemplate jdbc;
    private final OrderAttributionClient owners;
    public PaymentOwnerResolver(JdbcTemplate jdbc, OrderAttributionClient owners) {
        this.jdbc=jdbc; this.owners=owners;
    }
    public void requireStableBasis(String tenant,long id,Long customer,java.time.Instant at) {
        var rows=jdbc.queryForList("SELECT customer_id,payment_time FROM order_payment_record WHERE tenant_id=? AND id=? AND payment_owner_evidence IS NOT NULL AND deleted=0",tenant,id);
        if (rows.isEmpty()) return;
        var row=rows.getFirst();
        var previous=row.get("payment_time")==null ? null : instant(row.get("payment_time"));
        Long previousCustomer=row.get("customer_id")==null ? null : ((Number)row.get("customer_id")).longValue();
        if (!java.util.Objects.equals(customer,previousCustomer) || !java.util.Objects.equals(at,previous))
            throw new com.rigour.shared.core.exception.BusinessException(com.rigour.shared.core.api.ErrorCode.CONFLICT,"回款归属已确认，客户或实际回款时间变更需先核实，不能静默覆盖",java.util.List.of());
    }
    public void resolve(String tenant, long id) {
        var rows=jdbc.queryForList("SELECT customer_id,payment_time FROM order_payment_record WHERE tenant_id=? AND id=? AND deleted=0 AND payment_owner_evidence IS NULL",tenant,id);
        if(rows.isEmpty()) return;
        var row=rows.getFirst();
        if(row.get("customer_id")==null || row.get("payment_time")==null) return;
        try {
            var at=instant(row.get("payment_time"));
            var owner=owners.paymentOwner(AuthorizationContext.requireCurrent(),((Number)row.get("customer_id")).longValue(),at);
            if(owner==null || !owner.usable()) return;
            jdbc.update("UPDATE order_payment_record SET payment_owner_code=?,payment_owner_name=?,payment_owner_evidence=?,payment_owner_department_id=?,payment_owner_department_name=?,payment_owner_region_code=?,updated_time=?,revision=revision+1 WHERE tenant_id=? AND id=? AND customer_id=? AND payment_time=? AND payment_owner_evidence IS NULL AND deleted=0",
                owner.employeeCode(),owner.employeeName(),owner.evidence(),owner.departmentId(),owner.departmentName(),owner.regionCode(),java.time.LocalDateTime.ofInstant(java.time.Instant.now(),java.time.ZoneOffset.UTC),tenant,id,row.get("customer_id"),row.get("payment_time"));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("回款归属暂无法核实，保留待确认 paymentId={} errorType={}",id,failure.getClass().getSimpleName());
        }
    }
    private static java.time.Instant instant(Object value) {
        if(value instanceof java.time.LocalDateTime date) return date.toInstant(java.time.ZoneOffset.UTC);
        return ((java.sql.Timestamp)value).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

}

package com.rigour.order.application.service.sales;

import com.rigour.order.infrastructure.persistence.repository.OrderDataScope;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.RequestValidationException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class ReceivablePlanService {
    public record Line(LocalDate dueDate, BigDecimal amount) {}

    public record Command(
            int version, LocalDate effectiveDate, String evidence, List<Line> lines) {}

    public record Plan(int version, LocalDate effectiveDate, String evidence, List<Line> lines) {}

    private final JdbcTemplate jdbc;
    private final OrderDataScope scope;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String WRITE = "order:receivable-plan:write";

    public ReceivablePlanService(JdbcTemplate jdbc, OrderDataScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private CallerIdentity caller(String action) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope()))
            throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        return actor;
    }

    @Transactional(readOnly = true)
    public Plan read(long id) {
        var tenant = caller("order:read").tenantId().toString();
        scope.requireOrder(tenant, id, "order:read");
        return latest(tenant, id);
    }

    private Plan latest(String tenant, long id) {
        var rows =
                jdbc.queryForList(
                        """
SELECT plan_version,effective_at,evidence,due_date,amount FROM order_receivable_installment
WHERE tenant_id=? AND order_id=? AND plan_version=(SELECT MAX(plan_version) FROM order_receivable_installment
WHERE tenant_id=? AND order_id=?) ORDER BY installment_no
""",
                        tenant,
                        id,
                        tenant,
                        id);
        if (rows.isEmpty()) return new Plan(0, null, null, List.of());
        var first = rows.get(0);
        var lines =
                rows.stream()
                        .map(
                                r ->
                                        new Line(
                                                ((java.sql.Date) r.get("due_date")).toLocalDate(),
                                                (BigDecimal) r.get("amount")))
                        .toList();
        var at = (Object) first.get("effective_at");
        LocalDateTime utc = at instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) at;
        return new Plan(
                ((Number) first.get("plan_version")).intValue(),
                utc.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONE).toLocalDate(),
                (String) first.get("evidence"),
                lines);
    }

    @Transactional
    public Plan save(long id, Command cmd) {
        var actor = caller(WRITE);
        String tenant = actor.tenantId().toString();
        if (cmd == null
                || cmd.version() < 0
                || cmd.effectiveDate() == null
                || cmd.effectiveDate().isAfter(LocalDate.now(ZONE))
                || cmd.evidence() == null
                || cmd.evidence().isBlank()
                || cmd.evidence().trim().length() > 500
                || cmd.lines() == null
                || cmd.lines().isEmpty()
                || cmd.lines().size() > 120)
            throw new RequestValidationException("请填写账期依据、生效日和完整分期");
        scope.requireOrder(tenant, id, WRITE);
        var order =
                jdbc.queryForMap(
                        "SELECT"
                            + " payable_amount,order_status_code,DATE(TIMESTAMPADD(HOUR,8,order_date))"
                            + " order_day FROM order_sales_order WHERE tenant_id=? AND id=? AND"
                            + " deleted=0 FOR UPDATE",
                        tenant,
                        id);
        if (Set.of("DRAFT", "CANCELLED", "DELETED").contains(order.get("order_status_code"))
                || order.get("order_day") == null
                || cmd.effectiveDate()
                        .isBefore(((java.sql.Date) order.get("order_day")).toLocalDate()))
            throw new RequestValidationException("账期生效日不能早于有效订单");
        Plan saved = latest(tenant, id);
        BigDecimal sum = BigDecimal.ZERO;
        LocalDate previous = null;
        for (var line : cmd.lines()) {
            if (line == null
                    || line.dueDate() == null
                    || line.amount() == null
                    || line.amount().signum() <= 0
                    || line.amount().scale() > 6
                    || (previous != null && !line.dueDate().isAfter(previous)))
                throw new RequestValidationException("分期金额应大于零，到期日应递增");
            previous = line.dueDate();
            sum = sum.add(line.amount());
        }
        if (sum.compareTo((BigDecimal) order.get("payable_amount")) != 0)
            throw new RequestValidationException("分期金额合计必须等于订单应收原额");
        if (saved.version() != cmd.version()) {
            boolean same =
                    saved.version() == cmd.version() + 1
                            && saved.effectiveDate().equals(cmd.effectiveDate())
                            && saved.evidence().equals(cmd.evidence().trim())
                            && saved.lines().size() == cmd.lines().size();
            for (int i = 0; same && i < saved.lines().size(); i++)
                same =
                        saved.lines().get(i).dueDate().equals(cmd.lines().get(i).dueDate())
                                && saved.lines()
                                                .get(i)
                                                .amount()
                                                .compareTo(cmd.lines().get(i).amount())
                                        == 0;
            if (same) return saved;
            throw new BusinessException(ErrorCode.CONFLICT, "合同账期已经更新，请重新读取", List.of());
        }
        if (saved.effectiveDate() != null && cmd.effectiveDate().isBefore(saved.effectiveDate()))
            throw new RequestValidationException("新版本生效日不能早于上一版");
        var effective =
                LocalDateTime.ofInstant(
                        cmd.effectiveDate().atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
        for (int i = 0; i < cmd.lines().size(); i++) {
            var line = cmd.lines().get(i);
            jdbc.update(
                    """
INSERT INTO order_receivable_installment(tenant_id,order_id,plan_version,installment_no,effective_at,due_date,amount,evidence,created_by)
VALUES(?,?,?,?,?,?,?,?,?)
""",
                    tenant,
                    id,
                    saved.version() + 1,
                    i + 1,
                    effective,
                    line.dueDate(),
                    line.amount(),
                    cmd.evidence().trim(),
                    actor.principalId().toString());
        }
        return latest(tenant, id);
    }
}

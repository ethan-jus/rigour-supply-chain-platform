package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.OrderAttributionReview.*;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderAttributionReviewStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** 只改变经复核的归属，不修改交易金额、订单状态或库存；前后证据永久留存。 */
@Repository
public class JdbcOrderAttributionReviewStore implements OrderAttributionReviewStore {
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    private final HrEmployeeDisplayClient employees;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public JdbcOrderAttributionReviewStore(
            JdbcTemplate jdbc, OrderDataScope scopes, HrEmployeeDisplayClient employees) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.employees = employees;
    }

    private CallerIdentity actor(String action) {
        var a = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(a.principalScope()) || a.tenantId() == null)
            throw new AuthorizationDeniedException(action);
        var p = scopes.policy(action);
        if (p == null) throw conflict("请在供应链授权正式启用后进行历史归属复核");
        return a;
    }

    @Transactional(readOnly = true)
    public Context context(long id) {
        var a = actor("order:attribution:read");
        String t = a.tenantId().toString();
        scopes.requireOrder(t, id, "order:attribution:read");
        return current(t, id, false);
    }

    private Map<String, Object> header(String t, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " id,order_no,source_system_code,source_order_no,revision,order_status_code,owner_employee_code,owner_employee_name_snapshot,region_code"
                            + " FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        t,
                        id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在", List.of());
        return rows.getFirst();
    }

    private Map<String, Object> snapshot(String t, long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " state,employee_code,employee_name,department_id,department_name,department_path,region_code,region_path,source_version,customer_revision,employee_revision,organization_version,resolved_at,frozen_at,revision"
                            + " FROM order_attribution_snapshot WHERE tenant_id=? AND order_id=?",
                        t,
                        id);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Context current(String t, long id, boolean lock) {
        var h = header(t, id, lock);
        var s = snapshot(t, id);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("order", h);
        evidence.put("snapshot", s);
        var changes =
                jdbc
                        .queryForList(
                                "SELECT * FROM order_attribution_adjustment WHERE tenant_id=? AND"
                                    + " order_id=? ORDER BY proposed_at DESC,id",
                                t,
                                id)
                        .stream()
                        .map(this::view)
                        .toList();
        return new Context(
                id,
                (String) h.get("order_no"),
                (String) h.get("source_system_code"),
                (String) h.get("source_order_no"),
                ((Number) h.get("revision")).intValue(),
                s.isEmpty() ? 0 : ((Number) s.get("revision")).longValue(),
                evidence,
                changes);
    }

    @Transactional
    public Adjustment propose(long id, Propose c) {
        var a = actor("order:attribution:propose");
        String t = a.tenantId().toString();
        var old = current(t, id, true);
        scopes.requireOrder(t, id, "order:attribution:propose");
        if (c == null
                || !Objects.equals(c.orderRevision(), old.orderRevision())
                || !Objects.equals(c.snapshotRevision(), old.snapshotRevision()))
            throw conflict("订单或归属已变化，请刷新后重新提交复核");
        var p = normalize(c.proposed());
        String ref = text(c.evidenceRef(), 1000, "来源证据位置"),
                evidence = text(c.evidenceText(), 4000, "历史归属证据"),
                reason = text(c.reason(), 1000, "调整原因");
        requireEmployee(a, p.employeeCode());
        var h = header(t, id, false);
        if ("DRAFT".equals(h.get("order_status_code")) && h.get("source_system_code") == null)
            throw conflict("普通草稿应通过客户主责重新解析归属");
        String review = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                    + " order_attribution_adjustment(tenant_id,id,order_id,expected_order_revision,expected_snapshot_revision,before_json,proposed_json,source_system_code,source_order_no,evidence_ref,evidence_text,reason,proposed_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t,
                review,
                id,
                old.orderRevision(),
                old.snapshotRevision(),
                JSON.writeValueAsString(old.current()),
                JSON.writeValueAsString(p),
                old.sourceSystemCode(),
                old.sourceOrderNo(),
                ref,
                evidence,
                reason,
                a.principalId().toString());
        return find(t, id, review, false);
    }

    @Transactional
    public Adjustment decide(long id, String reviewId, Review c) {
        var a = actor("order:attribution:approve");
        String t = a.tenantId().toString();
        var old = current(t, id, true);
        scopes.requireOrder(t, id, "order:attribution:approve");
        var item = find(t, id, reviewId, true);
        if (!"PENDING".equals(item.status())) throw conflict("此复核已处理");
        if (c == null) throw new IllegalArgumentException("复核决定不能为空");
        String reason = text(c.reason(), 1000, "复核意见");
        if (a.principalId().toString().equals(item.proposedBy())) throw conflict("请由另一位有权限的用户复核");
        if (c.approve()) {
            if (old.orderRevision() != item.expectedOrderRevision()
                    || old.snapshotRevision() != item.expectedSnapshotRevision())
                throw conflict("订单或归属已变化，此申请不能生效，请重新提交");
            var p = item.proposed();
            requireEmployee(a, p.employeeCode());
            jdbc.update(
                    "INSERT INTO"
                        + " order_attribution_snapshot(tenant_id,order_id,state,employee_code,employee_name,department_id,department_name,department_path,region_code,region_path,source_version,customer_revision,employee_revision,organization_version,resolved_at,frozen_at)"
                        + " VALUES(?,?,'FROZEN',?,?,?,?,?,?,?,?,0,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                        + " ON DUPLICATE KEY UPDATE"
                        + " state='FROZEN',employee_code=VALUES(employee_code),employee_name=VALUES(employee_name),department_id=VALUES(department_id),department_name=VALUES(department_name),department_path=VALUES(department_path),region_code=VALUES(region_code),region_path=VALUES(region_path),source_version=VALUES(source_version),resolved_at=VALUES(resolved_at),frozen_at=COALESCE(frozen_at,VALUES(frozen_at)),revision=revision+1",
                    t,
                    id,
                    p.employeeCode(),
                    p.employeeName(),
                    p.departmentId(),
                    p.departmentName(),
                    JSON.writeValueAsString(p.departmentPath()),
                    p.regionCode(),
                    JSON.writeValueAsString(p.regionPath()),
                    "REVIEW:" + item.id());
            jdbc.update(
                    "UPDATE order_sales_order SET"
                        + " owner_employee_code=?,owner_employee_name_snapshot=?,owner_sales_user_id=NULL,owner_sales_name=NULL,region_code=?,revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6)"
                        + " WHERE tenant_id=? AND id=? AND revision=?",
                    p.employeeCode(),
                    p.employeeName(),
                    p.regionCode(),
                    a.principalId().toString(),
                    t,
                    id,
                    old.orderRevision());
            scopes.requireOrder(t, id, "order:attribution:approve"); // 调整后的目标归属也须在复核人授权范围内，失败整体回滚。
        }
        jdbc.update(
                "UPDATE order_attribution_adjustment SET"
                    + " status=?,reviewed_by=?,reviewed_at=UTC_TIMESTAMP(6),review_reason=? WHERE"
                    + " tenant_id=? AND id=? AND status='PENDING'",
                c.approve() ? "APPLIED" : "REJECTED",
                a.principalId().toString(),
                reason,
                t,
                reviewId);
        return find(t, id, reviewId, false);
    }

    private void requireEmployee(CallerIdentity a, String code) {
        UUID service =
                UUID.nameUUIDFromBytes(
                        "service:rigour-order-center-service"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var caller =
                new CallerIdentity(
                        "SERVICE",
                        service,
                        a.tenantId(),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:employee:read"));
        if (employees.resolve(caller, Set.of(code)).stream()
                .noneMatch(e -> code.equals(e.employeeCode())))
            throw conflict("员工编码未找到对应 HR 档案，请先核对映射；历史离职员工仍可使用");
    }

    private Snapshot normalize(Snapshot p) {
        if (p == null) throw new IllegalArgumentException("归属不能为空");
        String employee = text(p.employeeCode(), 50, "员工编码"),
                name = text(p.employeeName(), 128, "历史员工姓名");
        var dept = p.departmentPath() == null ? List.<Long>of() : List.copyOf(p.departmentPath());
        var region = p.regionPath() == null ? List.<String>of() : List.copyOf(p.regionPath());
        if (dept.size() > 32
                || region.size() > 32
                || new HashSet<>(dept).size() != dept.size()
                || new HashSet<>(region).size() != region.size())
            throw new IllegalArgumentException("历史层级路径重复或超过32层");
        String dn = null, rc = null;
        if (p.departmentId() == null) {
            if (!dept.isEmpty()) throw new IllegalArgumentException("未知部门不能填写部门路径");
        } else {
            dn = text(p.departmentName(), 160, "历史部门名称");
            if (p.departmentId() <= 0
                    || dept.isEmpty()
                    || !p.departmentId().equals(dept.getLast())
                    || dept.stream().anyMatch(x -> x <= 0))
                throw new IllegalArgumentException("历史部门路径需由根到本部门且以本部门结束");
        }
        if (p.regionCode() == null || p.regionCode().isBlank()) {
            if (!region.isEmpty()) throw new IllegalArgumentException("未知地区不能填写地区路径");
        } else {
            rc = text(p.regionCode(), 128, "历史地区编码");
            if (region.isEmpty()
                    || !rc.equals(region.getLast())
                    || region.stream().anyMatch(x -> x.isBlank() || x.length() > 128))
                throw new IllegalArgumentException("历史地区路径需由根到本地区且以本地区结束");
        }
        return new Snapshot(employee, name, p.departmentId(), dn, dept, rc, region);
    }

    private Adjustment find(String t, long order, String id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM order_attribution_adjustment WHERE tenant_id=? AND"
                            + " order_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        t,
                        order,
                        id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "归属复核不存在", List.of());
        return view(rows.getFirst());
    }

    private Adjustment view(Map<String, Object> r) {
        return new Adjustment(
                (String) r.get("id"),
                (String) r.get("status"),
                ((Number) r.get("expected_order_revision")).intValue(),
                ((Number) r.get("expected_snapshot_revision")).longValue(),
                JSON.readValue(
                        r.get("before_json").toString(),
                        new TypeReference<Map<String, Object>>() {}),
                JSON.readValue(r.get("proposed_json").toString(), Snapshot.class),
                (String) r.get("evidence_ref"),
                (String) r.get("evidence_text"),
                (String) r.get("reason"),
                (String) r.get("proposed_by"),
                str(r.get("proposed_at")),
                (String) r.get("reviewed_by"),
                str(r.get("reviewed_at")),
                (String) r.get("review_reason"));
    }

    private static String str(Object x) {
        return x == null ? null : x.toString();
    }

    private static String text(String x, int max, String label) {
        if (x == null || x.strip().isEmpty() || x.strip().length() > max)
            throw new IllegalArgumentException(label + "不能为空且最多" + max + "字");
        return x.strip();
    }

    private static BusinessException conflict(String s) {
        return new BusinessException(ErrorCode.CONFLICT, s, List.of());
    }
}

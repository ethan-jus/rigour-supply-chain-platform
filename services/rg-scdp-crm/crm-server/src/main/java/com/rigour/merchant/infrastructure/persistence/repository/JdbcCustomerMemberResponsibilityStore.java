package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.CustomerMemberResponsibilityApi.*;
import com.rigour.merchant.api.v1.CustomerResponsibilityApi.Change;
import com.rigour.merchant.application.port.out.CustomerMemberResponsibilityStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 预览令牌限定操作者、目标和客户版本；整批核验后在 CRM 单事务内提交。 */
@Repository
public class JdbcCustomerMemberResponsibilityStore implements CustomerMemberResponsibilityStore {
    private static final String ACTION = "crm:customer:assign-owner";
    private final JdbcTemplate jdbc;
    private final CrmDataScope scopes;
    private final CustomerAssignmentScope targets;
    private final JdbcCustomerResponsibilityStore responsibility;
    private final CrmResponsibilityWriter writer;

    public JdbcCustomerMemberResponsibilityStore(
            JdbcTemplate jdbc,
            CrmDataScope scopes,
            CustomerAssignmentScope targets,
            JdbcCustomerResponsibilityStore responsibility,
            CrmResponsibilityWriter writer) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.targets = targets;
        this.responsibility = responsibility;
        this.writer = writer;
    }

    public Page customers(
            String tenant,
            UUID userId,
            String mode,
            String keyword,
            String customerType,
            String regionCode,
            String status,
            int page,
            int size,
            String action) {
        if (!Set.of("OWNED", "CANDIDATES").contains(mode)
                || page < 1
                || size < 1
                || size > 100
                || page > 1000000) throw invalid("客户查询参数无效");
        boolean candidates = "CANDIDATES".equals(mode);
        var target = targets.member(tenant, userId, candidates);
        var actor = scopes.predicate(action, "c.");
        var conditions = new ArrayList<>(List.of("c.tenant_id=?", "c.deleted=0", actor.sql()));
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(actor.args());
        if (candidates) {
            var ceiling = targets.predicate(tenant, target, "c.region_code");
            var proposed = scopes.proposedPredicate(ACTION, "c.", target.employeeCode());
            conditions.add(ceiling.sql());
            args.addAll(ceiling.args());
            conditions.add(proposed.sql());
            args.addAll(proposed.args());
            conditions.add("(c.owner_employee_code IS NULL OR c.owner_employee_code<>?)");
            args.add(target.employeeCode());
        } else {
            conditions.add("c.owner_employee_code=?");
            args.add(target.employeeCode());
        }
        var filters = filters(String.join(" AND ", conditions), List.copyOf(args));
        if (clean(keyword) != null) {
            if (keyword.length() > 200) throw invalid("搜索内容过长");
            conditions.add(
                    "(c.customer_name LIKE ? OR c.customer_code LIKE ? OR c.contact_phone LIKE ? OR"
                        + " c.contact_name LIKE ?)");
            String query = "%" + keyword.trim() + "%";
            args.addAll(List.of(query, query, query, query));
        }
        filter(conditions, args, "c.customer_type_code", customerType);
        filter(conditions, args, "c.region_code", regionCode);
        filter(conditions, args, "c.status_code", status);
        String where = String.join(" AND ", conditions);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crm_customer c WHERE " + where,
                        Long.class,
                        args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var items =
                jdbc.query(
                        "SELECT"
                            + " c.id,c.customer_name,c.customer_code,c.customer_type_code,c.status_code,c.region_code,(SELECT"
                            + " a.area_name FROM crm_customer_area a WHERE"
                            + " a.tenant_id=UUID_TO_BIN(c.tenant_id) AND a.area_code=c.region_code"
                            + " AND a.deleted=0 LIMIT 1) region_name,(SELECT t.type_name FROM"
                            + " crm_customer_type t WHERE t.tenant_id=UUID_TO_BIN(c.tenant_id) AND"
                            + " t.type_code=c.customer_type_code AND t.deleted=0 LIMIT 1)"
                            + " customer_type_name,c.owner_employee_code,c.owner_employee_name_snapshot,c.revision"
                            + " FROM crm_customer c WHERE "
                                + where
                                + " ORDER BY c.id LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new Customer(
                                        rs.getLong("id"),
                                        rs.getString("customer_name"),
                                        rs.getString("customer_code"),
                                        rs.getString("customer_type_code"),
                                        rs.getString("customer_type_name"),
                                        rs.getString("status_code"),
                                        rs.getString("region_code"),
                                        rs.getString("region_name"),
                                        rs.getString("owner_employee_code"),
                                        rs.getString("owner_employee_name_snapshot"),
                                        rs.getLong("revision")),
                        args.toArray());
        return new Page(items, total, page, size, view(target), filters);
    }

    @Transactional
    public Preview preview(String tenant, UUID userId, PreviewCommand command, String actor) {
        validate(command);
        var target = targets.member(tenant, userId, "ASSIGN".equals(command.operation()));
        var selections =
                command.customers().stream()
                        .sorted(Comparator.comparingLong(Selection::customerId))
                        .toList();
        var items = new ArrayList<PreviewItem>();
        for (var selection : selections) {
            var row = customer(tenant, selection.customerId(), false);
            validateSelection(tenant, target, command.operation(), selection, row);
            items.add(
                    new PreviewItem(
                            selection.customerId(),
                            text(row, "customer_name"),
                            text(row, "region_code"),
                            regionName(tenant, text(row, "region_code")),
                            text(row, "owner_employee_code"),
                            text(row, "owner_employee_name_snapshot"),
                            "ASSIGN".equals(command.operation()) ? target.employeeCode() : null,
                            "ASSIGN".equals(command.operation()) ? target.employeeName() : null,
                            selection.revision()));
        }
        String token = UUID.randomUUID().toString();
        Instant expires = Instant.now().plusSeconds(600);
        jdbc.update(
                "INSERT INTO"
                    + " crm_member_responsibility_preview(token,tenant_id,actor_id,target_user_id,target_employee_code,target_fingerprint,operation,reason,expires_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                token,
                tenant,
                actor,
                userId.toString(),
                target.employeeCode(),
                CustomerAssignmentScope.fingerprint(target),
                command.operation(),
                command.reason().trim(),
                Timestamp.from(expires));
        for (var selection : selections)
            jdbc.update(
                    "INSERT INTO"
                        + " crm_member_responsibility_preview_item(tenant_id,token,customer_id,customer_revision)"
                        + " VALUES(?,?,?,?)",
                    tenant,
                    token,
                    selection.customerId(),
                    selection.revision());
        // 清理已过期凭证；外键同时删除其短期版本清单。
        jdbc.update(
                "DELETE FROM crm_member_responsibility_preview WHERE tenant_id=? AND"
                    + " expires_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY)",
                tenant);
        return new Preview(
                token,
                expires,
                view(target),
                command.operation(),
                List.copyOf(items),
                items.size());
    }

    @Transactional
    public Applied apply(String tenant, UUID userId, ApplyCommand command, String actor) {
        if (command == null || clean(command.previewToken()) == null) throw invalid("请先预览客户归属变更");
        var previews =
                jdbc.queryForList(
                        "SELECT * FROM crm_member_responsibility_preview WHERE token=? AND"
                            + " tenant_id=? AND actor_id=? AND target_user_id=? AND"
                            + " expires_at>UTC_TIMESTAMP(6) FOR UPDATE",
                        command.previewToken(),
                        tenant,
                        actor,
                        userId.toString());
        if (previews.size() != 1) throw conflict("预览不存在，请重新预览");
        var preview = previews.getFirst();
        if (preview.get("consumed_at") != null) throw conflict("此批变更已提交，请刷新客户列表");
        String operation = text(preview, "operation");
        var target = targets.member(tenant, userId, "ASSIGN".equals(operation));
        if (!CustomerAssignmentScope.fingerprint(target)
                .equals(text(preview, "target_fingerprint"))) throw conflict("目标用户关联或授权已变化，请重新预览");
        var selections =
                jdbc.query(
                        "SELECT customer_id,customer_revision FROM"
                            + " crm_member_responsibility_preview_item WHERE tenant_id=? AND"
                            + " token=? ORDER BY customer_id",
                        (rs, n) -> new Selection(rs.getLong(1), rs.getLong(2)),
                        tenant,
                        command.previewToken());
        if (selections.isEmpty() || selections.size() > 100) throw conflict("预览客户清单无效，请重新预览");
        var identity =
                "ASSIGN".equals(operation)
                        ? writer.requireOwner(tenant, target.employeeCode())
                        : null;
        var locked = new LinkedHashMap<Long, Map<String, Object>>();
        for (var selection : selections) {
            var row = customer(tenant, selection.customerId(), true);
            validateSelection(tenant, target, operation, selection, row);
            locked.put(selection.customerId(), row);
        }
        for (var selection : selections) {
            var row = locked.get(selection.customerId());
            responsibility.applyInBatch(
                    tenant,
                    selection.customerId(),
                    new Change(
                            "ASSIGN".equals(operation) ? target.employeeCode() : null,
                            text(row, "region_code"),
                            selection.revision(),
                            text(preview, "reason")),
                    actor,
                    row,
                    identity);
        }
        var latest = targets.member(tenant, userId, "ASSIGN".equals(operation));
        if (!CustomerAssignmentScope.fingerprint(latest)
                .equals(CustomerAssignmentScope.fingerprint(target)))
            throw conflict("目标用户授权在提交过程中变化，请重新预览");
        jdbc.update(
                "UPDATE crm_member_responsibility_preview SET consumed_at=UTC_TIMESTAMP(6) WHERE"
                    + " tenant_id=? AND token=? AND consumed_at IS NULL",
                tenant,
                command.previewToken());
        return new Applied(selections.size());
    }

    private Map<String, Object> customer(String tenant, long id, boolean lock) {
        scopes.requireCustomer(tenant, id, ACTION);
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " id,customer_name,region_code,owner_employee_code,owner_employee_name_snapshot,revision"
                            + " FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        tenant,
                        id);
        if (rows.size() != 1) throw conflict("客户不存在或已删除，请重新预览");
        return rows.getFirst();
    }

    private void validateSelection(
            String tenant,
            CustomerAssignmentTargetView target,
            String operation,
            Selection selection,
            Map<String, Object> row) {
        if (((Number) row.get("revision")).longValue() != selection.revision())
            throw conflict("客户已被修改，请重新预览");
        String owner = text(row, "owner_employee_code"), region = text(row, "region_code");
        if ("RELEASE".equals(operation)) {
            if (!target.employeeCode().equals(owner)) throw conflict("只能解除当前用户负责的客户，请刷新");
            scopes.requireProposed(tenant, null, region, ACTION);
        } else {
            if (target.employeeCode().equals(owner)) throw conflict("客户已由目标员工负责，请刷新");
            targets.requireRegion(tenant, target, region);
            scopes.requireProposed(tenant, target.employeeCode(), region, ACTION);
        }
    }

    private Filters filters(String where, List<Object> args) {
        var types =
                jdbc.query(
                        "SELECT DISTINCT c.customer_type_code"
                            + " code,COALESCE(t.type_name,c.customer_type_code) name FROM"
                            + " crm_customer c LEFT JOIN crm_customer_type t ON"
                            + " t.tenant_id=UUID_TO_BIN(c.tenant_id) AND"
                            + " t.type_code=c.customer_type_code AND t.deleted=0 WHERE "
                                + where
                                + " AND c.customer_type_code IS NOT NULL ORDER BY name LIMIT 500",
                        (rs, n) -> new Option(rs.getString("code"), rs.getString("name")),
                        args.toArray());
        var regions =
                jdbc.query(
                        "SELECT DISTINCT c.region_code code,COALESCE(a.area_name,c.region_code)"
                            + " name FROM crm_customer c LEFT JOIN crm_customer_area a ON"
                            + " a.tenant_id=UUID_TO_BIN(c.tenant_id) AND a.area_code=c.region_code"
                            + " AND a.deleted=0 WHERE "
                                + where
                                + " AND c.region_code IS NOT NULL ORDER BY name LIMIT 500",
                        (rs, n) -> new Option(rs.getString("code"), rs.getString("name")),
                        args.toArray());
        var statuses =
                jdbc.query(
                        "SELECT DISTINCT c.status_code FROM crm_customer c WHERE "
                                + where
                                + " ORDER BY c.status_code LIMIT 500",
                        (rs, n) -> {
                            String code = rs.getString(1);
                            return new Option(code, statusName(code));
                        },
                        args.toArray());
        return new Filters(types, regions, statuses);
    }

    private static String statusName(String code) {
        return switch (code) {
            case "ACTIVE" -> "启用";
            case "DISABLED", "INACTIVE" -> "停用";
            case "PENDING" -> "待审核";
            case "PENDING_ACTIVATION" -> "待激活";
            default -> code;
        };
    }

    private String regionName(String tenant, String code) {
        return jdbc
                .query(
                        "SELECT area_name FROM crm_customer_area WHERE tenant_id=UUID_TO_BIN(?) AND"
                            + " area_code=? AND deleted=0",
                        (rs, n) -> rs.getString(1),
                        tenant,
                        code)
                .stream()
                .findFirst()
                .orElse(code);
    }

    private static void validate(PreviewCommand command) {
        if (command == null
                || !Set.of("ASSIGN", "RELEASE").contains(Objects.toString(command.operation(), "")))
            throw invalid("请选择分配或解除操作");
        if (command.customers() == null
                || command.customers().isEmpty()
                || command.customers().size() > 100) throw invalid("每次请选择1至100个客户");
        if (command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500) throw invalid("请填写不超过500字的变更原因");
        Set<Long> ids = new HashSet<>();
        for (var selection : command.customers())
            if (selection == null
                    || selection.customerId() <= 0
                    || selection.revision() < 1
                    || !ids.add(selection.customerId())) throw invalid("客户清单有重复或无效数据");
    }

    private static void filter(
            List<String> conditions, List<Object> args, String column, String value) {
        if (clean(value) != null) {
            conditions.add(column + "=?");
            args.add(value.trim());
        }
    }

    private static Target view(CustomerAssignmentTargetView target) {
        return new Target(target.userId(), target.employeeCode(), target.employeeName());
    }

    private static String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }
}

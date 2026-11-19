package com.rigour.analytics.application.service;

import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.client.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AssignedFollowups {
    public static final String READ = "sales:followup:self:read",
            RESPOND = "sales:followup:self:respond";
    private static final String ASSIGNED =
            "tenant_id=? AND assignee=? AND kind IN ('CUSTOMER','COLLECTION')";
    private final JdbcTemplate jdbc;
    private final SupplyAuthorizationClient iam;
    private final Clock clock;

    public AssignedFollowups(
            JdbcTemplate jdbc, SupplyAuthorizationClient iam, Clock analyticsClock) {
        this.jdbc = jdbc;
        this.iam = iam;
        this.clock = analyticsClock;
    }

    public record Task(
            String id,
            String kind,
            String subject,
            Instant dueAt,
            String status,
            String note,
            int revision) {}

    public record Page(List<Task> items, long total) {}

    public record Reply(String status, String note, int revision) {}

    private record Owner(String tenant, String employee, String actor) {}

    private Owner owner(String action) {
        var actor = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (!"TENANT".equals(actor.principalScope())
                || actor.tenantId() == null
                || actor.userId() == null) throw new AuthorizationDeniedException(action);
        var policy =
                SupplyAuthorizationContext.current().isPresent()
                        ? SupplyAuthorizationContext.requireAction(action)
                        : iam.authorization(actor, action);
        if (policy == null
                || !"ACTIVE".equals(policy.mode())
                || !policy.functionAllowed()
                || !action.equals(policy.action())
                || !actor.tenantId().equals(policy.tenantId())
                || !actor.userId().equals(policy.userId())
                || policy.employeeCode() == null
                || policy.employeeCode().isBlank()) throw new AuthorizationDeniedException(action);
        return new Owner(
                actor.tenantId().toString(), policy.employeeCode(), actor.userId().toString());
    }

    @Transactional(readOnly = true)
    public Page list(boolean closed, int page, int size) {
        var me = owner(READ);
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid("分页条件无效");
        String where =
                ASSIGNED
                        + (closed
                                ? " AND status IN ('RESOLVED','DISMISSED')"
                                : " AND status IN ('OPEN','IN_PROGRESS')");
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bi_operating_action WHERE " + where,
                        Long.class,
                        me.tenant(),
                        me.employee());
        var rows =
                jdbc.query(
                        "SELECT id,kind,business_label,due_at,status,note,revision FROM"
                            + " bi_operating_action WHERE "
                                + where
                                + " ORDER BY due_at,id LIMIT ? OFFSET ?",
                        (r, n) ->
                                new Task(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getTimestamp(4).toInstant(),
                                        r.getString(5),
                                        r.getString(6),
                                        r.getInt(7)),
                        me.tenant(),
                        me.employee(),
                        size,
                        (page - 1) * size);
        return new Page(rows, total);
    }

    @Transactional
    public void respond(String id, Reply reply) {
        AuthorizationContext.requirePermission(READ);
        var me = owner(RESPOND);
        if (reply == null
                || reply.revision() < 1
                || reply.note() == null
                || reply.note().isBlank()
                || reply.note().strip().length() > 2000
                || !("IN_PROGRESS".equals(reply.status()) || "RESOLVED".equals(reply.status())))
            throw invalid("请填写办理结果并选择有效状态");
        var now = Timestamp.from(clock.instant());
        String expected = "IN_PROGRESS".equals(reply.status()) ? "OPEN" : "IN_PROGRESS";
        int changed =
                jdbc.update(
                        "UPDATE bi_operating_action SET"
                            + " status=?,note=?,revision=revision+1,updated_at=? WHERE "
                                + ASSIGNED
                                + " AND id=? AND revision=? AND status=?",
                        reply.status(),
                        reply.note().strip(),
                        now,
                        me.tenant(),
                        me.employee(),
                        id,
                        reply.revision(),
                        expected);
        if (changed != 1) throw OperatingWorkspaceService.conflict();
        jdbc.update(
                """
INSERT INTO bi_operating_action_event (id,tenant_id,action_id,revision,previous_status,status,previous_assignee,assignee,
      previous_due_at,due_at,note,actor,occurred_at,promised_on,disputed)
SELECT ?,tenant_id,id,revision,?,status,assignee,assignee,due_at,due_at,note,?,?,promised_on,disputed FROM bi_operating_action WHERE
"""
                        + ASSIGNED
                        + " AND id=?",
                UUID.randomUUID().toString(),
                expected,
                me.actor(),
                now,
                me.tenant(),
                me.employee(),
                id);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}

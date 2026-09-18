package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.*;

/** 与订单保存共用事务。草稿解析当前归属；提交后快照不可被同步、调岗或客户转交改写。 */
@Component
public final class OrderAttributionWriter {
    private final JdbcTemplate jdbc;
    private final OrderAttributionClient client;

    public OrderAttributionWriter(JdbcTemplate jdbc, OrderAttributionClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public void prepare(String tenant, long id, boolean submitting, boolean keepDraftOnMissing) {
        var row =
                jdbc.queryForMap(
                        "SELECT customer_id,source_system_code FROM order_sales_order WHERE"
                            + " tenant_id=? AND id=? AND deleted=0 FOR UPDATE",
                        tenant,
                        id);
        // 历史来源记录保留来源证据，由单独核对迁移建立历史快照，不能用今天的客户归属补造。
        if (row.get("source_system_code") != null
                && !row.get("source_system_code").toString().isBlank()) return;
        Long frozen =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_attribution_snapshot WHERE tenant_id=? AND"
                            + " order_id=? AND state='FROZEN'",
                        Long.class,
                        tenant,
                        id);
        if (frozen != null && frozen > 0) throw conflict("已冻结的订单归属不能直接修改");
        var actor = AuthorizationContext.requireCurrent();
        Long customer =
                row.get("customer_id") == null
                        ? null
                        : ((Number) row.get("customer_id")).longValue();
        if (customer == null) {
            missing(tenant, id, "客户映射尚未完成", submitting, keepDraftOnMissing);
            return;
        }
        CustomerOrderAttributionView a = client.resolve(actor, customer);
        if (!a.usable()) {
            missing(tenant, id, a.unavailableReason(), submitting, keepDraftOnMissing);
            jdbc.update(
                    "UPDATE order_sales_order SET region_code=? WHERE tenant_id=? AND id=?",
                    a.regionCode(),
                    tenant,
                    id);
            saveSnapshot(tenant, id, a, false, "REVIEW");
            return;
        }
        if (submitting) {
            var latest = client.resolve(actor, customer);
            if (!a.sourceVersion().equals(latest.sourceVersion()))
                throw conflict("客户或员工归属刚刚变更，请重新提交");
        }
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " owner_employee_code=?,owner_employee_name_snapshot=?,owner_sales_user_id=NULL,owner_sales_name=NULL,region_code=?,customer_code_snapshot=?,customer_name_snapshot=?"
                    + " WHERE tenant_id=? AND id=?",
                a.employeeCode(),
                a.employeeName(),
                a.regionCode(),
                a.customerCode(),
                a.customerName(),
                tenant,
                id);
        saveSnapshot(tenant, id, a, submitting, submitting ? "FROZEN" : "DRAFT");
    }

    private void saveSnapshot(
            String tenant,
            long id,
            CustomerOrderAttributionView a,
            boolean submitting,
            String state) {
        jdbc.update(
                """
INSERT INTO order_attribution_snapshot(tenant_id,order_id,state,employee_code,employee_name,department_id,department_name,department_path,region_code,region_path,source_version,customer_revision,employee_revision,organization_version,resolved_at,frozen_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),employee_code=VALUES(employee_code),employee_name=VALUES(employee_name),department_id=VALUES(department_id),department_name=VALUES(department_name),department_path=VALUES(department_path),region_code=VALUES(region_code),region_path=VALUES(region_path),source_version=VALUES(source_version),customer_revision=VALUES(customer_revision),employee_revision=VALUES(employee_revision),organization_version=VALUES(organization_version),resolved_at=VALUES(resolved_at),frozen_at=VALUES(frozen_at),revision=revision+1
""",
                tenant,
                id,
                state,
                a.employeeCode(),
                a.employeeName(),
                a.departmentId(),
                a.departmentName(),
                json(a.departmentAncestorIds()),
                a.regionCode(),
                json(a.regionAncestorCodes()),
                a.sourceVersion(),
                a.customerRevision(),
                a.employeeRevision(),
                a.organizationVersion(),
                Timestamp.from(a.resolvedAt()),
                submitting ? Timestamp.from(a.resolvedAt()) : null);
    }

    private void missing(String tenant, long id, String reason, boolean submitting, boolean keep) {
        if (submitting && !keep) throw conflict("订单保留草稿：" + reason);
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " order_status_code='DRAFT',data_quality_status_code='NEEDS_REVIEW',data_quality_message=?,owner_employee_code=NULL,owner_employee_name_snapshot=NULL,owner_sales_user_id=NULL,owner_sales_name=NULL,region_code=NULL"
                    + " WHERE tenant_id=? AND id=?",
                reason,
                tenant,
                id);
        jdbc.update(
                "DELETE FROM order_attribution_snapshot WHERE tenant_id=? AND order_id=? AND"
                    + " state<>'FROZEN'",
                tenant,
                id);
    }

    public void requireExecutedReceipt(String tenant, long id) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_fulfillment_execution WHERE tenant_id=? AND"
                            + " order_id=? AND erp_stock_out_id IS NOT NULL AND status IN"
                            + " ('ERP_CONFIRMED','COMPLETED')",
                        Integer.class,
                        tenant,
                        id)
                != 1) throw conflict("没有已核验的 ERP 出库结果，不能只修改订单状态");
    }

    public void requireNoExecution(String tenant, long id) {
        jdbc.queryForObject(
                "SELECT id FROM order_sales_order WHERE tenant_id=? AND id=? FOR UPDATE",
                Long.class,
                tenant,
                id);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_fulfillment_execution WHERE tenant_id=? AND"
                            + " order_id=?",
                        Integer.class,
                        tenant,
                        id)
                > 0) throw conflict("订单已有履约执行记录，不能直接编辑、取消或删除");
    }

    public void rejectFrozenSourceRewrite(String tenant, long id, String employee, String region) {
        var rows =
                jdbc.queryForList(
                        "SELECT employee_code,region_code FROM order_attribution_snapshot WHERE"
                            + " tenant_id=? AND order_id=? AND state='FROZEN'",
                        tenant,
                        id);
        if (!rows.isEmpty()
                && (!Objects.equals(rows.getFirst().get("employee_code"), employee)
                        || !Objects.equals(rows.getFirst().get("region_code"), region)))
            throw conflict("来源更新不能改写已冻结的历史归属，请走归属更正流程");
    }

    private static String json(Object v) {
        return JsonMapper.builder().build().writeValueAsString(v);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }
}

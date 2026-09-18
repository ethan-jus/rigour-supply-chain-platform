package com.rigour.erp.infrastructure.persistence.repository;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ErpOrderExecutionStore;
import com.rigour.order.api.v1.model.FulfillmentExecutionView;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 执行占位在同一事务内提交；失败时连同库存、出库单及回执一起回滚。 */
@Repository
public class JdbcErpOrderExecutionStore implements ErpOrderExecutionStore {
    private final JdbcTemplate jdbc;

    public JdbcErpOrderExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SalesExecutionReceipt> receipt(String tenant, String id) {
        return jdbc
                .queryForList(
                        "SELECT * FROM erp_order_execution_receipt WHERE tenant_id=? AND"
                            + " execution_id=? AND stock_out_id IS NOT NULL",
                        tenant,
                        id)
                .stream()
                .findFirst()
                .map(JdbcErpOrderExecutionStore::view);
    }

    @Transactional
    public SalesExecutionReceipt execute(
            FulfillmentExecutionView c, Supplier<InternalStockOutOrderDetailView> work) {
        var existing =
                jdbc.queryForList(
                        "SELECT * FROM erp_order_execution_receipt WHERE tenant_id=? AND"
                            + " execution_id=? FOR UPDATE",
                        c.tenantId(),
                        c.executionId());
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!c.requestHash().equals(row.get("request_hash"))
                    || number(row, "order_id") != c.orderId()
                    || number(row, "warehouse_id") != c.warehouseId())
                throw conflict("执行标识对应的请求内容不一致");
            if (row.get("stock_out_id") == null) throw conflict("执行结果待核对");
            return view(row);
        }
        var warehouse =
                jdbc.queryForList(
                        "SELECT status_code FROM erp_inventory_warehouse WHERE tenant_id=? AND id=?"
                            + " AND deleted=0 FOR SHARE",
                        c.tenantId(),
                        c.warehouseId());
        if (warehouse.size() != 1 || !"ACTIVE".equals(warehouse.getFirst().get("status_code")))
            throw conflict("仓库不存在或已停用");
        jdbc.update(
                "INSERT INTO"
                    + " erp_order_execution_receipt(tenant_id,execution_id,order_id,warehouse_id,request_hash)"
                    + " VALUES(?,?,?,?,?)",
                c.tenantId(),
                c.executionId(),
                c.orderId(),
                c.warehouseId(),
                c.requestHash());
        var result = work.get();
        if (result.salesOrderId() != c.orderId() || result.warehouseId() != c.warehouseId())
            throw conflict("出库结果与执行意图不一致");
        jdbc.update(
                "UPDATE erp_order_execution_receipt SET"
                    + " stock_out_id=?,stock_out_no=?,stock_out_time=? WHERE tenant_id=? AND"
                    + " execution_id=?",
                result.id(),
                result.stockOutNo(),
                Timestamp.from(result.stockOutTime()),
                c.tenantId(),
                c.executionId());
        return new SalesExecutionReceipt(
                c.executionId(),
                c.orderId(),
                c.warehouseId(),
                result.id(),
                result.stockOutNo(),
                result.stockOutTime());
    }

    private static SalesExecutionReceipt view(Map<String, Object> row) {
        Object time = row.get("stock_out_time");
        Instant instant =
                time instanceof Timestamp t
                        ? t.toInstant()
                        : ((LocalDateTime) time).toInstant(ZoneOffset.UTC);
        return new SalesExecutionReceipt(
                (String) row.get("execution_id"),
                number(row, "order_id"),
                number(row, "warehouse_id"),
                number(row, "stock_out_id"),
                (String) row.get("stock_out_no"),
                instant);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static BusinessException conflict(String m) {
        return new BusinessException(ErrorCode.CONFLICT, m, List.of());
    }
}

package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.OrderFulfillmentStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 同一订单先锁订单行，再锁执行行；意图不可替换仓库或明细，重复回写不会再次产生库存。 */
@Repository
public class JdbcOrderFulfillmentStore implements OrderFulfillmentStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;

    public JdbcOrderFulfillmentStore(JdbcTemplate jdbc, OrderDataScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    @Override
    @Transactional(readOnly=true)
    public OrderFulfillmentQueueView queue(String tenant,int begin,int step,String keyword,String outboundStatus) {
        if(begin<0 || step<1 || step>100) throw new IllegalArgumentException("分页范围无效");
        var p=scopes.predicate("order:outbound:read","o.",null);
        var args=new ArrayList<Object>(List.of(tenant)); args.addAll(p.args());
        String where=" FROM order_sales_order o WHERE o.tenant_id=? AND o.deleted=0 AND o.order_status_code='SUBMITTED' AND o.selected_warehouse_id IS NOT NULL AND "+p.sql();
        if(keyword!=null && !keyword.isBlank()) { if(keyword.length()>100) throw new IllegalArgumentException("搜索内容过长"); where+=" AND LOCATE(?,o.order_no)>0";args.add(keyword.strip()); }
        if(outboundStatus!=null && !outboundStatus.isBlank()) { if(!Set.of("PENDING","CONFIRMED").contains(outboundStatus)) throw new IllegalArgumentException("出库状态无效");where+=" AND o.outbound_status_code=?";args.add(outboundStatus); }
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
        args.add(step);args.add(begin);
        var items=jdbc.query("SELECT o.id,o.order_no,o.selected_warehouse_id,o.outbound_status_code,o.revision"+where+" ORDER BY o.id DESC LIMIT ? OFFSET ?",(rs,n)->new OrderFulfillmentQueueView.Item(rs.getString(1),rs.getString(2),rs.getString(3),null,rs.getString(4),rs.getInt(5)),args.toArray());
        return new OrderFulfillmentQueueView(total,begin,step,items);
    }
    @Override
    @Transactional(readOnly=true)
    public OrderFulfillmentQueueView.Detail detail(String tenant,long id) {
        scopes.requireOrder(tenant,id,"order:outbound:read");
        var item=jdbc.queryForObject("SELECT id,order_no,selected_warehouse_id,outbound_status_code,revision FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0 AND order_status_code='SUBMITTED' AND selected_warehouse_id IS NOT NULL",(rs,n)->new OrderFulfillmentQueueView.Item(rs.getString(1),rs.getString(2),rs.getString(3),null,rs.getString(4),rs.getInt(5)),tenant,id);
        var lines=jdbc.query("SELECT product_code_snapshot,sku_code_snapshot,product_name_snapshot,unit_code,quantity FROM order_sales_order_line WHERE tenant_id=? AND order_id=? AND deleted=0 ORDER BY id",(rs,n)->new OrderFulfillmentQueueView.Line(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5)),tenant,id);
        return new OrderFulfillmentQueueView.Detail(item,rawStatus(tenant,id),lines);
    }

    @Override
    @Transactional(readOnly=true)
    public String regionCode(String tenant,long id) {
        var rows=jdbc.query("SELECT region_code FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0",
                (rs,n)->rs.getString(1),tenant,id);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Override
    public Set<Long> permittedWarehouseIds(
            CallerIdentity actor, long orderId, List<Long> candidates) {
        return scopes.permittedWarehouses(actor.tenantId().toString(), orderId, candidates);
    }

    @Override
    public OrderFulfillmentStatusView status(String tenant, long id) {
        scopes.requireOrder(tenant, id, "order:read");
        return rawStatus(tenant, id);
    }

    @Override
    @Transactional
    public OrderFulfillmentStatusView select(
            CallerIdentity actor, long id, long warehouse, int revision) {
        String tenant = actor.tenantId().toString();
        var order = lock(tenant, id);
        scopes.requireOrder(tenant, id, "order:warehouse:select", warehouse);
        if (warehouse < 1 || revision < 1) throw new IllegalArgumentException("仓库及版本无效");
        if (number(order, "revision") != revision) throw conflict("订单已变更，请刷新");
        if (!Set.of("DRAFT", "SUBMITTED").contains(order.get("order_status_code"))
                || !"PENDING".equals(order.get("outbound_status_code")))
            throw conflict("当前订单不能变更仓库");
        if (!executions(tenant, id).isEmpty()) throw conflict("已开始履约，不能直接改仓");
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " selected_warehouse_id=?,warehouse_selected_by=?,warehouse_selected_at=UTC_TIMESTAMP(6),revision=revision+1"
                    + " WHERE tenant_id=? AND id=?",
                warehouse,
                actor.principalId().toString(),
                tenant,
                id);
        return rawStatus(tenant, id);
    }

    @Override
    @Transactional
    public FulfillmentExecutionView prepare(
            CallerIdentity actor,
            long id,
            long warehouse,
            int revision,
            Instant time,
            String remark) {
        String tenant = actor.tenantId().toString();
        var order = lock(tenant, id);
        scopes.requireOrder(tenant, id, "order:outbound:confirm", warehouse);
        if (warehouse < 1 || revision < 1) throw new IllegalArgumentException("仓库及版本无效");
        if (order.get("selected_warehouse_id") == null
                || number(order, "selected_warehouse_id") != warehouse)
            throw conflict("只能从订单已确认的仓库出库，请先由有权人员选仓");
        var existing = executions(tenant, id);
        if (!existing.isEmpty()) return payload(existing.getFirst());
        if (number(order, "revision") != revision) throw conflict("订单已变更，请刷新");
        if (!"SUBMITTED".equals(order.get("order_status_code"))
                || !"PENDING".equals(order.get("outbound_status_code")))
            throw conflict("只有已提交且待出库的订单才能执行");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_attribution_snapshot WHERE tenant_id=? AND"
                            + " order_id=? AND state='FROZEN'",
                        Integer.class,
                        tenant,
                        id)
                != 1) throw conflict("订单历史归属待核对，不能直接执行出库");
        var lines =
                jdbc
                        .queryForList(
                                "SELECT"
                                    + " id,product_id,product_variant_id,product_code_snapshot,sku_code_snapshot,product_name_snapshot,unit_code,quantity,remark"
                                    + " FROM order_sales_order_line WHERE tenant_id=? AND"
                                    + " order_id=? AND deleted=0 ORDER BY id",
                                tenant,
                                id)
                        .stream()
                        .map(
                                row -> {
                                    if (row.get("product_id") == null
                                            || row.get("product_variant_id") == null
                                            || row.get("unit_code") == null
                                            || ((BigDecimal) row.get("quantity")).signum() <= 0)
                                        throw conflict("订单商品映射待完善");
                                    return new FulfillmentExecutionView.Line(
                                            number(row, "id"),
                                            number(row, "product_id"),
                                            number(row, "product_variant_id"),
                                            (String) row.get("product_code_snapshot"),
                                            (String) row.get("sku_code_snapshot"),
                                            (String) row.get("product_name_snapshot"),
                                            (String) row.get("unit_code"),
                                            (BigDecimal) row.get("quantity"),
                                            (String) row.get("remark"));
                                })
                        .toList();
        if (lines.isEmpty()) throw conflict("订单缺少有效商品明细");
        String execution = UUID.randomUUID().toString();
        Instant occurred = time == null ? Instant.now() : time;
        if (remark != null && remark.length() > 1000)
            throw new IllegalArgumentException("备注不能超过1000字");
        var unsigned =
                new FulfillmentExecutionView(
                        tenant,
                        execution,
                        id,
                        (String) order.get("order_no"),
                        revision,
                        warehouse,
                        order.get("customer_id") == null ? null : number(order, "customer_id"),
                        (String) order.get("customer_name_snapshot"),
                        occurred,
                        lines,
                        remark,
                        null);
        String hash = hash(JSON.writeValueAsString(unsigned));
        var value =
                new FulfillmentExecutionView(
                        tenant,
                        execution,
                        id,
                        unsigned.orderNo(),
                        revision,
                        warehouse,
                        unsigned.customerId(),
                        unsigned.customerName(),
                        occurred,
                        lines,
                        remark,
                        hash);
        jdbc.update(
                "INSERT INTO"
                    + " order_fulfillment_execution(tenant_id,order_id,execution_id,warehouse_id,order_revision,request_json,request_hash,actor_id)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                tenant,
                id,
                execution,
                warehouse,
                revision,
                JSON.writeValueAsString(value),
                hash,
                actor.principalId().toString());
        return value;
    }

    @Override
    @Transactional
    public FulfillmentExecutionView claim(CallerIdentity actor, String id) {
        UUID.fromString(id);
        String tenant = actor.tenantId().toString();
        var ids =
                jdbc.queryForList(
                        "SELECT order_id FROM order_fulfillment_execution WHERE tenant_id=? AND"
                            + " execution_id=?",
                        Long.class,
                        tenant,
                        id);
        if (ids.size() != 1) throw conflict("履约记录不存在");
        var order = lock(tenant, ids.getFirst());
        var row = executions(tenant, ids.getFirst()).getFirst();
        var value = payload(row);
        scopes.requireOrder(tenant, value.orderId(), "order:outbound:confirm", value.warehouseId());
        if (!Set.of("PREPARED", "EXECUTING").contains(row.get("status")))
            throw conflict("执行记录不能再次发起出库");
        if (!"SUBMITTED".equals(order.get("order_status_code"))
                || !"PENDING".equals(order.get("outbound_status_code"))
                || number(order, "revision") != value.orderRevision()
                || number(order, "selected_warehouse_id") != value.warehouseId())
            throw conflict("订单状态或仓库已变更，执行中止");
        jdbc.update(
                "UPDATE order_fulfillment_execution SET"
                    + " status='EXECUTING',attempt_count=attempt_count+1,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND execution_id=?",
                tenant,
                id);
        return value;
    }

    @Override
    @Transactional
    public OrderFulfillmentStatusView complete(
            String tenant, String id, long stockOutId, String no, Instant time, long warehouse) {
        var ids =
                jdbc.queryForList(
                        "SELECT order_id FROM order_fulfillment_execution WHERE tenant_id=? AND"
                            + " execution_id=?",
                        Long.class,
                        tenant,
                        id);
        if (ids.size() != 1) throw conflict("履约记录不存在");
        long orderId = ids.getFirst();
        var order = lock(tenant, orderId);
        var row = executions(tenant, orderId).getFirst();
        if (number(row, "warehouse_id") != warehouse) throw conflict("ERP 返回的仓库与执行意图不同");
        if ("COMPLETED".equals(row.get("status"))) {
            if (number(row, "erp_stock_out_id") != stockOutId) throw conflict("ERP 执行结果不一致");
            return rawStatus(tenant, orderId);
        }
        jdbc.update(
                "UPDATE order_fulfillment_execution SET"
                    + " status='ERP_CONFIRMED',erp_stock_out_id=?,erp_stock_out_no=?,erp_stock_out_at=?,last_error=NULL,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND execution_id=?",
                stockOutId,
                no,
                Timestamp.from(time),
                tenant,
                id);
        // ERP 已发生真实库存动作。仅写回该订单同一仓库的既有结果，不再要求原用户有效。
        if (number(order, "selected_warehouse_id") != warehouse)
            throw conflict("订单仓库异常，需核对 ERP 已执行结果");
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " outbound_status_code='OUT_CONFIRMED',shipment_time=?,revision=revision+1,updated_time=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND id=? AND outbound_status_code='PENDING'",
                Timestamp.from(time),
                tenant,
                orderId);
        jdbc.update(
                "UPDATE order_fulfillment_execution SET"
                    + " status='COMPLETED',updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND"
                    + " execution_id=?",
                tenant,
                id);
        return rawStatus(tenant, orderId);
    }

    @Override
    @Transactional
    public void failed(String tenant, String id, String reason) {
        jdbc.update(
                "UPDATE order_fulfillment_execution SET last_error=?,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND execution_id=? AND status<>'COMPLETED'",
                reason,
                tenant,
                id);
    }

    private Map<String, Object> lock(String tenant, long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0"
                            + " FOR UPDATE",
                        tenant,
                        id);
        if (rows.size() != 1) throw conflict("订单不存在");
        return rows.getFirst();
    }

    private List<Map<String, Object>> executions(String tenant, long id) {
        return jdbc.queryForList(
                "SELECT * FROM order_fulfillment_execution WHERE tenant_id=? AND order_id=? FOR"
                    + " UPDATE",
                tenant,
                id);
    }

    private OrderFulfillmentStatusView rawStatus(String tenant, long id) {
        var row =
                jdbc.queryForMap(
                        "SELECT"
                            + " o.id,o.revision,o.selected_warehouse_id,e.execution_id,e.status,e.erp_stock_out_id,e.erp_stock_out_no,e.erp_stock_out_at,e.last_error"
                            + " FROM order_sales_order o LEFT JOIN order_fulfillment_execution e ON"
                            + " e.tenant_id=o.tenant_id AND e.order_id=o.id WHERE o.tenant_id=? AND"
                            + " o.id=? AND o.deleted=0",
                        tenant,
                        id);
        return new OrderFulfillmentStatusView(
                id,
                (int) number(row, "revision"),
                nullable(row, "selected_warehouse_id"),
                (String) row.get("execution_id"),
                row.get("status") == null ? "NOT_STARTED" : (String) row.get("status"),
                nullable(row, "erp_stock_out_id"),
                (String) row.get("erp_stock_out_no"),
                instant(row.get("erp_stock_out_at")),
                (String) row.get("last_error"));
    }

    private static FulfillmentExecutionView payload(Map<String, Object> row) {
        return JSON.readValue(row.get("request_json").toString(), FulfillmentExecutionView.class);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Long nullable(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : number(row, key);
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        return v instanceof Timestamp t
                ? t.toInstant()
                : ((LocalDateTime) v).toInstant(ZoneOffset.UTC);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException conflict(String m) {
        return new BusinessException(ErrorCode.CONFLICT, m, List.of());
    }
}

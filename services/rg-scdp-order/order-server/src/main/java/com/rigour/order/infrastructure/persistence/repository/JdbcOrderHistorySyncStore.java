package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.application.port.out.OrderHistorySyncStore;
import com.rigour.order.domain.sync.HistorySyncRules;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 门店关联与回款接续均在Order本域事务中完成；无法证明的归属保留待核对。 */
@Repository
public class JdbcOrderHistorySyncStore implements OrderHistorySyncStore {
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final com.rigour.order.application.port.out.OrderAttributionClient owners;
    private final com.rigour.order.application.port.out.OrderSalesPaymentRecordStore payments;
    private final com.rigour.order.application.service.sales.OrderSalesOrderService salesOrders;

    public JdbcOrderHistorySyncStore(
            JdbcTemplate jdbc,
            com.rigour.order.application.port.out.OrderAttributionClient owners,
            com.rigour.order.application.port.out.OrderSalesPaymentRecordStore payments,
            com.rigour.order.application.service.sales.OrderSalesOrderService salesOrders) {
        this.jdbc = jdbc;
        this.owners = owners;
        this.payments = payments;
        this.salesOrders = salesOrders;
    }

    private static LocalDateTime ts(Instant v) {
        return v == null ? null : LocalDateTime.ofInstant(v, ZoneOffset.UTC);
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        if (v instanceof Timestamp time) return time.toLocalDateTime().toInstant(ZoneOffset.UTC);
        throw new IllegalArgumentException("无法解析来源时间类型");
    }

    private static String text(Map<String, Object> r, String k) {
        var v = r.get(k);
        return v == null ? null : v.toString();
    }

    private static long num(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private static BigDecimal money(Map<String, Object> r, String k) {
        return new BigDecimal(r.get(k).toString());
    }

    private static void require(boolean b, String s) {
        if (!b) throw new IllegalArgumentException(s);
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        require(rows.size() == 1, "记录不存在或不唯一");
        return rows.getFirst();
    }

    private static void id(String s) {
        require(s != null && !s.isBlank() && s.length() <= 128, "来源编号无效");
    }

    private List<Map<String, Object>> source(String t, UUID c, String n, boolean lock) {
        return jdbc.queryForList(
                "SELECT * FROM order_sync_source WHERE tenant_id=? AND connector_id=? AND"
                    + " source_no=?"
                        + (lock ? " FOR UPDATE" : ""),
                t,
                c.toString(),
                n);
    }

    private Map<String, Object> order(String t, long id) {
        return one(
                "SELECT * FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0 FOR"
                    + " UPDATE",
                t,
                id);
    }

    public StoreView overview(String t, Long c) {
        String where = c == null ? "" : " AND customer_id=?";
        Object[] args = c == null ? new Object[] {t} : new Object[] {t, c};
        var stores =
                jdbc.queryForList(
                        "SELECT customer_id,MAX(customer_name_snapshot) customer_name,COUNT(*)"
                            + " history_count,SUM(payable_amount) history_amount FROM"
                            + " order_sales_order WHERE tenant_id=? AND source_system_code='FEISHU'"
                            + " AND deleted=0"
                                + where
                                + " GROUP BY customer_id ORDER BY customer_id",
                        args);
        var byCustomer = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : stores) byCustomer.put(text(row, "customer_id"), row);
        for (var row :
                jdbc.queryForList(
                        "SELECT customer_id,COUNT(*) source_count,SUM(amount) source_amount FROM"
                            + " order_sync_source WHERE tenant_id=?"
                                + where
                                + " GROUP BY customer_id",
                        args)) {
            var target =
                    byCustomer.computeIfAbsent(
                            text(row, "customer_id"),
                            k ->
                                    new LinkedHashMap<>(
                                            Map.of(
                                                    "customer_id",
                                                    k,
                                                    "customer_name",
                                                    "门店 " + k,
                                                    "history_count",
                                                    0,
                                                    "history_amount",
                                                    BigDecimal.ZERO)));
            target.putAll(row);
        }
        for (var row :
                jdbc.queryForList(
                        "SELECT customer_id,COUNT(*) receipt_count,SUM(CASE WHEN"
                            + " source_status='CONFIRMED' THEN amount ELSE 0 END) receipt_amount"
                            + " FROM order_sync_receipt WHERE tenant_id=?"
                                + where
                                + " AND customer_id IS NOT NULL GROUP BY customer_id",
                        args)) {
            var target =
                    byCustomer.computeIfAbsent(
                            text(row, "customer_id"),
                            k ->
                                    new LinkedHashMap<>(
                                            Map.of(
                                                    "customer_id",
                                                    k,
                                                    "customer_name",
                                                    "门店 " + k,
                                                    "history_count",
                                                    0,
                                                    "history_amount",
                                                    BigDecimal.ZERO)));
            target.putAll(row);
        }
        stores = new ArrayList<>(byCustomer.values());
        if (c == null)
            return new StoreView(safeIds(stores), List.of(), List.of(), List.of(), List.of());
        var sources =
                jdbc.queryForList(
                        "SELECT"
                            + " connector_id,source_no,customer_id,source_date,amount,state,group_id,revision,payload"
                            + " FROM order_sync_source WHERE tenant_id=?"
                                + where
                                + " ORDER BY customer_id,source_no",
                        args);
        var history =
                jdbc.queryForList(
                        "SELECT"
                            + " id,order_no,source_order_no,customer_id,customer_name_snapshot,order_date,owner_employee_code,owner_employee_name_snapshot,payable_amount,paid_amount,unpaid_amount,source_unpaid_amount,revision"
                            + " FROM order_sales_order WHERE tenant_id=? AND"
                            + " source_system_code='FEISHU' AND deleted=0"
                                + where
                                + " ORDER BY customer_id,id",
                        args);
        var groups =
                jdbc.queryForList(
                        "SELECT * FROM order_history_group WHERE tenant_id=?" + where, args);
        var receipts =
                jdbc.queryForList(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=?"
                                + where
                                + " ORDER BY occurred_at DESC,receipt_no",
                        args);
        for (var h : history)
            h.put(
                    "lines",
                    safeIds(
                            jdbc.queryForList(
                                    "SELECT"
                                        + " id,order_id,product_id,product_variant_id,product_name_snapshot,specification_snapshot,unit_code,quantity,line_amount"
                                        + " FROM order_sales_order_line WHERE tenant_id=? AND"
                                        + " order_id=? AND deleted=0",
                                    t,
                                    h.get("id"))));
        for (var receipt : receipts) {
            receipt.put(
                    "allocations",
                    safeIds(
                            jdbc.queryForList(
                                    "SELECT a.*,l.id"
                                        + " line_id,l.product_name_snapshot,l.specification_snapshot,l.line_amount"
                                        + " FROM order_sync_allocation a JOIN"
                                        + " order_sales_order_line l ON l.tenant_id=a.tenant_id AND"
                                        + " l.order_id=a.order_id AND l.deleted=0 WHERE"
                                        + " a.tenant_id=? AND a.connector_id=? AND a.receipt_no=?",
                                    t,
                                    receipt.get("connector_id"),
                                    receipt.get("receipt_no"))));
            if ("NEW".equals(text(receipt, "state")))
                receipt.put(
                        "allocations",
                        safeIds(
                                jdbc.queryForList(
                                        "SELECT p.order_id,p.paid_amount amount,l.id"
                                            + " line_id,l.product_name_snapshot,l.specification_snapshot,l.line_amount"
                                            + " FROM order_payment_record p JOIN"
                                            + " order_sales_order_line l ON l.tenant_id=p.tenant_id"
                                            + " AND l.order_id=p.order_id AND l.deleted=0 WHERE"
                                            + " p.tenant_id=? AND p.connector_id=? AND"
                                            + " p.source_system_code='DINGHUOBAO' AND"
                                            + " p.source_document_no=? AND p.deleted=0",
                                        t,
                                        receipt.get("connector_id"),
                                        receipt.get("receipt_no"))));
            receipt.put(
                    "product_allocated",
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_sync_product_allocation WHERE"
                                        + " tenant_id=? AND connector_id=? AND receipt_no=?",
                                    Long.class,
                                    t,
                                    receipt.get("connector_id"),
                                    receipt.get("receipt_no"))
                            > 0);
        }
        for (var g : groups)
            g.put(
                    "members",
                    safeIds(
                            jdbc.queryForList(
                                    "SELECT * FROM order_history_member WHERE tenant_id=? AND"
                                        + " group_id=?",
                                    t,
                                    g.get("id"))));
        return new StoreView(
                safeIds(stores),
                safeIds(sources),
                safeIds(history),
                safeIds(groups),
                safeIds(receipts));
    }

    private static List<Map<String, Object>> safeIds(List<Map<String, Object>> rows) {
        for (var row : rows)
            for (String key :
                    List.of(
                            "id",
                            "customer_id",
                            "order_id",
                            "line_id",
                            "payment_id",
                            "product_id",
                            "product_variant_id"))
                if (row.get(key) != null) row.put(key, row.get(key).toString());
        for (var row : rows)
            for (var entry : new ArrayList<>(row.entrySet()))
                if (entry.getValue() instanceof LocalDateTime
                        || entry.getValue() instanceof Timestamp)
                    row.put(entry.getKey(), instant(entry.getValue()).toString());
        return rows;
    }

    /** 来源取消只删除业务投影，保留来源、关联、核销审计记录。 */
    @Transactional
    public Intake cancelSourceOrder(String t, String actor, CancelSourceOrder c) {
        require(c != null && c.connectorId() != null, "来源连接器缺失");
        id(c.sourceNo());
        require(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_sync_source WHERE tenant_id=? AND source_no=? AND connector_id<>?",
                Long.class, t, c.sourceNo(), c.connectorId().toString()) == 0,
                "来源单属于其他连接器，不能执行删除");
        var sources = source(t, c.connectorId(), c.sourceNo(), true);
        String group = sources.isEmpty() ? null : text(sources.getFirst(), "group_id");
        jdbc.update("UPDATE order_sync_source SET state='CANCELLED',revision=revision+1"
                        + " WHERE tenant_id=? AND connector_id=? AND source_no=? AND state<>'CANCELLED'",
                t, c.connectorId().toString(), c.sourceNo());
        // 一张历史单可能对应多张来源单，不能因其中一张取消删除其他有效订单。
        if (group != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_sync_source WHERE tenant_id=? AND group_id=? AND state<>'CANCELLED'",
                Long.class, t, group) > 0) {
            return new Intake("CANCELLATION_REVIEW", group, "关联组仍有有效来源订单，需拆分核对后删除取消部分");
        }
        var orders = jdbc.queryForList(
                "SELECT o.id FROM order_sales_order o WHERE o.tenant_id=? AND o.deleted=0 AND"
                        + " ((o.source_system_code='DINGHUOBAO' AND o.source_order_no=?) OR EXISTS"
                        + " (SELECT 1 FROM order_history_member m WHERE m.tenant_id=o.tenant_id"
                        + " AND m.order_id=o.id AND m.group_id=?)) FOR UPDATE", t, c.sourceNo(), group);
        for (var order : orders) {
            long orderId = num(order, "id");
            jdbc.update("UPDATE order_payment_record SET deleted=1,revision=revision+1,updated_by=?,"
                            + " updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND order_id=? AND deleted=0",
                    actor, t, orderId);
            jdbc.update("UPDATE order_sales_order_line SET deleted=1,revision=revision+1,updated_by=?,"
                            + " updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND order_id=? AND deleted=0",
                    actor, t, orderId);
            jdbc.update("UPDATE order_sales_order SET deleted=1,order_status_code='CANCELLED',"
                            + " revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6)"
                            + " WHERE tenant_id=? AND id=? AND deleted=0", actor, t, orderId);
        }
        return new Intake("CANCELLED", group, null);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Intake sourceOrder(String t, SourceOrder c) {
        require(
                c != null
                        && c.connectorId() != null
                        && c.order() != null
                        && c.order().customerId() != null,
                "来源订单必须先完成精确客户映射");
        id(c.sourceNo());
        id(c.checksum());
        var amount = HistorySyncRules.money(c.amount());
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sync_source WHERE tenant_id=? AND"
                                    + " source_no=? AND connector_id<>?",
                                Long.class,
                                t,
                                c.sourceNo(),
                                c.connectorId().toString())
                        == 0,
                "相同来源单出现在不同连接器，请确认来源账号命名空间后再同步");
        var rows = source(t, c.connectorId(), c.sourceNo(), true);
        String group = null, state;
        boolean unbound =
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sales_order o WHERE o.tenant_id=? AND"
                                    + " o.customer_id=? AND o.source_system_code='FEISHU' AND"
                                    + " o.deleted=0 AND NOT EXISTS(SELECT 1 FROM"
                                    + " order_history_member m WHERE m.tenant_id=o.tenant_id AND"
                                    + " m.order_id=o.id)",
                                Long.class,
                                t,
                                c.order().customerId())
                        > 0;
        state = HistorySyncRules.classify(c.order().orderDate(), unbound);
        if (!rows.isEmpty()) {
            var old = rows.getFirst();
            group = text(old, "group_id");
            if ("CANCELLED".equals(text(old, "state")))
                return new Intake("CANCELLED", group, "来源订单已取消，不能自动恢复");
            require(num(old, "customer_id") == c.order().customerId(), "来源订单客户发生变化，必须先人工核对");
            if (c.checksum().equals(text(old, "checksum"))
                    && money(old, "amount").compareTo(amount) == 0) {
                String stored = text(old, "state");
                // 历史关联进度会改变新旧订单判定：校验和未变也要按当前关联状态重算，只允许向上收敛。
                if (Set.of("HISTORY_PENDING", "NEW_OR_HISTORY_REVIEW", "DATE_REVIEW").contains(stored)
                        && !stored.equals(state)) {
                    jdbc.update(
                            "UPDATE order_sync_source SET state=?,revision=revision+1 WHERE tenant_id=?"
                                + " AND connector_id=? AND source_no=?",
                            state,
                            t,
                            c.connectorId().toString(),
                            c.sourceNo());
                    return new Intake(state, group, null);
                }
                return new Intake(stored, group, null);
            }
            if (group != null) {
                var previous = JSON.readValue(text(old, "payload"), SalesOrderCommand.class);
                if (money(old, "amount").compareTo(amount) == 0
                        && sameSourceLines(previous.lines(), c.order().lines()))
                    return new Intake("BOUND", group, null);
                jdbc.update(
                        "UPDATE order_sync_source SET"
                            + " state='SOURCE_CHANGED_REVIEW',revision=revision+1 WHERE tenant_id=?"
                            + " AND connector_id=? AND source_no=?",
                        t,
                        c.connectorId().toString(),
                        c.sourceNo());
                return new Intake("SOURCE_CHANGED_REVIEW", group, "已绑定历史来源商品或金额发生变化，未覆盖原订单，需重新核对");
            }
            if ("NEW".equals(text(old, "state"))) state = "NEW";
            jdbc.update(
                    "UPDATE order_sync_source SET"
                        + " source_date=?,amount=?,payload=?,checksum=?,state=?,revision=revision+1"
                        + " WHERE tenant_id=? AND connector_id=? AND source_no=?",
                    ts(c.order().orderDate()),
                    amount,
                    JSON.writeValueAsString(c.order()),
                    c.checksum(),
                    state,
                    t,
                    c.connectorId().toString(),
                    c.sourceNo());
        } else {
            jdbc.update(
                    "INSERT INTO"
                        + " order_sync_source(tenant_id,connector_id,source_no,customer_id,source_date,amount,payload,checksum,state)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    t,
                    c.connectorId().toString(),
                    c.sourceNo(),
                    c.order().customerId(),
                    ts(c.order().orderDate()),
                    amount,
                    JSON.writeValueAsString(c.order()),
                    c.checksum(),
                    state);
        }
        return new Intake(state, group, "NEW".equals(state) ? null : "来源已保存，历史订单等待门店关联复核，不创建重复订单");
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void confirmNew(String t, String actor, NewOrder c) {
        require(c != null && c.source() != null, "请选择待确认来源订单");
        HistorySyncRules.evidence(c.evidence());
        var ref = c.source();
        var s =
                one(
                        "SELECT * FROM order_sync_source WHERE tenant_id=? AND connector_id=? AND"
                            + " source_no=? FOR UPDATE",
                        t,
                        ref.connectorId().toString(),
                        ref.sourceNo());
        require(
                num(s, "revision") == ref.revision()
                        && s.get("group_id") == null
                        && "NEW_OR_HISTORY_REVIEW".equals(text(s, "state")),
                "记录已改变或不是新旧订单待确认状态");
        require(
                s.get("source_date") != null
                        && !instant(s.get("source_date")).isBefore(HistorySyncRules.CUTOVER),
                "切换日前的单据不能确认为新增销售");
        jdbc.update(
                "UPDATE order_sync_source SET"
                    + " state='NEW',classification_evidence=?,classification_actor=?,revision=revision+1"
                    + " WHERE tenant_id=? AND connector_id=? AND source_no=?",
                c.evidence(),
                actor,
                t,
                ref.connectorId().toString(),
                ref.sourceNo());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void confirmHistoricalNew(String t, String actor, NewOrder c) {
        require(c != null && c.source() != null, "请选择待确认历史来源订单");
        HistorySyncRules.evidence(c.evidence());
        var ref = c.source();
        var s = one("SELECT * FROM order_sync_source WHERE tenant_id=? AND connector_id=?"
                + " AND source_no=? FOR UPDATE", t, ref.connectorId().toString(), ref.sourceNo());
        require(num(s, "revision") == ref.revision() && s.get("group_id") == null
                && "HISTORY_PENDING".equals(text(s, "state")), "历史来源版本或关联状态已改变");
        require(s.get("source_date") != null && instant(s.get("source_date")).isBefore(HistorySyncRules.CUTOVER),
                "仅允许切换日前的未关联历史订单补新增");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM order_sales_order WHERE tenant_id=?"
                + " AND source_system_code='DINGHUOBAO' AND source_order_no=? AND deleted=0",
                Long.class, t, ref.sourceNo()) == 0, "来源订单已经存在，不能重复补新增");
        jdbc.update("UPDATE order_sync_source SET state='NEW',classification_evidence=?,"
                + "classification_actor=?,revision=revision+1 WHERE tenant_id=? AND connector_id=? AND source_no=?",
                c.evidence(), actor, t, ref.connectorId().toString(), ref.sourceNo());
    }

    /** 已批准的历史清理：保留行和原业务字段，只打删除标记；任何回款或关联均阻止删除。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteUnlinkedHistory(String t, String actor, DeleteUnlinkedHistory c) {
        require(c != null && c.orderId() > 0 && c.revision() >= 0, "历史订单或版本无效");
        HistorySyncRules.evidence(c.evidence());
        var o = order(t, c.orderId());
        require(num(o, "revision") == c.revision() && "FEISHU".equals(text(o, "source_system_code")),
                "只允许按原版本清理飞书历史孤立单");
        require(o.get("order_date") != null && instant(o.get("order_date")).isBefore(HistorySyncRules.CUTOVER),
                "仅允许清理切换日前历史订单");
        require(money(o, "paid_amount").signum() == 0, "订单有已收金额，不能删除");
        for (String table : List.of("order_history_member", "order_payment_record", "order_sync_allocation",
                "order_sync_product_allocation", "order_refund_record", "order_financial_event",
                "order_fulfillment_execution")) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND order_id=?",
                    Long.class, t, c.orderId()) == 0, "订单存在关联、回款或执行引用，不能删除: " + table);
        }
        for (var ref : Map.of("order_fund_document", "related_order_id", "order_sales_shipment", "sales_order_id",
                "order_invoice", "sales_order_id").entrySet()) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM " + ref.getKey() + " WHERE tenant_id=? AND "
                    + ref.getValue() + "=?", Long.class, t, c.orderId()) == 0, "订单存在业务引用，不能删除: " + ref.getKey());
        }
        require(jdbc.queryForObject("SELECT COUNT(*) FROM order_number_mapping WHERE tenant_id=?"
                + " AND internal_order_no=? AND deleted=0", Long.class, t, text(o, "order_no")) == 0,
                "订单已有订货宝单号关联，不能删除");
        var now = ts(Instant.now());
        String remark = (text(o, "remark") == null ? "" : text(o, "remark") + "\n") + "历史对齐逻辑删除：" + c.evidence();
        require(remark.length() <= 1000, "原备注过长，无法保存删除证据");
        require(jdbc.update("UPDATE order_sales_order SET deleted=1,revision=revision+1,updated_by=?,"
                + "updated_time=?,remark=? WHERE tenant_id=? AND id=? AND revision=? AND deleted=0",
                actor, now, remark, t, c.orderId(), c.revision()) == 1, "订单已被修改");
        jdbc.update("UPDATE order_sales_order_line SET deleted=1,revision=revision+1,updated_by=?,updated_time=?"
                + " WHERE tenant_id=? AND order_id=? AND deleted=0", actor, now, t, c.orderId());
    }

    /** 每个旧组独立原子迁移；回款按明确拆分计划守恒，所有原值进入本域审计。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Long> normalizeGroup(String t, String actor, NormalizeGroup c) {
        require(c != null && c.operationId() != null && c.connectorId() != null, "对齐操作身份缺失");
        HistorySyncRules.evidence(c.evidence());
        String request = JSON.writeValueAsString(c);
        var done = jdbc.queryForList("SELECT request_json,result_json FROM order_history_reconciliation_audit"
                + " WHERE tenant_id=? AND operation_id=?", t, c.operationId().toString());
        if (!done.isEmpty()) {
            require(request.equals(text(done.getFirst(), "request_json")), "幂等操作的内容发生变化");
            var result = new LinkedHashMap<String, Long>();
            JSON.readTree(text(done.getFirst(), "result_json")).properties().forEach(e -> result.put(e.getKey(), e.getValue().asLong()));
            return result;
        }
        var group = one("SELECT * FROM order_history_group WHERE tenant_id=? AND id=? FOR UPDATE", t, c.groupId());
        var originalSources = jdbc.queryForList("SELECT * FROM order_sync_source WHERE tenant_id=? AND group_id=? ORDER BY source_no FOR UPDATE", t, c.groupId());
        var originalMembers = jdbc.queryForList("SELECT * FROM order_history_member WHERE tenant_id=? AND group_id=? ORDER BY order_id FOR UPDATE", t, c.groupId());
        require(originalSources.size() > 1 || originalMembers.size() > 1, "仅用于拆合组的一单一单规范化");
        require(c.targets() != null && c.orders() != null && c.payments() != null
                && c.targets().size() == originalSources.size() && c.orders().size() == originalMembers.size(), "组成员不完整");
        var before = new LinkedHashMap<String, Object>();
        before.put("group", group); before.put("sources", originalSources); before.put("members", originalMembers);
        Set<Long> memberIds = new TreeSet<>();
        originalMembers.forEach(m -> memberIds.add(num(m, "order_id")));
        Map<Long, Map<String, Object>> oldOrders = new TreeMap<>();
        for (var ref : c.orders().stream().sorted(Comparator.comparingLong(HistoryOrderRef::orderId)).toList()) {
            require(memberIds.contains(ref.orderId()) && !oldOrders.containsKey(ref.orderId()), "历史订单不在当前组或重复");
            var o = order(t, ref.orderId());
            require(num(o, "revision") == ref.revision() && num(o, "customer_id") == num(group, "customer_id"), "订单版本或门店改变");
            oldOrders.put(ref.orderId(), o);
        }
        before.put("orders", oldOrders.values());
        var oldPayments = new TreeMap<Long, Map<String, Object>>();
        var oldLines = new ArrayList<Map<String, Object>>();
        var oldAllocations = new ArrayList<Map<String, Object>>();
        var oldFunds = new ArrayList<Map<String, Object>>();
        for (long id : memberIds) {
            for (String table : List.of("order_fulfillment_execution", "order_refund_record", "order_financial_event", "order_sync_product_allocation"))
                require(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND order_id=?", Long.class, t, id) == 0, "存在不能自动迁移的引用: " + table);
            for (String table : List.of("order_sales_shipment", "order_invoice"))
                require(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND sales_order_id=?", Long.class, t, id) == 0, "存在不能自动迁移的引用: " + table);
            jdbc.queryForList("SELECT * FROM order_payment_record WHERE tenant_id=? AND order_id=? AND deleted=0 ORDER BY id FOR UPDATE", t, id)
                    .forEach(p -> oldPayments.put(num(p, "id"), p));
            oldLines.addAll(jdbc.queryForList("SELECT * FROM order_sales_order_line WHERE tenant_id=? AND order_id=? AND deleted=0", t, id));
            oldAllocations.addAll(jdbc.queryForList("SELECT * FROM order_sync_allocation WHERE tenant_id=? AND order_id=? FOR UPDATE", t, id));
            oldFunds.addAll(jdbc.queryForList("SELECT * FROM order_fund_document WHERE tenant_id=? AND related_order_id=? AND deleted=0 FOR UPDATE", t, id));
        }
        var oldReceipts = jdbc.queryForList("SELECT * FROM order_sync_receipt WHERE tenant_id=? AND group_id=? FOR UPDATE", t, c.groupId());
        before.put("lines", oldLines); before.put("payments", oldPayments.values()); before.put("allocations", oldAllocations);
        before.put("funds", oldFunds); before.put("receipts", oldReceipts);
        HistorySyncRules.equal(oldOrders.values().stream().map(o -> money(o, "paid_amount")).reduce(BigDecimal.ZERO, BigDecimal::add),
                oldPayments.values().stream().map(p -> money(p, "paid_amount")).reduce(BigDecimal.ZERO, BigDecimal::add), "原订单已收与回款记录合计");
        var sourceByNo = new TreeMap<String, Map<String, Object>>();
        originalSources.forEach(s -> sourceByNo.put(text(s, "source_no"), s));
        Set<Long> reused = new HashSet<>(); Set<String> selected = new HashSet<>();
        for (var target : c.targets()) {
            var ref = target.source(); var source = sourceByNo.get(ref.sourceNo());
            require(source != null && selected.add(ref.sourceNo()) && c.connectorId().equals(ref.connectorId())
                    && ref.connectorId().toString().equals(text(source, "connector_id")) && num(source, "revision") == ref.revision(), "来源成员或版本不符");
            require(target.command() != null && Objects.equals(target.command().customerId(), num(group, "customer_id")), "订单客户不能跨门店");
            require(target.sourceOrder() != null && ref.connectorId().equals(target.sourceOrder().connectorId())
                    && ref.sourceNo().equals(target.sourceOrder().sourceNo())
                    && Objects.equals(target.sourceOrder().order().customerId(), target.command().customerId())
                    && target.sourceOrder().order().lines().equals(target.command().lines()), "缺少一致的来源明细证据");
            if (target.orderId() != null) require(oldOrders.containsKey(target.orderId()) && reused.add(target.orderId()), "每张旧订单只能对应一个来源单");
            else require("DINGHUOBAO".equals(target.command().sourceSystemCode()) && ref.sourceNo().equals(target.command().sourceOrderNo()), "新单来源身份不符");
        }
        require(c.payments().size() == oldPayments.size(), "回款清单发生变化");
        Set<Long> plannedPayments = new HashSet<>();
        for (var split : c.payments()) {
            var payment = oldPayments.get(split.paymentId());
            require(payment != null && plannedPayments.add(split.paymentId()) && num(payment, "revision") == split.revision(), "回款版本或清单不符");
            require(split.portions() != null && !split.portions().isEmpty(), "回款不能丢失");
            BigDecimal sum = BigDecimal.ZERO; Set<String> seen = new HashSet<>();
            for (var part : split.portions()) {
                require(selected.contains(part.sourceNo()) && seen.add(part.sourceNo()) && part.amount() != null && part.amount().signum() > 0, "回款拆分无效");
                sum = sum.add(part.amount());
            }
            HistorySyncRules.equal(sum, money(payment, "paid_amount"), "拆分前后回款总额");
            if (oldAllocations.stream().anyMatch(a -> num(a, "payment_id") == split.paymentId()))
                require(split.portions().size() == 1, "已有订货宝核销记录不能跨来源拆分");
        }
        var result = new LinkedHashMap<String, Long>();
        for (var target : c.targets()) {
            var saved = target.orderId() == null ? salesOrders.create(target.command()) : salesOrders.update(target.orderId(), target.command());
            result.put(target.source().sourceNo(), saved.id());
        }
        var now = ts(Instant.now());
        for (var split : c.payments()) {
            var original = oldPayments.get(split.paymentId()); int index = 0;
            for (var part : split.portions()) {
                long targetId = result.get(part.sourceNo()); var targetOrder = order(t, targetId);
                String evidence = "历史单号规范化 " + c.operationId() + "；原回款 " + text(original, "payment_no") + "(" + split.paymentId() + ")；对应 " + part.sourceNo();
                if (index++ == 0) {
                    jdbc.update("UPDATE order_payment_record SET order_id=?,sales_order_no_snapshot=?,paid_amount=?,remark=?,updated_by=?,updated_time=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=? AND deleted=0",
                            targetId, text(targetOrder, "order_no"), part.amount(), evidence, actor, now, t, split.paymentId(), split.revision());
                    for (var allocation : oldAllocations) if (num(allocation, "payment_id") == split.paymentId()) {
                        var receipt = oldReceipts.stream().filter(r -> text(r, "receipt_no").equals(text(allocation, "receipt_no"))).findFirst().orElseThrow();
                        require(part.sourceNo().equals(text(receipt, "source_order_no")), "回款必须对应订货宝原订单号");
                        jdbc.update("UPDATE order_sync_allocation SET order_id=? WHERE tenant_id=? AND connector_id=? AND receipt_no=? AND order_id=?",
                                targetId, t, text(allocation, "connector_id"), text(allocation, "receipt_no"), num(allocation, "order_id"));
                    }
                } else {
                    var copy = new LinkedHashMap<>(original); copy.remove("id"); copy.remove("transaction_no_key");
                    copy.put("payment_no", "HPR" + UUID.randomUUID().toString().replace("-", ""));
                    String sourceNo = text(original, "source_document_no");
                    copy.put("source_document_no", sourceNo == null ? null : sourceNo + "#H" + c.operationId().toString().substring(0, 8) + "-" + index);
                    copy.put("order_id", targetId); copy.put("sales_order_no_snapshot", text(targetOrder, "order_no")); copy.put("paid_amount", part.amount());
                    copy.put("transaction_no", null); copy.put("remark", evidence); copy.put("revision", 0);
                    copy.put("created_by", actor); copy.put("updated_by", actor); copy.put("created_time", now); copy.put("updated_time", now);
                    new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbc).withTableName("order_payment_record").usingColumns(copy.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(copy);
                }
            }
        }
        for (var fund : oldFunds) {
            String sourceNo = text(fund, "source_order_no");
            if (!result.containsKey(sourceNo)) sourceNo = oldReceipts.stream().filter(r -> Objects.equals(text(r, "receipt_no"), text(fund, "source_document_no")))
                    .map(r -> text(r, "source_order_no")).findFirst().orElse(null);
            require(result.containsKey(sourceNo), "资金单的订单归属无法证明");
            var targetOrder = order(t, result.get(sourceNo));
            jdbc.update("UPDATE order_fund_document SET related_order_id=?,sales_order_no_snapshot=?,revision=revision+1,updated_by=?,updated_time=? WHERE tenant_id=? AND id=?",
                    result.get(sourceNo), text(targetOrder, "order_no"), actor, now, t, num(fund, "id"));
        }
        var baseline = originalMembers.getFirst().get("baseline_at");
        require(originalMembers.stream().allMatch(m -> Objects.equals(m.get("baseline_at"), baseline)), "原组期初时点不一致");
        jdbc.update("DELETE FROM order_history_member WHERE tenant_id=? AND group_id=?", t, c.groupId());
        for (var entry : result.entrySet()) {
            String no = entry.getKey(); long id = entry.getValue(); var targetOrder = order(t, id);
            BigDecimal paid = jdbc.queryForObject("SELECT COALESCE(SUM(paid_amount),0) FROM order_payment_record WHERE tenant_id=? AND order_id=? AND deleted=0", BigDecimal.class, t, id);
            BigDecimal allocated = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM order_sync_allocation WHERE tenant_id=? AND order_id=?", BigDecimal.class, t, id);
            BigDecimal payable = money(targetOrder, "payable_amount");
            require(paid.compareTo(payable) <= 0, "拆合组回款超过订单金额，请保留人工复核");
            String newGroup = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO order_history_group(tenant_id,id,customer_id,evidence,actor_id,created_at,source_amount,order_amount,difference_amount,difference_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    t, newGroup, num(group, "customer_id"), c.evidence() + "；原组 " + c.groupId(), actor, now, money(targetOrder, "original_amount"), payable, money(targetOrder, "original_amount").subtract(payable), "订货宝原价与折后金额分别保留");
            jdbc.update("INSERT INTO order_history_member(tenant_id,order_id,group_id,baseline_at,opening_paid) VALUES(?,?,?,?,?)", t, id, newGroup, baseline, paid.subtract(allocated));
            var sourceOrder = c.targets().stream().filter(v -> v.source().sourceNo().equals(no)).findFirst().orElseThrow().sourceOrder();
            jdbc.update("UPDATE order_sync_source SET group_id=?,state='BOUND',source_date=?,amount=?,payload=?,checksum=?,revision=revision+1 WHERE tenant_id=? AND connector_id=? AND source_no=?",
                    newGroup, ts(sourceOrder.order().orderDate()), sourceOrder.amount(), JSON.writeValueAsString(sourceOrder.order()), sourceOrder.checksum(), t, c.connectorId().toString(), no);
            jdbc.update("UPDATE order_sync_receipt SET group_id=?,revision=revision+1 WHERE tenant_id=? AND group_id=? AND source_order_no=?", newGroup, t, c.groupId(), no);
            jdbc.update("UPDATE order_sales_order SET paid_amount=?,unpaid_amount=?,source_unpaid_amount=?,payment_status_code=?,payment_time=(SELECT MAX(payment_time) FROM order_payment_record p WHERE p.tenant_id=? AND p.order_id=? AND p.deleted=0),revision=revision+1,updated_by=?,updated_time=? WHERE tenant_id=? AND id=?",
                    paid, payable.subtract(paid), payable.subtract(paid), paid.signum() == 0 ? "UNPAID" : paid.compareTo(payable) >= 0 ? "PAID" : "PARTIAL_PAID", t, id, actor, now, t, id);
        }
        require(jdbc.queryForObject("SELECT COUNT(*) FROM order_sync_receipt WHERE tenant_id=? AND group_id=?", Long.class, t, c.groupId()) == 0, "尚有回款未迁移");
        for (long id : memberIds) if (!result.containsValue(id)) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_record WHERE tenant_id=? AND order_id=? AND deleted=0", Long.class, t, id) == 0, "旧订单回款未迁完");
            jdbc.update("UPDATE order_sales_order SET deleted=1,paid_amount=0,unpaid_amount=0,source_unpaid_amount=0,revision=revision+1,updated_by=?,updated_time=? WHERE tenant_id=? AND id=?", actor, now, t, id);
            jdbc.update("UPDATE order_sales_order_line SET deleted=1,revision=revision+1,updated_by=?,updated_time=? WHERE tenant_id=? AND order_id=? AND deleted=0", actor, now, t, id);
        }
        jdbc.update("DELETE FROM order_history_group WHERE tenant_id=? AND id=?", t, c.groupId());
        jdbc.update("INSERT INTO order_history_reconciliation_audit(tenant_id,operation_id,group_id,request_json,before_json,result_json,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                t, c.operationId().toString(), c.groupId(), request, JSON.writeValueAsString(before), JSON.writeValueAsString(result), actor, now);
        return result;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String bind(String t, String actor, Bind c) {
        require(
                c != null && c.customerId() > 0 && !c.sources().isEmpty() && !c.orders().isEmpty(),
                "请选择同一门店两边的订单");
        require(c.sources().size() <= 100 && c.orders().size() <= 100, "每组最多100张来源/历史订单");
        HistorySyncRules.evidence(c.evidence());
        String group = UUID.randomUUID().toString();
        BigDecimal left = BigDecimal.ZERO, right = BigDecimal.ZERO;
        Map<String, BigDecimal> l = new TreeMap<>(), r = new TreeMap<>();
        Set<String> sourceKeys = new HashSet<>();
        Set<Long> orderIds = new HashSet<>();
        for (var ref :
                c.sources().stream()
                        .sorted(Comparator.comparing(x -> x.connectorId() + "/" + x.sourceNo()))
                        .toList()) {
            require(sourceKeys.add(ref.connectorId() + "/" + ref.sourceNo()), "来源订单重复选择");
            var s =
                    one(
                            "SELECT * FROM order_sync_source WHERE tenant_id=? AND connector_id=?"
                                + " AND source_no=? FOR UPDATE",
                            t,
                            ref.connectorId().toString(),
                            ref.sourceNo());
            require(
                    num(s, "customer_id") == c.customerId()
                            && num(s, "revision") == ref.revision()
                            && s.get("group_id") == null
                            && !Set.of("NEW", "CANCELLED").contains(text(s, "state")),
                    "来源门店、版本或关联状态不符");
            // 已投影的订货宝单必须先受控排除重复，不能靠新增关联掩盖重复统计。
            require(
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_sales_order WHERE tenant_id=? AND"
                                        + " source_system_code='DINGHUOBAO' AND source_order_no=?"
                                        + " AND deleted=0",
                                    Long.class,
                                    t,
                                    ref.sourceNo())
                            == 0,
                    "该订货宝单已在平台存在，需先核对重复订单及资金引用");
            left = left.add(money(s, "amount"));
            var command = JSON.readValue(text(s, "payload"), SalesOrderCommand.class);
            require(!command.lines().isEmpty(), "来源缺少商品明细，不能仅凭金额绑定");
            for (var line : command.lines())
                merge(
                        l,
                        line.productId() + "/" + line.productVariantId() + "/" + line.unitCode(),
                        line.quantity());
        }
        for (var baseline :
                c.orders().stream().sorted(Comparator.comparingLong(Baseline::orderId)).toList()) {
            require(orderIds.add(baseline.orderId()), "历史订单重复选择");
            var o = order(t, baseline.orderId());
            require(
                    num(o, "customer_id") == c.customerId()
                            && num(o, "revision") == baseline.revision()
                            && "FEISHU".equals(text(o, "source_system_code")),
                    "历史门店、版本或来源不符");
            require(!"CANCELLED".equals(text(o, "order_status_code")), "取消订单不能绑定为有效历史应收");
            require(
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_history_member WHERE tenant_id=?"
                                        + " AND order_id=?",
                                    Long.class,
                                    t,
                                    baseline.orderId())
                            == 0,
                    "历史订单已在其他组中");
            require(
                    baseline.cutoff() != null && !baseline.cutoff().isAfter(Instant.now()),
                    "必须核实历史余额截止时点");
            require(
                    jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM order_payment_record WHERE tenant_id=?"
                                        + " AND order_id=? AND deleted=0 AND payment_time>?",
                                    Long.class,
                                    t,
                                    baseline.orderId(),
                                    ts(baseline.cutoff()))
                            == 0,
                    "历史订单已有截止时点后的回款，必须先核清来源重叠再建期初");
            var opening = HistorySyncRules.money(baseline.openingPaid());
            require(opening.compareTo(money(o, "payable_amount")) <= 0, "期初已收超过应付，需核对");
            BigDecimal unpaid =
                    o.get("source_unpaid_amount") == null
                            ? money(o, "unpaid_amount")
                            : money(o, "source_unpaid_amount");
            HistorySyncRules.equal(
                    opening, money(o, "payable_amount").subtract(unpaid), "期初余额与飞书快照");
            right = right.add(money(o, "payable_amount"));
            var lines =
                    jdbc.queryForList(
                            "SELECT product_id,product_variant_id,unit_code,quantity FROM"
                                + " order_sales_order_line WHERE tenant_id=? AND order_id=? AND"
                                + " deleted=0",
                            t,
                            baseline.orderId());
            require(!lines.isEmpty(), "历史商品明细缺失，不能自动激活关联");
            for (var line : lines)
                merge(
                        r,
                        text(line, "product_id")
                                + "/"
                                + text(line, "product_variant_id")
                                + "/"
                                + text(line, "unit_code"),
                        money(line, "quantity"));
        }
        String differenceReason =
                c.differenceReason() == null ? null : c.differenceReason().strip();
        boolean hasReason =
                differenceReason != null
                        && differenceReason.length() >= 5
                        && differenceReason.length() <= 200;
        // 单位换算/规格口径差异以订货宝为准时，明细结构可以不同，但必须留差异原因；金额口径同理。
        if (!l.equals(r))
            require(hasReason, "商品、规格、单位或数量结构不一致，请填写5至200字的差异原因");
        boolean sameAmount = left.compareTo(right) == 0;
        BigDecimal orderAmountBefore = right;
        if (!sameAmount) {
            require(hasReason, "关联组金额不一致，请填写5至200字的差额原因");
            if (Boolean.TRUE.equals(c.alignOrderAmount())) {
                // 业务确认以订货宝金额为结算口径：原子对齐历史订单金额并保留原始差额审计。
                require(
                        c.sources().size() == 1 && c.orders().size() == 1,
                        "差额金额对齐仅支持一对一关联，拆合单需人工核对");
                var baseline = c.orders().getFirst();
                var o = order(t, baseline.orderId());
                BigDecimal paid = money(o, "paid_amount");
                require(
                        paid.compareTo(HistorySyncRules.money(left)) <= 0,
                        "订单已收超过订货宝金额，不能对齐，需人工核对");
                BigDecimal newUnpaid = HistorySyncRules.money(left).subtract(paid);
                jdbc.update(
                        "UPDATE order_sales_order SET"
                            + " payable_amount=?,unpaid_amount=?,source_unpaid_amount=?,"
                            + "updated_time=?,revision=revision+1 WHERE tenant_id=? AND id=?",
                        HistorySyncRules.money(left),
                        newUnpaid,
                        newUnpaid,
                        ts(Instant.now()),
                        t,
                        baseline.orderId());
                right = left;
            }
        }
        jdbc.update(
                "INSERT INTO"
                    + " order_history_group(tenant_id,id,customer_id,evidence,actor_id,created_at,source_amount,order_amount,difference_amount,difference_reason)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                t,
                group,
                c.customerId(),
                c.evidence(),
                actor,
                ts(Instant.now()),
                auditMoney(left),
                auditMoney(orderAmountBefore),
                auditMoney(left.subtract(orderAmountBefore)),
                differenceReason);
        for (var ref : c.sources())
            jdbc.update(
                    "UPDATE order_sync_source SET group_id=?,state='BOUND',revision=revision+1"
                        + " WHERE tenant_id=? AND connector_id=? AND source_no=?",
                    group,
                    t,
                    ref.connectorId().toString(),
                    ref.sourceNo());
        for (var o : c.orders())
            jdbc.update(
                    "INSERT INTO"
                        + " order_history_member(tenant_id,order_id,group_id,baseline_at,opening_paid)"
                        + " VALUES(?,?,?,?,?)",
                    t,
                    o.orderId(),
                    group,
                    ts(o.cutoff()),
                    o.openingPaid());
        // 已拉取的款保持待核对，复拉相同来源也会重新解析映射，不因checksum相同跳过补关联。
        return group;
    }

    private static void merge(Map<String, BigDecimal> values, String key, BigDecimal quantity) {
        require(quantity != null && quantity.signum() >= 0 && !key.contains("null"), "商品映射或数量缺失");
        values.merge(key, quantity.stripTrailingZeros(), (x, y) -> x.add(y).stripTrailingZeros());
    }

    /** 关联组审计金额：来源快照可能带多于两位的小数，按分四舍五入后记录，原始值保留在差额原因里。 */
    private static BigDecimal auditMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Intake receipt(String t, Receipt c) {
        require(c != null && c.connectorId() != null, "收款来源连接器缺失");
        id(c.receiptNo());
        id(c.checksum());
        HistorySyncRules.money(c.amount());
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sync_receipt WHERE tenant_id=? AND"
                                    + " receipt_no=? AND connector_id<>?",
                                Long.class,
                                t,
                                c.receiptNo(),
                                c.connectorId().toString())
                        == 0,
                "相同回款来源出现在不同连接器，请先核实账号命名空间");
        var sources =
                c.sourceOrderNo() == null
                        ? List.<Map<String, Object>>of()
                        : source(t, c.connectorId(), c.sourceOrderNo(), true);
        String group = sources.isEmpty() ? null : text(sources.getFirst(), "group_id");
        if (!sources.isEmpty() && "CANCELLED".equals(text(sources.getFirst(), "state")))
            return new Intake("ORDER_CANCELLED", group, "订单已取消，回款保留在来源记录中");
        Long customer = c.customerId();
        if (!sources.isEmpty()) {
            long owner = num(sources.getFirst(), "customer_id");
            require(customer != null && customer == owner, "收款客户与来源订单门店不一致或缺少客户映射");
        }
        boolean newSource = !sources.isEmpty() && "NEW".equals(text(sources.getFirst(), "state"));
        if (!sources.isEmpty() && "CONFIRMED".equals(c.status())) {
            var received =
                    jdbc.queryForObject(
                            "SELECT COALESCE(SUM(amount),0) FROM order_sync_receipt WHERE"
                                + " tenant_id=? AND connector_id=? AND source_order_no=? AND"
                                + " receipt_no<>? AND source_status='CONFIRMED'",
                            BigDecimal.class,
                            t,
                            c.connectorId().toString(),
                            c.sourceOrderNo(),
                            c.receiptNo());
            require(
                    received.add(c.amount()).compareTo(money(sources.getFirst(), "amount")) <= 0,
                    "该来源订单累计回款超过来源订单额，请核实重复款或预收款用途");
        }
        String state =
                group == null ? (newSource ? "NEW" : "MAPPING_PENDING") : "ALLOCATION_PENDING";
        if (!sources.isEmpty() && "SOURCE_CHANGED_REVIEW".equals(text(sources.getFirst(), "state")))
            state = "SOURCE_CHANGED_REVIEW";
        if (c.occurredAt() == null) state = "PAYMENT_TIME_REVIEW";
        else if (!"CONFIRMED".equals(c.status()) && !"CANCELLED".equals(c.status())
                && !(newSource && "PENDING".equals(c.status())))
            state = "STATUS_REVIEW";
        var old =
                jdbc.queryForList(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=? FOR UPDATE",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        int revision = 0;
        if (!old.isEmpty()) {
            var v = old.getFirst();
            revision = (int) num(v, "revision") + 1;
            if (v.get("source_updated_at") != null
                    && c.updatedAt() != null
                    && c.updatedAt().isBefore(instant(v.get("source_updated_at"))))
                return new Intake(text(v, "state"), text(v, "group_id"), "旧版本响应已忽略");
            if (c.checksum().equals(text(v, "pending_checksum")))
                return new Intake("SOURCE_CHANGED_REVIEW", text(v, "group_id"), "来源变更待核实，原已入账事实保留");
            boolean confirmedStatusCorrection =
                    "STATUS_REVIEW".equals(text(v, "state"))
                            && "CONFIRMED".equals(c.status())
                            && !"CONFIRMED".equals(text(v, "source_status"));
            if (c.checksum().equals(text(v, "checksum"))
                    && Objects.equals(group, text(v, "group_id"))
                    && !confirmedStatusCorrection) {
                resolveOwner(t, c, customer);
                if (group == null
                        && !sources.isEmpty()
                        && "NEW".equals(text(sources.getFirst(), "state"))
                        && !"BASELINE_COVERED".equals(text(v, "state"))
                        && Set.of("CONFIRMED", "PENDING").contains(c.status())
                        && c.occurredAt() != null) return receiptResult(t, c, "NEW", null);
                return receiptResult(t, c, text(v, "state"), group);
            }
            // 金额、付款日或关联门店变更不能静默挪动既有归属和核销。
            boolean changed =
                    money(v, "amount").compareTo(c.amount()) != 0
                            || !Objects.equals(instant(v.get("occurred_at")), c.occurredAt())
                            || !Objects.equals(text(v, "source_order_no"), c.sourceOrderNo())
                            || !Objects.equals(v.get("customer_id"), customer);
            boolean reinstated =
                    "CANCELLED".equals(text(v, "source_status")) && !"CANCELLED".equals(c.status());
            boolean baselineCancellation =
                    "BASELINE_COVERED".equals(text(v, "state")) && "CANCELLED".equals(c.status());
            boolean statusRegression =
                    "CONFIRMED".equals(text(v, "source_status"))
                            && !Set.of("CONFIRMED", "CANCELLED")
                                    .contains(c.status() == null ? "" : c.status());
            if (changed || reinstated || baselineCancellation || statusRegression) {
                jdbc.update(
                        "UPDATE order_sync_receipt SET"
                            + " pending_payload=?,pending_checksum=?,revision=revision+1 WHERE"
                            + " tenant_id=? AND connector_id=? AND receipt_no=?",
                        JSON.writeValueAsString(c),
                        c.checksum(),
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
                return new Intake(
                        "SOURCE_CHANGED_REVIEW",
                        text(v, "group_id"),
                        "金额、时间、状态或期初覆盖发生变化，已保留原入账事实与新来源，等待核实");
            }
            // 人工对账已覆盖的回款仅更新来源元数据，不能重新激活旧核销或生成第二份回款。
            if ("BASELINE_COVERED".equals(text(v, "state")) && "CONFIRMED".equals(c.status()))
                state = "BASELINE_COVERED";
            jdbc.update(
                    "INSERT INTO"
                        + " order_sync_receipt_revision(tenant_id,connector_id,receipt_no,revision,payload,created_at)"
                        + " VALUES(?,?,?,?,?,?)",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo(),
                    revision,
                    JSON.writeValueAsString(v),
                    ts(Instant.now()));
            jdbc.update(
                    "UPDATE order_sync_receipt SET"
                        + " source_order_no=?,customer_id=?,group_id=?,amount=?,occurred_at=?,source_updated_at=?,source_status=?,checksum=?,state=?,revision=?"
                        + " WHERE tenant_id=? AND connector_id=? AND receipt_no=?",
                    c.sourceOrderNo(),
                    customer,
                    group,
                    c.amount(),
                    ts(c.occurredAt()),
                    ts(c.updatedAt()),
                    c.status(),
                    c.checksum(),
                    state,
                    revision,
                    t,
                    c.connectorId().toString(),
                    c.receiptNo());
        } else {
            jdbc.update(
                    "INSERT INTO"
                        + " order_sync_receipt(tenant_id,connector_id,receipt_no,source_order_no,customer_id,group_id,amount,occurred_at,source_updated_at,source_status,checksum,state)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo(),
                    c.sourceOrderNo(),
                    customer,
                    group,
                    c.amount(),
                    ts(c.occurredAt()),
                    ts(c.updatedAt()),
                    c.status(),
                    c.checksum(),
                    state);
        }
        resolveOwner(t, c, customer);
        if ("CANCELLED".equals(c.status())) {
            var allocations =
                    jdbc.queryForList(
                            "SELECT order_id FROM order_sync_allocation WHERE tenant_id=? AND"
                                + " connector_id=? AND receipt_no=? ORDER BY order_id",
                            t,
                            c.connectorId().toString(),
                            c.receiptNo());
            jdbc.update(
                    "UPDATE order_sync_receipt SET state='CANCELLED' WHERE tenant_id=? AND"
                        + " connector_id=? AND receipt_no=?",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo());
            for (var a : allocations) {
                jdbc.update(
                        "UPDATE order_payment_record SET"
                            + " deleted=1,updated_time=?,revision=revision+1 WHERE tenant_id=? AND"
                            + " id IN (SELECT payment_id FROM order_sync_allocation WHERE"
                            + " tenant_id=? AND connector_id=? AND receipt_no=?) AND deleted=0",
                        ts(Instant.now()),
                        t,
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
                refresh(t, num(a, "order_id"));
            }
            if (newSource) {
                for (var p :
                        jdbc.queryForList(
                                "SELECT id,revision,customer_id FROM order_payment_record WHERE"
                                    + " tenant_id=? AND connector_id=? AND"
                                    + " source_system_code='DINGHUOBAO' AND source_document_no=?"
                                    + " AND deleted=0",
                                t,
                                c.connectorId().toString(),
                                c.receiptNo())) {
                    require(customer != null && num(p, "customer_id") == customer, "撤销回款的客户快照不一致");
                    payments.delete(t, num(p, "id"), (int) num(p, "revision"), "SYSTEM");
                }
            }
            return new Intake("CANCELLED", group, null);
        }
        if ("BASELINE_COVERED".equals(state)) return receiptResult(t, c, state, group);
        if (group == null
                && !sources.isEmpty()
                && "NEW".equals(text(sources.getFirst(), "state"))
                && Set.of("CONFIRMED", "PENDING").contains(c.status())
                && c.occurredAt() != null) return receiptResult(t, c, "NEW", null);
        if (group != null
                && "ALLOCATION_PENDING".equals(state)
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sync_allocation WHERE tenant_id=? AND"
                                    + " connector_id=? AND receipt_no=?",
                                Long.class,
                                t,
                                c.connectorId().toString(),
                                c.receiptNo())
                        > 0) {
            jdbc.update(
                    "UPDATE order_sync_receipt SET state='ALLOCATED' WHERE tenant_id=? AND"
                        + " connector_id=? AND receipt_no=?",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo());
            return receiptResult(t, c, "ALLOCATED", group);
        }
        if (group != null && "ALLOCATION_PENDING".equals(state)) {
            var members =
                    jdbc.queryForList(
                            "SELECT * FROM order_history_member WHERE tenant_id=? AND group_id=?",
                            t,
                            group);
            if (members.size() == 1) {
                var m = members.getFirst();
                if (!c.occurredAt().isAfter(instant(m.get("baseline_at"))))
                    state = "BASELINE_COVERED";
                else {
                    tryAllocate(
                            t,
                            "SYSTEM",
                            new Allocate(
                                    c.connectorId(),
                                    c.receiptNo(),
                                    jdbc.queryForObject(
                                            "SELECT revision FROM order_sync_receipt WHERE"
                                                + " tenant_id=? AND connector_id=? AND"
                                                + " receipt_no=?",
                                            Integer.class,
                                            t,
                                            c.connectorId().toString(),
                                            c.receiptNo()),
                                    List.of(new Allocation(num(m, "order_id"), c.amount())),
                                    "确定的一对一或拆单关联自动核销"));
                    state = "ALLOCATED";
                }
                jdbc.update(
                        "UPDATE order_sync_receipt SET state=? WHERE tenant_id=? AND connector_id=?"
                            + " AND receipt_no=?",
                        state,
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
            }
        }
        return new Intake(
                state, group, "ALLOCATION_PENDING".equals(state) ? "已归门店组，待确认组内核销" : "回款来源已保存");
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void allocate(String t, String actor, Allocate c) {
        HistorySyncRules.evidence(c.evidence());
        tryAllocate(t, actor, c);
    }

    private void tryAllocate(String t, String actor, Allocate c) {
        var receipt =
                one(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=? FOR UPDATE",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        require(
                num(receipt, "revision") == c.revision()
                        && "CONFIRMED".equals(text(receipt, "source_status"))
                        && receipt.get("group_id") != null
                        && receipt.get("occurred_at") != null
                        && receipt.get("pending_payload") == null
                        && !"SOURCE_CHANGED_REVIEW".equals(text(receipt, "state")),
                "收款版本、状态或关联未确认");
        require(!c.allocations().isEmpty(), "核销分配不能为空");
        BigDecimal sum = BigDecimal.ZERO;
        Set<Long> ids = new HashSet<>();
        for (var a :
                c.allocations().stream()
                        .sorted(Comparator.comparingLong(Allocation::orderId))
                        .toList()) {
            require(ids.add(a.orderId()), "同一订单不能重复分配");
            var m =
                    one(
                            "SELECT * FROM order_history_member WHERE tenant_id=? AND order_id=?"
                                + " AND group_id=?",
                            t,
                            a.orderId(),
                            receipt.get("group_id"));
            require(
                    instant(receipt.get("occurred_at")).isAfter(instant(m.get("baseline_at"))),
                    "该款已在历史期初覆盖范围，不能再次核销");
            var o = order(t, a.orderId());
            var amount = HistorySyncRules.money(a.amount());
            BigDecimal other =
                    jdbc.queryForObject(
                            "SELECT COALESCE(SUM(a.amount),0) FROM order_sync_allocation a JOIN"
                                + " order_sync_receipt r ON r.tenant_id=a.tenant_id AND"
                                + " r.connector_id=a.connector_id AND r.receipt_no=a.receipt_no"
                                + " WHERE a.tenant_id=? AND a.order_id=? AND"
                                + " r.source_status='CONFIRMED' AND NOT(a.connector_id=? AND"
                                + " a.receipt_no=?)",
                            BigDecimal.class,
                            t,
                            a.orderId(),
                            c.connectorId().toString(),
                            c.receiptNo());
            require(
                    money(m, "opening_paid")
                                    .add(other)
                                    .add(amount)
                                    .compareTo(money(o, "payable_amount"))
                            <= 0,
                    "核销超过订单剩余应收，需核对重复款或期初");
            sum = sum.add(amount);
        }
        HistorySyncRules.equal(sum, money(receipt, "amount"), "回款分配");
        // 已核销款重分配须先走显式冲正，禁止覆盖既有分配历史。
        long old =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sync_allocation WHERE tenant_id=? AND"
                            + " connector_id=? AND receipt_no=?",
                        Long.class,
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        require(old == 0, "该款已有核销，重复请求不会追加；如需更正请先核对冲正");
        for (var a : c.allocations())
            jdbc.update(
                    "INSERT INTO"
                        + " order_sync_allocation(tenant_id,connector_id,receipt_no,order_id,amount,evidence,actor_id,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo(),
                    a.orderId(),
                    a.amount(),
                    c.evidence(),
                    actor,
                    ts(Instant.now()));
        jdbc.update(
                "UPDATE order_sync_receipt SET state='ALLOCATED',revision=revision+1 WHERE"
                    + " tenant_id=? AND connector_id=? AND receipt_no=?",
                t,
                c.connectorId().toString(),
                c.receiptNo());
        for (var a : c.allocations()) {
            projectAllocation(t, c, a, receipt);
            refresh(t, a.orderId());
        }
    }

    private void refresh(String t, long id) {
        var o = order(t, id);
        var m = one("SELECT * FROM order_history_member WHERE tenant_id=? AND order_id=?", t, id);
        BigDecimal added =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(a.amount),0) FROM order_sync_allocation a JOIN"
                            + " order_sync_receipt r ON r.tenant_id=a.tenant_id AND"
                            + " r.connector_id=a.connector_id AND r.receipt_no=a.receipt_no WHERE"
                            + " a.tenant_id=? AND a.order_id=? AND r.source_status='CONFIRMED' AND"
                            + " r.state IN ('ALLOCATED','ALLOCATION_PENDING')",
                        BigDecimal.class,
                        t,
                        id);
        BigDecimal paid = money(m, "opening_paid").add(added),
                unpaid = money(o, "payable_amount").subtract(paid);
        String status =
                paid.signum() == 0 ? "UNPAID" : unpaid.signum() == 0 ? "PAID" : "PARTIAL_PAID";
        jdbc.update(
                "UPDATE order_sales_order SET"
                    + " paid_amount=?,unpaid_amount=?,payment_status_code=?,updated_time=?,revision=revision+1"
                    + " WHERE tenant_id=? AND id=?",
                paid,
                unpaid,
                status,
                ts(Instant.now()),
                t,
                id);
    }

    private Intake receiptResult(String t, Receipt c, String state, String group) {
        var receipt =
                one(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=?",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        return new Intake(
                state, group, null, text(receipt, "employee_code"), text(receipt, "employee_name"));
    }

    /** 已确认核销生成独立可追溯回款明细；一笔合单款分配多订单时总金额守恒。 */
    private void projectAllocation(
            String t, Allocate c, Allocation a, Map<String, Object> receipt) {
        var o = order(t, a.orderId());
        long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        String no = "HS" + id;
        jdbc.update(
                "INSERT INTO"
                    + " order_payment_record(id,tenant_id,payment_no,connector_id,source_system_code,source_document_no,order_id,sales_order_no_snapshot,customer_id,customer_code_snapshot,customer_name_snapshot,collector_staff_code,collector_name_snapshot,payment_time,paid_amount,remark,created_by,updated_by,created_time,updated_time,source_record_id,payment_status_code,revision,deleted)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CHECKED',1,0)",
                id,
                t,
                no,
                c.connectorId().toString(),
                "DINGHUOBAO",
                c.receiptNo(),
                a.orderId(),
                o.get("order_no"),
                o.get("customer_id"),
                o.get("customer_code_snapshot"),
                o.get("customer_name_snapshot"),
                receipt.get("employee_code"),
                receipt.get("employee_name"),
                receipt.get("occurred_at"),
                a.amount(),
                "历史关联组核销：" + c.evidence(),
                "SYSTEM",
                "SYSTEM",
                ts(Instant.now()),
                ts(Instant.now()),
                c.receiptNo());
        jdbc.update(
                "UPDATE order_sync_allocation SET payment_id=? WHERE tenant_id=? AND connector_id=?"
                    + " AND receipt_no=? AND order_id=?",
                id,
                t,
                c.connectorId().toString(),
                c.receiptNo(),
                a.orderId());
    }

    private void projectOwner(String t, UUID connector, String receipt) {
        var row =
                one(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=?",
                        t,
                        connector.toString(),
                        receipt);
        if (row.get("employee_code") == null || row.get("pending_payload") != null) return;
        jdbc.update(
                "UPDATE order_payment_record SET"
                    + " collector_staff_code=?,collector_name_snapshot=?,updated_time=?,revision=revision+1"
                    + " WHERE tenant_id=? AND connector_id=? AND source_system_code='DINGHUOBAO'"
                    + " AND source_document_no=? AND customer_id=? AND payment_time=? AND"
                    + " collector_staff_code IS NULL AND deleted=0",
                row.get("employee_code"),
                row.get("employee_name"),
                ts(Instant.now()),
                t,
                connector.toString(),
                receipt,
                row.get("customer_id"),
                row.get("occurred_at"));
    }

    /** 产品核销必须对应已确认订单核销，按每个原订单分别守恒，不推测合单款用途。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void allocateProducts(String t, String actor, AllocateProducts c) {
        HistorySyncRules.evidence(c.evidence());
        require(c.connectorId() != null && !c.allocations().isEmpty(), "请选择回款产品明细");
        var receipt =
                one(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=? FOR UPDATE",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        require(
                num(receipt, "revision") == c.revision()
                        && Set.of("ALLOCATED", "NEW").contains(text(receipt, "state"))
                        && "CONFIRMED".equals(text(receipt, "source_status"))
                        && receipt.get("pending_payload") == null,
                "请先完成订单核销且核清来源变更");
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_sync_product_allocation WHERE"
                                    + " tenant_id=? AND connector_id=? AND receipt_no=?",
                                Long.class,
                                t,
                                c.connectorId().toString(),
                                c.receiptNo())
                        == 0,
                "本笔产品核销已存在，不能重复追加");
        var expected = new TreeMap<Long, BigDecimal>();
        for (var a :
                jdbc.queryForList(
                        "SELECT order_id,amount FROM order_sync_allocation WHERE tenant_id=? AND"
                            + " connector_id=? AND receipt_no=?",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo())) expected.put(num(a, "order_id"), money(a, "amount"));
        if ("NEW".equals(text(receipt, "state")))
            for (var a :
                    jdbc.queryForList(
                            "SELECT order_id,paid_amount amount FROM order_payment_record WHERE"
                                + " tenant_id=? AND connector_id=? AND"
                                + " source_system_code='DINGHUOBAO' AND source_document_no=? AND"
                                + " customer_id=? AND payment_time=? AND deleted=0",
                            t,
                            c.connectorId().toString(),
                            c.receiptNo(),
                            receipt.get("customer_id"),
                            receipt.get("occurred_at")))
                expected.merge(num(a, "order_id"), money(a, "amount"), BigDecimal::add);
        HistorySyncRules.equal(
                expected.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                money(receipt, "amount"),
                "回款对应订单");
        var actual = new TreeMap<Long, BigDecimal>();
        var ids = new HashSet<Long>();
        for (var a : c.allocations()) {
            require(ids.add(a.lineId()), "产品明细重复");
            one(
                    "SELECT id FROM order_sales_order_line WHERE tenant_id=? AND order_id=? AND"
                        + " id=? AND deleted=0",
                    t,
                    a.orderId(),
                    a.lineId());
            var amount = HistorySyncRules.money(a.amount());
            require(amount.signum() > 0, "核销产品金额必须大于0");
            actual.merge(a.orderId(), amount, BigDecimal::add);
        }
        require(expected.keySet().equals(actual.keySet()), "产品核销不能跨出该笔订单分配范围");
        for (var e : expected.entrySet())
            HistorySyncRules.equal(e.getValue(), actual.get(e.getKey()), "订单产品分配");
        for (var a : c.allocations())
            jdbc.update(
                    "INSERT INTO"
                        + " order_sync_product_allocation(tenant_id,connector_id,receipt_no,order_id,line_id,amount,evidence,actor_id,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    t,
                    c.connectorId().toString(),
                    c.receiptNo(),
                    a.orderId(),
                    a.lineId(),
                    a.amount(),
                    c.evidence(),
                    actor,
                    ts(Instant.now()));
        jdbc.update(
                "UPDATE order_sync_receipt SET revision=revision+1 WHERE tenant_id=? AND"
                    + " connector_id=? AND receipt_no=?",
                t,
                c.connectorId().toString(),
                c.receiptNo());
    }

    /** 月交易按订单日和订单销售；月回款按实际付款日及逐笔固化的回款销售。 */
    public Performance performance(String t, String month) {
        YearMonth m = YearMonth.parse(month);
        var zone = ZoneId.of("Asia/Shanghai");
        var from = ts(m.atDay(1).atStartOfDay(zone).toInstant());
        var to = ts(m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        var sales =
                jdbc.queryForList(
                        "SELECT owner_employee_code employee_code,MAX(owner_employee_name_snapshot)"
                            + " employee_name,COUNT(*) order_count,SUM(payable_amount) amount FROM"
                            + " order_sales_order WHERE tenant_id=? AND deleted=0 AND"
                            + " order_status_code<>'CANCELLED' AND order_date>=? AND order_date<?"
                            + " GROUP BY owner_employee_code",
                        t,
                        from,
                        to);
        var receipts =
                jdbc.queryForList(
                        "SELECT p.collector_staff_code employee_code,MAX(p.collector_name_snapshot)"
                            + " employee_name,SUM(p.paid_amount) amount FROM order_payment_record p"
                            + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id"
                            + " WHERE p.tenant_id=? AND p.deleted=0 AND o.deleted=0"
                            + " AND o.order_status_code<>'CANCELLED'"
                            + " AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time>=? AND"
                            + " p.payment_time<? GROUP BY p.collector_staff_code",
                        t,
                        from,
                        to);
        var products =
                jdbc.queryForList(
                        "SELECT l.product_id,l.product_variant_id,MAX(l.product_name_snapshot)"
                            + " product_name,MAX(l.specification_snapshot)"
                            + " specification,SUM(l.line_amount) amount FROM order_sales_order_line"
                            + " l JOIN order_sales_order o ON o.tenant_id=l.tenant_id AND"
                            + " o.id=l.order_id WHERE o.tenant_id=? AND o.deleted=0 AND l.deleted=0"
                            + " AND o.order_status_code<>'CANCELLED' AND o.order_date>=? AND"
                            + " o.order_date<? GROUP BY l.product_id,l.product_variant_id",
                        t,
                        from,
                        to);
        var productReceipts =
                jdbc.queryForList(
                        "SELECT l.product_id,l.product_variant_id,MAX(l.product_name_snapshot)"
                            + " product_name,MAX(l.specification_snapshot)"
                            + " specification,SUM(a.amount) amount FROM"
                            + " order_sync_product_allocation a JOIN order_sync_receipt r ON"
                            + " r.tenant_id=a.tenant_id AND r.connector_id=a.connector_id AND"
                            + " r.receipt_no=a.receipt_no JOIN order_sales_order_line l ON"
                            + " l.tenant_id=a.tenant_id AND l.id=a.line_id JOIN order_sales_order o ON"
                            + " o.tenant_id=l.tenant_id AND o.id=l.order_id WHERE a.tenant_id=? AND"
                            + " l.deleted=0 AND o.deleted=0 AND o.order_status_code<>'CANCELLED' AND"
                            + " r.source_status='CONFIRMED' AND r.occurred_at>=? AND"
                            + " r.occurred_at<? GROUP BY l.product_id,l.product_variant_id",
                        t,
                        from,
                        to);
        var pending =
                one(
                        "SELECT COUNT(*) receipt_count,COALESCE(SUM(CASE WHEN employee_code IS NULL"
                            + " THEN amount ELSE 0 END),0) owner_pending_amount,COALESCE(SUM(CASE"
                            + " WHEN state NOT IN"
                            + " ('ALLOCATED','BASELINE_COVERED','NEW','CANCELLED') THEN amount ELSE"
                            + " 0 END),0) allocation_pending_amount,COALESCE(SUM(CASE WHEN"
                            + " pending_payload IS NOT NULL THEN amount ELSE 0 END),0)"
                            + " changed_review_amount FROM order_sync_receipt WHERE tenant_id=? AND"
                            + " source_status<>'CANCELLED' AND occurred_at>=? AND occurred_at<?",
                        t,
                        from,
                        to);
        pending.put(
                "product_pending_amount",
                jdbc.queryForObject(
                                "SELECT COALESCE(SUM(p.paid_amount),0) FROM order_payment_record p"
                                    + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id"
                                    + " WHERE p.tenant_id=? AND p.deleted=0 AND o.deleted=0"
                                    + " AND o.order_status_code<>'CANCELLED'"
                                    + " AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.payment_time>=?"
                                    + " AND p.payment_time<?",
                                BigDecimal.class,
                                t,
                                from,
                                to)
                        .subtract(
                                productReceipts.stream()
                                        .map(v -> money(v, "amount"))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
        pending.put(
                "order_level_adjustment",
                sales.stream()
                        .map(v -> money(v, "amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .subtract(
                                products.stream()
                                        .map(v -> money(v, "amount"))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
        return new Performance(
                sales, receipts, safeIds(products), safeIds(productReceipts), pending);
    }

    private void resolveOwner(String t, Receipt c, Long customer) {
        if (customer == null || c.occurredAt() == null) return;
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sync_receipt WHERE tenant_id=? AND"
                            + " connector_id=? AND receipt_no=? AND employee_code IS NOT NULL",
                        Long.class,
                        t,
                        c.connectorId().toString(),
                        c.receiptNo())
                > 0) return;
        try {
            var owner =
                    owners.paymentOwner(
                            com.rigour.shared.context.AuthorizationContext.requireCurrent(),
                            customer,
                            c.occurredAt());
            if (owner != null && owner.usable())
                jdbc.update(
                        "UPDATE order_sync_receipt SET"
                            + " employee_code=?,employee_name=?,owner_evidence=?,owner_actor='SYSTEM',revision=revision+1"
                            + " WHERE tenant_id=? AND connector_id=? AND receipt_no=? AND"
                            + " employee_code IS NULL",
                        owner.employeeCode(),
                        owner.employeeName(),
                        owner.evidence(),
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
            projectOwner(t, c.connectorId(), c.receiptNo());
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "回款归属读取未完成，保留待确认 tenant={} customer={} errorType={}",
                            t,
                            customer,
                            failure.getClass().getSimpleName());
        }
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void confirmOwner(String t, String actor, OwnerReview c) {
        HistorySyncRules.evidence(c.evidence());
        id(c.employeeCode());
        require(c.employeeCode().length() <= 50, "员工编码过长");
        require(c.employeeName() != null && c.employeeName().length() <= 100, "请填写核实的员工姓名");
        var receipt =
                one(
                        "SELECT * FROM order_sync_receipt WHERE tenant_id=? AND connector_id=? AND"
                            + " receipt_no=? FOR UPDATE",
                        t,
                        c.connectorId().toString(),
                        c.receiptNo());
        require(
                num(receipt, "revision") == c.revision()
                        && receipt.get("employee_code") == null
                        && receipt.get("customer_id") != null
                        && receipt.get("occurred_at") != null,
                "归属已冻结或缺少回款门店/时间，不能覆盖");
        jdbc.update(
                "UPDATE order_sync_receipt SET"
                    + " employee_code=?,employee_name=?,owner_evidence=?,owner_actor=?,revision=revision+1"
                    + " WHERE tenant_id=? AND connector_id=? AND receipt_no=?",
                c.employeeCode(),
                c.employeeName(),
                c.evidence(),
                actor,
                t,
                c.connectorId().toString(),
                c.receiptNo());
        projectOwner(t, c.connectorId(), c.receiptNo());
    }
    private static boolean sameSourceLines(
            java.util.List<com.rigour.order.api.v1.model.SalesOrderLineCommand> left,
            java.util.List<com.rigour.order.api.v1.model.SalesOrderLineCommand> right) {
        return left.stream().map(JdbcOrderHistorySyncStore::businessLine).toList()
                .equals(right.stream().map(JdbcOrderHistorySyncStore::businessLine).toList());
    }

    private static com.rigour.order.api.v1.model.SalesOrderLineCommand businessLine(
            com.rigour.order.api.v1.model.SalesOrderLineCommand line) {
        return new com.rigour.order.api.v1.model.SalesOrderLineCommand(line.productId(), line.productVariantId(),
                line.productCodeSnapshot(), line.skuCodeSnapshot(), line.productNameSnapshot(),
                line.specificationSnapshot(), line.unitCode(), line.quantity(), line.unitPrice(),
                line.discountRate(), line.discountAmount(), line.remark());
    }

}

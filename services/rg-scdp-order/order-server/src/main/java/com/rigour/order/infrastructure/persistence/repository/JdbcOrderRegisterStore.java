package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.OrderRegisterModels;
import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.NumberMappingView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingResult;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.order.application.port.out.OrderRegisterStore.ApplyNumberMapping;
import com.rigour.order.application.port.out.OrderRegisterStore.LineCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.NumberMappingCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PaymentCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PeriodCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.ReceivablesCriteria;
import com.rigour.order.domain.sync.HistorySyncRules;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 订单登记读取仓储；直接用现有业务表查询，金额按主键聚合，禁止三表展开求和。 */
@Repository
public class JdbcOrderRegisterStore implements OrderRegisterStore {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;

    public JdbcOrderRegisterStore(JdbcTemplate jdbc, OrderDataScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    @Override
    public Optional<OrderRegisterOrderView> findOrder(String tenantId, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return Optional.empty();
        return orders(
                        tenantId,
                        0,
                        1,
                        new OrderCriteria(
                                orderNo, null, null, null, null, null, null, null, null, null, null, null,
                                null, null))
                .items()
                .stream()
                .findFirst();
    }

    @Override
    public OrderRegisterPage<OrderRegisterOrderView> orders(
            String tenantId, int begin, int step, OrderCriteria criteria) {
        var where = orderWhere(tenantId, criteria);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order o "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        Long.class,
                        where.args().toArray());
        var items =
                jdbc.query(
                        "SELECT o.id,o.order_no,m.internal_order_no,m.state AS mapping_state,"
                                + " o.source_system_code,o.source_order_no,"
                                + " COALESCE((SELECT MAX(dhb.dhb_order_no) FROM order_number_mapping dhb WHERE dhb.tenant_id=o.tenant_id AND dhb.internal_order_no=o.order_no AND dhb.state='ACTIVE' AND dhb.deleted=0), CASE WHEN o.source_system_code='DINGHUOBAO' THEN o.source_order_no END) AS dhb_order_no,"
                                + " o.customer_id,"
                                + " o.customer_code_snapshot,o.customer_name_snapshot,"
                                + " COALESCE(snap.region_code,o.region_code) AS region_code,"
                                + " COALESCE(snap.employee_code,o.owner_employee_code) AS owner_employee_code,"
                                + " COALESCE(snap.employee_name,o.owner_employee_name_snapshot) AS owner_employee_name,"
                                + " snap.department_id,snap.department_name,"
                                + " o.order_status_code,o.payment_status_code,o.data_quality_status_code,"
                                + " o.original_amount,"
                                + " o.payable_amount,o.paid_amount,o.unpaid_amount,"
                                + " COALESCE(pc.checked_amount,0) AS checked_amount,"
                                + " o.order_date,o.shipment_time,o.created_by,o.created_time,"
                                + " o.updated_by,o.updated_time,o.synced_by,o.synced_at,o.revision,"
                                + " o.source_creator_name,o.source_created_at,o.source_modifier_name,o.source_updated_at "
                                + " FROM order_sales_order o "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " LEFT JOIN order_number_mapping m ON m.tenant_id=o.tenant_id AND m.dhb_order_no=o.order_no AND m.state='ACTIVE' AND m.deleted=0 "
                                + " LEFT JOIN (SELECT tenant_id,order_id,SUM(paid_amount) AS checked_amount "
                                + "             FROM order_payment_record "
                                + "             WHERE payment_status_code='CHECKED' AND deleted=0 "
                                + "             GROUP BY tenant_id,order_id) pc ON pc.tenant_id=o.tenant_id AND pc.order_id=o.id "
                                + " WHERE "
                                + where.sql()
                                + " ORDER BY o.order_date DESC,o.id DESC LIMIT ? OFFSET ?",
                        (rs, i) ->
                                new OrderRegisterOrderView(
                                        rs.getLong("id"),
                                        rs.getString("order_no"),
                                        rs.getString("internal_order_no"),
                                        rs.getString("source_system_code"),
                                        rs.getString("source_order_no"),
                                        rs.getString("dhb_order_no"),
                                        orderNumberState(
                                                rs.getString("source_system_code"),
                                                rs.getString("mapping_state")),
                                        nullableLong(rs, "customer_id"),
                                        rs.getString("customer_code_snapshot"),
                                        rs.getString("customer_name_snapshot"),
                                        rs.getString("region_code"),
                                        null,
                                        rs.getString("owner_employee_code"),
                                        rs.getString("owner_employee_name"),
                                        nullableLong(rs, "department_id"),
                                        rs.getString("department_name"),
                                        rs.getString("order_status_code"),
                                        rs.getString("payment_status_code"),
                                        rs.getString("data_quality_status_code"),
                                        null,
                                        null,
                                        decimal(rs, "original_amount"),
                                        decimal(rs, "payable_amount"),
                                        decimal(rs, "paid_amount"),
                                        decimal(rs, "unpaid_amount"),
                                        decimal(rs, "checked_amount"),
                                        instant(rs, "order_date"),
                                        instant(rs, "shipment_time"),
                                        first(rs.getString("source_creator_name"), rs.getString("created_by")),
                                        firstInstant(rs, "source_created_at", "created_time"),
                                        first(rs.getString("source_modifier_name"), rs.getString("updated_by")),
                                        firstInstant(rs, "source_updated_at", "updated_time"),
                                        rs.getString("synced_by"),
                                        instant(rs, "synced_at"),
                                        rs.getInt("revision")),
                        append(where.args(), step, begin).toArray());
        Map<String, BigDecimal> totals = orderTotals(tenantId, criteria);
        return new OrderRegisterPage<>(total, begin, step, items, totals, historyCoverage(tenantId));
    }

    @Override
    public OrderRegisterPage<OrderRegisterLineView> lines(
            String tenantId, int begin, int step, LineCriteria criteria) {
        var where = lineWhere(tenantId, criteria);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order_line l "
                                + " JOIN order_sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        Long.class,
                        where.args().toArray());
        var items =
                jdbc.query(
                        "SELECT l.id,l.order_id,l.line_no,l.product_id,l.product_variant_id,"
                                + " l.product_code_snapshot,l.sku_code_snapshot,l.product_name_snapshot,"
                                + " l.specification_snapshot,l.unit_code,l.quantity,l.unit_price,"
                                + " l.line_amount,l.revision,o.order_no,o.source_order_no,"
                                + " COALESCE((SELECT MAX(dhb.dhb_order_no) FROM order_number_mapping dhb WHERE dhb.tenant_id=o.tenant_id AND dhb.internal_order_no=o.order_no AND dhb.state='ACTIVE' AND dhb.deleted=0), CASE WHEN o.source_system_code='DINGHUOBAO' THEN o.source_order_no END) AS dhb_order_no,"
                                + " o.customer_id,"
                                + " o.customer_code_snapshot,o.customer_name_snapshot,"
                                + " COALESCE(snap.region_code,o.region_code) AS region_code,"
                                + " COALESCE(snap.employee_code,o.owner_employee_code) AS owner_employee_code,"
                                + " COALESCE(snap.employee_name,o.owner_employee_name_snapshot) AS owner_employee_name,"
                                + " snap.department_id,snap.department_name,o.order_status_code,o.order_date,"
                                + " o.created_by,o.created_time,o.updated_by,o.updated_time,"
                                + " o.source_creator_name,o.source_created_at,o.source_modifier_name,o.source_updated_at,"
                                + " o.synced_by,o.synced_at "
                                + " FROM order_sales_order_line l "
                                + " JOIN order_sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " ORDER BY o.order_date DESC,l.id DESC LIMIT ? OFFSET ?",
                        (rs, i) ->
                                new OrderRegisterLineView(
                                        rs.getLong("id"),
                                        rs.getLong("order_id"),
                                        rs.getInt("line_no"),
                                        null,
                                        nullableLong(rs, "product_id"),
                                        nullableLong(rs, "product_variant_id"),
                                        rs.getString("product_code_snapshot"),
                                        rs.getString("sku_code_snapshot"),
                                        rs.getString("product_name_snapshot"),
                                        rs.getString("specification_snapshot"),
                                        rs.getString("unit_code"),
                                        decimal(rs, "quantity"),
                                        decimal(rs, "unit_price"),
                                        decimal(rs, "line_amount"),
                                        rs.getString("order_no"),
                                        rs.getString("source_order_no"),
                                        rs.getString("dhb_order_no"),
                                        nullableLong(rs, "customer_id"),
                                        rs.getString("customer_code_snapshot"),
                                        rs.getString("customer_name_snapshot"),
                                        rs.getString("region_code"),
                                        null,
                                        rs.getString("owner_employee_code"),
                                        rs.getString("owner_employee_name"),
                                        nullableLong(rs, "department_id"),
                                        rs.getString("department_name"),
                                        rs.getString("order_status_code"),
                                        instant(rs, "order_date"),
                                        rs.getInt("revision"),
                                        first(rs.getString("source_creator_name"), rs.getString("created_by")),
                                        firstInstant(rs, "source_created_at", "created_time"),
                                        first(rs.getString("source_modifier_name"), rs.getString("updated_by")),
                                        firstInstant(rs, "source_updated_at", "updated_time"),
                                        rs.getString("synced_by"),
                                        instant(rs, "synced_at")),
                        append(where.args(), step, begin).toArray());
        var totals =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(l.line_amount),0) AS line_amount,"
                                + " COUNT(DISTINCT COALESCE(CAST(o.customer_id AS CHAR), o.customer_name_snapshot)) AS customer_count,"
                                + " COUNT(DISTINCT COALESCE(CAST(l.product_id AS CHAR), l.product_code_snapshot)) AS product_count,"
                                + " COALESCE(SUM(l.quantity),0) AS quantity_sum "
                                + " FROM order_sales_order_line l "
                                + " JOIN order_sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        (rs, i) ->
                                Map.of(
                                        "lineAmount", rs.getBigDecimal("line_amount"),
                                        "customerCount", BigDecimal.valueOf(rs.getLong("customer_count")),
                                        "productCount", BigDecimal.valueOf(rs.getLong("product_count")),
                                        "quantitySum", rs.getBigDecimal("quantity_sum")),
                        where.args().toArray());
        // 订单金额按命中的订单去重后取折后应收合计，与明细金额（折前）并列展示，二者口径不同。
        BigDecimal orderAmount =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(x.payable_amount),0) FROM order_sales_order x WHERE x.id IN ("
                                + " SELECT o.id FROM order_sales_order_line l "
                                + " JOIN order_sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + ") LIMIT 1",
                        BigDecimal.class,
                        where.args().toArray());
        Map<String, BigDecimal> resultTotals = new LinkedHashMap<>(totals == null ? Map.of() : totals);
        resultTotals.put("orderAmount", orderAmount);
        return new OrderRegisterPage<>(
                total,
                begin,
                step,
                items,
                Map.copyOf(resultTotals),
                historyCoverage(tenantId));
    }

    @Override
    public OrderRegisterPage<OrderRegisterPaymentView> payments(
            String tenantId, int begin, int step, PaymentCriteria criteria) {
        var where = paymentWhere(tenantId, criteria);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_payment_record p "
                                + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        Long.class,
                        where.args().toArray());
        var items =
                jdbc.query(
                        "SELECT p.id,p.payment_no,p.source_record_id,p.order_id,o.order_no,"
                                + " COALESCE((SELECT MAX(dhb.dhb_order_no) FROM order_number_mapping dhb WHERE dhb.tenant_id=o.tenant_id AND dhb.internal_order_no=o.order_no AND dhb.state='ACTIVE' AND dhb.deleted=0), CASE WHEN o.source_system_code='DINGHUOBAO' THEN o.source_order_no END) AS dhb_order_no,"
                                + " p.customer_id,p.customer_code_snapshot,p.customer_name_snapshot,"
                                + " COALESCE(snap.region_code,o.region_code) AS region_code,"
                                + " COALESCE(snap.employee_code,o.owner_employee_code) AS owner_employee_code,"
                                + " COALESCE(snap.employee_name,o.owner_employee_name_snapshot) AS owner_employee_name,"
                                + " snap.department_id,snap.department_name,o.order_date,o.payable_amount,"
                                + " p.paid_amount,p.payment_status_code,p.payment_time,p.transaction_no,"
                                + " p.voucher_keys_json,p.created_by,p.created_time,p.updated_by,p.updated_time,"
                                + " p.synced_by,p.synced_at,p.checked_by,p.checked_at,p.revision,"
                                + " o.source_creator_name,o.source_created_at,o.source_modifier_name,o.source_updated_at "
                                + " FROM order_payment_record p "
                                + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + paymentOrderBy(criteria)
                                + " LIMIT ? OFFSET ?",
                        JdbcOrderRegisterStore::paymentRow,
                        append(where.args(), step, begin).toArray());
        var totals = paymentTotals(tenantId, criteria);
        return new OrderRegisterPage<>(total, begin, step, items, totals, historyCoverage(tenantId));
    }

    @Override
    public PeriodStatisticsView periodStatistics(String tenantId, PeriodCriteria criteria) {
        Instant from = startOfDay(criteria.dateFrom());
        Instant to = startOfDay(criteria.dateTo().plusDays(1));
        if (!from.isBefore(to)) throw badRequest("dateFrom必须早于dateTo");
        var where = periodWhere(tenantId, criteria);
        String orderSql =
                "SELECT COALESCE(SUM(CASE WHEN o.order_date>=? AND o.order_date<? THEN o.payable_amount ELSE 0 END),0) "
                        + " FROM order_sales_order o WHERE "
                        + where.sql();
        var args = new ArrayList<Object>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.addAll(where.args());
        BigDecimal orderAmount = jdbc.queryForObject(orderSql + " LIMIT 1", BigDecimal.class, args.toArray());

        String receivedSql =
                "SELECT COALESCE(SUM(CASE WHEN p.payment_time>=? AND p.payment_time<? "
                        + " AND p.payment_status_code IN ('RECEIVED','CHECKED') THEN p.paid_amount ELSE 0 END),0) "
                        + " FROM order_payment_record p "
                        + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0 "
                        + " WHERE "
                        + where.sql();
        args = new ArrayList<>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.addAll(where.args());
        BigDecimal received = jdbc.queryForObject(receivedSql + " LIMIT 1", BigDecimal.class, args.toArray());

        String refundSql =
                "SELECT COALESCE(SUM(CASE WHEN r.refund_time>=? AND r.refund_time<? AND r.refund_status_code='CONFIRMED' "
                        + " THEN r.refund_amount ELSE 0 END),0) "
                        + " FROM order_refund_record r "
                        + " JOIN order_sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id AND o.deleted=0 "
                        + " WHERE "
                        + where.sql();
        args = new ArrayList<>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.addAll(where.args());
        BigDecimal refund = jdbc.queryForObject(refundSql + " LIMIT 1", BigDecimal.class, args.toArray());

        String unpaidSql =
                "SELECT COALESCE(SUM(GREATEST(o.payable_amount "
                        + " - COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                        + "   THEN p.paid_amount ELSE 0 END) FROM order_payment_record p "
                        + "   WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_time<?),0) "
                        + " + COALESCE((SELECT SUM(r.refund_amount) FROM order_refund_record r "
                        + "   WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.deleted=0 "
                        + "   AND r.refund_status_code='CONFIRMED' AND r.refund_time<?),0),0)),0) "
                        + " FROM order_sales_order o WHERE o.order_date<? AND "
                        + where.sql();
        args = new ArrayList<>();
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(to));
        args.addAll(where.args());
        BigDecimal unpaid = jdbc.queryForObject(unpaidSql + " LIMIT 1", BigDecimal.class, args.toArray());

        List<OrderRegisterModels.PeriodRow> rows =
                "region".equalsIgnoreCase(criteria.groupBy())
                                || "customer".equalsIgnoreCase(criteria.groupBy())
                                || "employee".equalsIgnoreCase(criteria.groupBy())
                        ? periodRows(tenantId, criteria, from, to)
                        : List.of();
        return new PeriodStatisticsView(
                criteria.dateFrom(),
                criteria.dateTo(),
                criteria.groupBy(),
                new OrderRegisterModels.PeriodTotals(
                        orderAmount, received, refund, received.subtract(refund), unpaid),
                rows,
                historyCoverage(tenantId));
    }

    @Override
    public List<String> creators(String tenantId) {
        return jdbc.queryForList(
                "SELECT creator_name FROM ("
                        + " SELECT COALESCE(NULLIF(TRIM(source_creator_name),''),NULLIF(TRIM(created_by),'')) AS creator_name"
                        + " FROM order_sales_order WHERE tenant_id=? AND deleted=0"
                        + " GROUP BY COALESCE(NULLIF(TRIM(source_creator_name),''),NULLIF(TRIM(created_by),''))) c"
                        + " WHERE creator_name IS NOT NULL ORDER BY creator_name",
                String.class,
                tenantId);
    }

    @Override
    public OrderRegisterPage<ReceivablesView> receivables(
            String tenantId, int begin, int step, ReceivablesCriteria criteria) {
        Instant asOf = startOfDay(criteria.asOfDate().plusDays(1));
        var where = receivablesWhere(tenantId, criteria);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order o WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        Long.class,
                        where.args().toArray());
        var args = new ArrayList<Object>();
        args.add(Timestamp.from(asOf));
        args.add(Timestamp.from(asOf));
        args.add(Timestamp.from(asOf));
        args.addAll(where.args());
        var items =
                jdbc.query(
                        "SELECT o.id,o.order_no,o.source_system_code,o.customer_id,"
                                + " o.customer_code_snapshot,o.customer_name_snapshot,"
                                + " COALESCE(snap.region_code,o.region_code) AS region_code,"
                                + " COALESCE(snap.employee_code,o.owner_employee_code) AS owner_employee_code,"
                                + " COALESCE(snap.employee_name,o.owner_employee_name_snapshot) AS owner_employee_name,"
                                + " snap.department_name,o.order_date,o.payable_amount,"
                                + " COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                                + "   THEN p.paid_amount ELSE 0 END) FROM order_payment_record p "
                                + "   WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_time<?),0) "
                                + " - COALESCE((SELECT SUM(r.refund_amount) FROM order_refund_record r "
                                + "   WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.deleted=0 "
                                + "   AND r.refund_status_code='CONFIRMED' AND r.refund_time<?),0) AS net_received "
                                + " FROM order_sales_order o "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE o.order_date<? AND "
                                + where.sql()
                                + " ORDER BY o.order_date DESC,o.id DESC LIMIT ? OFFSET ?",
                        (rs, i) -> {
                            BigDecimal receivable = decimal(rs, "payable_amount");
                            BigDecimal net = decimal(rs, "net_received");
                            BigDecimal unpaid = receivable.subtract(net).max(ZERO);
                            BigDecimal overpaid = net.subtract(receivable).max(ZERO);
                            return new ReceivablesView(
                                    rs.getLong("id"),
                                    rs.getString("order_no"),
                                    rs.getString("source_system_code"),
                                    nullableLong(rs, "customer_id"),
                                    rs.getString("customer_code_snapshot"),
                                    rs.getString("customer_name_snapshot"),
                                    rs.getString("region_code"),
                                    null,
                                    rs.getString("owner_employee_code"),
                                    rs.getString("owner_employee_name"),
                                    rs.getString("department_name"),
                                    instant(rs, "order_date"),
                                    receivable,
                                    net,
                                    unpaid,
                                    overpaid,
                                    true,
                                    null);
                        },
                        append(args, step, begin).toArray());
        var totalArgs = new ArrayList<Object>();
        for (int i = 0; i < 3; i++) totalArgs.add(Timestamp.from(asOf));
        totalArgs.addAll(where.args());
        var totalsRow =
                jdbc.queryForMap(
                        "SELECT COALESCE(SUM(t.receivable),0) receivable_amount,"
                                + " COALESCE(SUM(t.net),0) net_received_amount,"
                                + " COALESCE(SUM(GREATEST(t.receivable-t.net,0)),0) unpaid_amount,"
                                + " COALESCE(SUM(GREATEST(t.net-t.receivable,0)),0) overpaid_amount"
                                + " FROM (SELECT o.payable_amount receivable,"
                                + " COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                                + "   THEN p.paid_amount ELSE 0 END) FROM order_payment_record p "
                                + "   WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_time<?),0) "
                                + " - COALESCE((SELECT SUM(r.refund_amount) FROM order_refund_record r "
                                + "   WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.deleted=0 "
                                + "   AND r.refund_status_code='CONFIRMED' AND r.refund_time<?),0) net "
                                + " FROM order_sales_order o WHERE o.order_date<? AND "
                                + where.sql()
                                + ") t LIMIT 1",
                        totalArgs.toArray());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("receivableAmount", decimal(totalsRow, "receivable_amount"));
        totals.put("netReceivedAmount", decimal(totalsRow, "net_received_amount"));
        totals.put("unpaidAmount", decimal(totalsRow, "unpaid_amount"));
        totals.put("overpaidAmount", decimal(totalsRow, "overpaid_amount"));
        return new OrderRegisterPage<>(
                total,
                begin,
                step,
                items,
                totals,
                historyCoverage(tenantId));
    }

    @Override
    public OrderRegisterPage<NumberMappingView> numberMappings(
            String tenantId, int begin, int step, NumberMappingCriteria criteria) {
        var where = new Sql();
        where.and("tenant_id=?", tenantId);
        if (criteria.state() != null && !criteria.state().isBlank())
            where.and("state=?", criteria.state());
        if (criteria.internalOrderNo() != null && !criteria.internalOrderNo().isBlank())
            where.and("internal_order_no=?", criteria.internalOrderNo());
        if (criteria.dhbOrderNo() != null && !criteria.dhbOrderNo().isBlank())
            where.and("dhb_order_no=?", criteria.dhbOrderNo());
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_number_mapping WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        Long.class,
                        where.args().toArray());
        var items =
                jdbc.query(
                        "SELECT id,tenant_id,connector_id,source_system_code,source_object_type,"
                                + " source_object_id,internal_order_no,dhb_order_no,state,evidence,"
                                + " created_by,created_time,updated_by,updated_time "
                                + " FROM order_number_mapping WHERE "
                                + where.sql()
                                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        (rs, i) ->
                                new NumberMappingView(
                                        rs.getLong("id"),
                                        rs.getString("connector_id"),
                                        rs.getString("source_system_code"),
                                        rs.getString("source_object_type"),
                                        rs.getString("source_object_id"),
                                        rs.getString("internal_order_no"),
                                        rs.getString("dhb_order_no"),
                                        rs.getString("state"),
                                        rs.getString("evidence"),
                                        rs.getString("created_by"),
                                        instant(rs, "created_time"),
                                        rs.getString("updated_by"),
                                        instant(rs, "updated_time")),
                        append(where.args(), step, begin).toArray());
        return new OrderRegisterPage<>(total, begin, step, items, Map.of(), HistoryCoverage.covered());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderNumberMappingResult applyNumberMapping(
            String tenantId, ApplyNumberMapping command, String actorId) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,order_no,source_system_code,revision FROM order_sales_order "
                                + " WHERE tenant_id=? AND order_no=? AND deleted=0 FOR UPDATE",
                        tenantId,
                        command.internalOrderNo());
        if (rows.isEmpty()) throw notFound("旧内部订单号不存在");
        var row = rows.getFirst();
        long orderId = ((Number) row.get("id")).longValue();
        String previousOrderNo = String.valueOf(row.get("order_no"));
        int revision = ((Number) row.get("revision")).intValue();
        if (command.revision() != null && command.revision() != revision)
            throw conflict("订单版本已变化，请刷新后重试");
        Long conflict =
                jdbc.queryForObject(
                        "SELECT id FROM order_sales_order WHERE tenant_id=? AND order_no=? AND id<>? AND deleted=0 LIMIT 1",
                        Long.class,
                        tenantId,
                        command.dhbOrderNo(),
                        orderId);
        if (conflict != null) {
            insertMapping(tenantId, orderId, command, previousOrderNo, "PENDING", actorId);
            return new OrderNumberMappingResult(
                    null, orderId, previousOrderNo, previousOrderNo, "PENDING", "订货宝单号已被其他订单占用");
        }
        jdbc.update(
                "UPDATE order_sales_order SET order_no=?,source_order_no=COALESCE(source_order_no,?),"
                        + " updated_by=?,updated_time=CURRENT_TIMESTAMP(6),revision=revision+1 "
                        + " WHERE tenant_id=? AND id=?",
                command.dhbOrderNo(),
                command.dhbOrderNo(),
                actorId,
                tenantId,
                orderId);
        for (String table : List.of("order_payment_record", "order_refund_record", "order_fund_document", "order_sales_shipment")) {
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET sales_order_no_snapshot=?,updated_by=?,updated_time=CURRENT_TIMESTAMP(6) "
                            + " WHERE tenant_id=? AND "
                            + (table.equals("order_fund_document") ? "related_order_id" : "order_id")
                            + "=?",
                    command.dhbOrderNo(),
                    actorId,
                    tenantId,
                    orderId);
        }
        long mappingId = insertMapping(tenantId, orderId, command, previousOrderNo, "ACTIVE", actorId);
        return new OrderNumberMappingResult(
                mappingId, orderId, previousOrderNo, command.dhbOrderNo(), "ACTIVE", null);
    }

    private long insertMapping(
            String tenantId,
            long orderId,
            ApplyNumberMapping command,
            String previousOrderNo,
            String state,
            String actorId) {
        jdbc.update(
                "INSERT INTO order_number_mapping(tenant_id,connector_id,source_system_code,source_object_type,"
                        + " source_object_id,dhb_order_no,internal_order_no,state,evidence,created_by,updated_by) "
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?) "
                        + " ON DUPLICATE KEY UPDATE dhb_order_no=VALUES(dhb_order_no),internal_order_no=VALUES(internal_order_no),"
                        + " state=VALUES(state),evidence=VALUES(evidence),updated_by=VALUES(updated_by),"
                        + " updated_time=CURRENT_TIMESTAMP(6),revision=revision+1",
                tenantId,
                command.connectorId(),
                "FEISHU",
                command.sourceObjectType(),
                command.sourceObjectId(),
                command.dhbOrderNo(),
                previousOrderNo,
                state,
                command.evidence(),
                actorId,
                actorId);
        return jdbc.queryForObject(
                        "SELECT id FROM order_number_mapping WHERE tenant_id=? AND connector_id<=>? "
                                + " AND source_object_type=? AND source_object_id=? LIMIT 1",
                        Long.class,
                        tenantId,
                        command.connectorId(),
                        command.sourceObjectType(),
                        command.sourceObjectId());
    }

    private Map<String, BigDecimal> orderTotals(String tenantId, OrderCriteria criteria) {
        var where = orderWhere(tenantId, criteria);
        var row =
                jdbc.queryForMap(
                        "SELECT COALESCE(SUM(o.original_amount),0) original_amount,"
                                + " COALESCE(SUM(o.payable_amount),0) payable_amount,"
                                + " COALESCE(SUM(o.paid_amount),0) paid_amount,"
                                + " COALESCE(SUM(o.unpaid_amount),0) unpaid_amount "
                                + " FROM order_sales_order o "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        where.args().toArray());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("originalAmount", decimal(row, "original_amount"));
        totals.put("payableAmount", decimal(row, "payable_amount"));
        totals.put("paidAmount", decimal(row, "paid_amount"));
        totals.put("unpaidAmount", decimal(row, "unpaid_amount"));
        totals.put("checkedAmount", ZERO);
        return totals;
    }

    private Map<String, BigDecimal> paymentTotals(String tenantId, PaymentCriteria criteria) {
        var where = paymentWhere(tenantId, criteria);
        var row =
                jdbc.queryForMap(
                        "SELECT "
                                + " COALESCE(SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') THEN p.paid_amount ELSE 0 END),0) received_amount,"
                                + " COALESCE(SUM(CASE WHEN p.payment_status_code='CHECKED' THEN p.paid_amount ELSE 0 END),0) checked_amount,"
                                + " COALESCE(SUM(CASE WHEN p.payment_status_code='PENDING' THEN p.paid_amount ELSE 0 END),0) pending_amount,"
                                + " COALESCE(SUM(CASE WHEN p.payment_status_code='CANCELLED' THEN p.paid_amount ELSE 0 END),0) cancelled_amount "
                                + " FROM order_payment_record p "
                                + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0 "
                                + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                                + " WHERE "
                                + where.sql()
                                + " LIMIT 1",
                        where.args().toArray());
        // 应收金额按订单表口径：符合条件的订单金额合计，不受付款条件影响（与订单列表同口径）。
        var orderWhere = paymentOrderSideWhere(tenantId, criteria);
        var related =
                jdbc.queryForMap(
                        "SELECT COALESCE(SUM(o.payable_amount),0) related_amount FROM order_sales_order o WHERE "
                                + orderWhere.sql()
                                + " LIMIT 1",
                        orderWhere.args().toArray());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("receivedAmount", decimal(row, "received_amount"));
        totals.put("checkedAmount", decimal(row, "checked_amount"));
        totals.put("pendingDocumentAmount", decimal(row, "pending_amount"));
        totals.put("cancelledDocumentAmount", decimal(row, "cancelled_amount"));
        totals.put("relatedOrderAmount", decimal(related, "related_amount"));
        return totals;
    }

    /** 期间统计分组明细；分组口径与合计一致，期末未回款含往期订单但收款截止到期末。 */
    private List<OrderRegisterModels.PeriodRow> periodRows(
            String tenantId, PeriodCriteria criteria, Instant from, Instant to) {
        String key =
                switch (criteria.groupBy().toLowerCase()) {
                    case "region" -> "COALESCE(snap.region_code,o.region_code)";
                    case "employee" -> "COALESCE(snap.employee_code,o.owner_employee_code)";
                    default -> "o.customer_id";
                };
        String label =
                switch (criteria.groupBy().toLowerCase()) {
                    case "region" -> "MAX(COALESCE(snap.region_code,o.region_code))";
                    case "employee" ->
                            "MAX(COALESCE(snap.employee_name,o.owner_employee_name_snapshot))";
                    default -> "MAX(o.customer_name_snapshot)";
                };
        var where = periodWhere(tenantId, criteria);
        var args = new ArrayList<Object>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(to));
        args.addAll(where.args());
        return jdbc.query(
                "SELECT "
                        + key
                        + " AS group_key,"
                        + label
                        + " AS group_label,"
                        + " COALESCE(SUM(CASE WHEN o.order_date>=? AND o.order_date<? THEN o.payable_amount ELSE 0 END),0) period_order_amount,"
                        + " COALESCE(SUM(COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                        + "   AND p.payment_time>=? AND p.payment_time<? THEN p.paid_amount ELSE 0 END) "
                        + "   FROM order_payment_record p "
                        + "   WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0),0)),0) period_received_amount,"
                        + " COALESCE(SUM(COALESCE((SELECT SUM(CASE WHEN r.refund_status_code='CONFIRMED' "
                        + "   AND r.refund_time>=? AND r.refund_time<? THEN r.refund_amount ELSE 0 END) "
                        + "   FROM order_refund_record r "
                        + "   WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.deleted=0),0)),0) period_refund_amount,"
                        + " COALESCE(SUM(GREATEST(o.payable_amount "
                        + " - COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                        + "   THEN p.paid_amount ELSE 0 END) FROM order_payment_record p "
                        + "   WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0 AND p.payment_time<?),0) "
                        + " + COALESCE((SELECT SUM(r.refund_amount) FROM order_refund_record r "
                        + "   WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.deleted=0 "
                        + "   AND r.refund_status_code='CONFIRMED' AND r.refund_time<?),0),0)),0) ending_unpaid_amount "
                        + " FROM order_sales_order o "
                        + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' "
                        + " WHERE o.order_date<? AND "
                        + where.sql()
                        + " GROUP BY "
                        + key
                        + " ORDER BY period_order_amount DESC,group_key",
                (rs, i) -> {
                    BigDecimal received = decimal(rs, "period_received_amount");
                    BigDecimal refund = decimal(rs, "period_refund_amount");
                    return new OrderRegisterModels.PeriodRow(
                            rs.getString("group_key"),
                            rs.getString("group_label"),
                            decimal(rs, "period_order_amount"),
                            received,
                            refund,
                            received.subtract(refund),
                            decimal(rs, "ending_unpaid_amount"));
                },
                args.toArray());
    }

    /** 与列表展示同一口径：订货宝订单号来自 order_number_mapping，或订货宝来源单号兜底。 */
    private static final String DHB_ORDER_NO_EXPRESSION =
            "COALESCE((SELECT MAX(dhb.dhb_order_no) FROM order_number_mapping dhb"
                    + " WHERE dhb.tenant_id=o.tenant_id AND dhb.internal_order_no=o.order_no"
                    + " AND dhb.state='ACTIVE' AND dhb.deleted=0),"
                    + " CASE WHEN o.source_system_code='DINGHUOBAO' THEN o.source_order_no END)";

    private Sql orderWhere(String tenantId, OrderCriteria c) {
        var where = new Sql();
        where.and("o.tenant_id=?", tenantId);
        where.and("o.deleted=0");
        like(where, "o.order_no", c.orderNo());
        eq(where, "o.customer_id", c.customerId());
        like(where, "o.customer_name_snapshot", c.customerName());
        like(where, "o.customer_code_snapshot", c.customerCode());
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        ge(where, "o.order_date", c.orderDateFrom());
        lt(where, "o.order_date", c.orderDateTo());
        eq(where, "o.order_status_code", c.orderStatusCode());
        eq(where, "o.payment_status_code", c.paymentStatusCode());
        if (Boolean.TRUE.equals(c.hasUnpaid())) where.and("o.unpaid_amount>0");
        if (Boolean.FALSE.equals(c.hasUnpaid())) where.and("o.unpaid_amount=0");
        if (Boolean.TRUE.equals(c.dhbLinked())) where.and(DHB_ORDER_NO_EXPRESSION + " IS NOT NULL");
        if (Boolean.FALSE.equals(c.dhbLinked())) where.and(DHB_ORDER_NO_EXPRESSION + " IS NULL");
        // 开票状态来自 order_invoice：未申请含无发票行与已撤回，其余按当前状态精确匹配。
        if ("NOT_APPLIED".equals(c.invoiceStatusCode())) {
            where.and(
                    "NOT EXISTS (SELECT 1 FROM order_invoice inv WHERE inv.tenant_id=o.tenant_id"
                            + " AND inv.sales_order_id=o.id AND inv.deleted=0"
                            + " AND inv.status IN ('PENDING','INVOICED'))");
        } else if (c.invoiceStatusCode() != null) {
            where.and(
                    "EXISTS (SELECT 1 FROM order_invoice inv WHERE inv.tenant_id=o.tenant_id"
                            + " AND inv.sales_order_id=o.id AND inv.deleted=0 AND inv.status=?)",
                    c.invoiceStatusCode());
        }
        var scope = scopes.predicate("order:read", "o.", null);
        where.and("(" + scope.sql() + ")", scope.args().toArray());
        return where;
    }

    private Sql lineWhere(String tenantId, LineCriteria c) {
        var where = new Sql();
        where.and("l.tenant_id=?", tenantId);
        where.and("l.deleted=0");
        like(where, "o.order_no", c.orderNo());
        eq(where, "o.customer_id", c.customerId());
        like(where, "o.customer_name_snapshot", c.customerName());
        like(where, "o.customer_code_snapshot", c.customerCode());
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        ge(where, "o.order_date", c.orderDateFrom());
        lt(where, "o.order_date", c.orderDateTo());
        eq(where, "o.order_status_code", c.orderStatusCode());
        if (c.productKeyword() != null && !c.productKeyword().isBlank()) {
            where.and(
                    "(l.product_name_snapshot LIKE ? OR l.product_code_snapshot LIKE ? OR l.sku_code_snapshot LIKE ?)",
                    "%" + c.productKeyword() + "%",
                    "%" + c.productKeyword() + "%",
                    "%" + c.productKeyword() + "%");
        }
        like(where, "l.product_code_snapshot", c.productCode());
        if (c.productIds() != null && !c.productIds().isEmpty()) {
            where.and(
                    "l.product_id IN ("
                            + String.join(",", java.util.Collections.nCopies(c.productIds().size(), "?"))
                            + ")",
                    c.productIds().toArray());
        }
        var scope = scopes.predicate("order:read", "o.", null);
        where.and("(" + scope.sql() + ")", scope.args().toArray());
        return where;
    }

    /** 应收金额只看订单侧条件；付款侧条件（收款编码/交易单号/收款状态/付款时间）不参与。 */
    private Sql paymentOrderSideWhere(String tenantId, PaymentCriteria c) {
        var where = new Sql();
        where.and("o.tenant_id=?", tenantId);
        where.and("o.deleted=0");
        like(where, "o.order_no", c.orderNo());
        eq(where, "o.customer_id", c.customerId());
        like(where, "o.customer_name_snapshot", c.customerName());
        like(where, "o.customer_code_snapshot", c.customerCode());
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        ge(where, "o.order_date", c.orderDateFrom());
        lt(where, "o.order_date", c.orderDateTo());
        eq(where, "o.order_status_code", c.orderStatusCode());
        var scope = scopes.predicate("order:read", "o.", null);
        where.and("(" + scope.sql() + ")", scope.args().toArray());
        return where;
    }

    /** 排序字段只允许收款时间/创建时间/同步时间，其他值一律回落默认排序。 */
    private static String paymentOrderBy(PaymentCriteria c) {
        String column =
                switch (c.sortBy() == null ? "" : c.sortBy().trim()) {
                    case "createdTime" -> "p.created_time";
                    case "syncedAt" -> "p.synced_at";
                    default -> "p.payment_time";
                };
        boolean asc = "asc".equalsIgnoreCase(c.sortDirection());
        return " ORDER BY " + column + (asc ? " ASC" : " DESC") + ",p.id DESC";
    }

    private Sql paymentWhere(String tenantId, PaymentCriteria c) {
        var where = new Sql();
        where.and("p.tenant_id=?", tenantId);
        where.and("p.deleted=0");
        like(where, "o.order_no", c.orderNo());
        eq(where, "o.customer_id", c.customerId());
        like(where, "o.customer_name_snapshot", c.customerName());
        like(where, "o.customer_code_snapshot", c.customerCode());
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        ge(where, "o.order_date", c.orderDateFrom());
        lt(where, "o.order_date", c.orderDateTo());
        eq(where, "o.order_status_code", c.orderStatusCode());
        like(where, "p.payment_no", c.paymentNo());
        like(where, "p.transaction_no", c.transactionNo());
        eq(where, "p.payment_status_code", c.paymentStatusCode());
        ge(where, "p.payment_time", c.paymentTimeFrom());
        lt(where, "p.payment_time", c.paymentTimeTo());
        var scope = scopes.predicate("order:read", "o.", null);
        where.and("(" + scope.sql() + ")", scope.args().toArray());
        return where;
    }

    private Sql periodWhere(String tenantId, PeriodCriteria c) {
        var where = new Sql();
        where.and("o.tenant_id=?", tenantId);
        where.and("o.deleted=0");
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        eq(where, "o.customer_id", c.customerId());
        like(where, "o.customer_name_snapshot", c.customerName());
        like(where, "o.customer_code_snapshot", c.customerCode());
        return where;
    }

    private Sql receivablesWhere(String tenantId, ReceivablesCriteria c) {
        var where = new Sql();
        where.and("o.tenant_id=?", tenantId);
        where.and("o.deleted=0");
        like(where, "o.order_no", c.orderNo());
        like(where, "o.region_code", c.regionCode());
        like(where, "o.owner_employee_code", c.ownerEmployeeCode());
        inIds(where, "snap.department_id", c.departmentIds());
        eq(where, "o.customer_id", c.customerId());
        if (c.hasUnpaid())
            where.and(
                    "o.payable_amount - COALESCE((SELECT SUM(CASE WHEN p.payment_status_code IN ('RECEIVED','CHECKED') "
                            + " THEN p.paid_amount ELSE 0 END) FROM order_payment_record p "
                            + " WHERE p.tenant_id=o.tenant_id AND p.order_id=o.id AND p.deleted=0),0) > 0");
        var scope = scopes.predicate("order:read", "o.", null);
        where.and("(" + scope.sql() + ")", scope.args().toArray());
        return where;
    }

    private HistoryCoverage historyCoverage(String tenantId) {
        long missing =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_sales_order o "
                                + " WHERE o.tenant_id=? AND o.deleted=0 AND o.order_date<? "
                                + " AND NOT EXISTS (SELECT 1 FROM order_financial_event e "
                                + "   WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id) LIMIT 1",
                        Long.class,
                        tenantId,
                        Timestamp.from(HistorySyncRules.CUTOVER));
        return missing == 0
                ? HistoryCoverage.covered()
                : HistoryCoverage.incomplete(
                        HistorySyncRules.CUTOVER,
                        "切换日前订单缺少历史资金事件，结果不视为完整历史余额",
                        missing);
    }

    private static String orderNumberState(String sourceSystemCode, String mappingState) {
        if ("ACTIVE".equals(mappingState)) return "MAPPED";
        if ("DINGHUOBAO".equalsIgnoreCase(sourceSystemCode)) return "SOURCE";
        return "PENDING_MAPPING";
    }

    private static Instant startOfDay(java.time.LocalDate date) {
        return date.atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    private static BigDecimal decimal(java.sql.ResultSet rs, String column) {
        try {
            return rs.getBigDecimal(column);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? ZERO : new BigDecimal(value.toString());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) {
        try {
            // DATETIME 按 UTC 写入（与写入侧 local(Instant) 一致）；不能经 JVM 时区再解读一次。
            java.time.LocalDateTime value = rs.getObject(column, java.time.LocalDateTime.class);
            return value == null ? null : value.toInstant(ZoneOffset.UTC);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) {
        try {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> stringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            var parsed = JSON.readValue(value, STRING_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /** 来源时间优先：同步数据的真实创建/修改时间，无来源时回退本系统记录时间。 */
    private static Instant firstInstant(ResultSet rs, String preferred, String fallback) throws SQLException {
        Instant value = instant(rs, preferred);
        return value == null ? instant(rs, fallback) : value;
    }

    private static List<Object> append(List<Object> base, Object... values) {
        var result = new ArrayList<>(base);
        for (Object value : values) result.add(value);
        return result;
    }

    private static void like(Sql where, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.and(column + " LIKE ?", "%" + value.strip() + "%");
    }

    /** 业务员编码过滤；空集合表示筛选无匹配，不能静默放大成全量。 */
    /** 部门范围用 ID 集合；空集合表示“范围为空”，不能放大成全量。 */
    private static void inIds(Sql where, String column, Collection<Long> values) {
        if (values == null) return;
        List<Long> ids = values.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            where.and("1=0");
            return;
        }
        where.and(
                column + " IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
                ids.toArray());
    }

    private static void in(Sql where, String column, Collection<String> values) {
        if (values == null) return;
        List<String> codes = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .sorted()
                .toList();
        if (codes.isEmpty()) {
            where.and("1=0");
            return;
        }
        where.and(
                column + " IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")",
                codes.toArray());
    }

    private static void eq(Sql where, String column, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) return;
        where.and(column + "=?", value);
    }

    private static void ge(Sql where, String column, Instant value) {
        if (value != null) where.and(column + ">=?", Timestamp.from(value));
    }

    private static void lt(Sql where, String column, Instant value) {
        if (value != null) where.and(column + "<?", Timestamp.from(value));
    }

    /** 列表与单条查询共用的回款行映射；核对返回同一口径，避免两套展示。 */
    private static OrderRegisterPaymentView paymentRow(ResultSet rs, int index) throws SQLException {
        return new OrderRegisterPaymentView(
                rs.getLong("id"),
                rs.getString("payment_no"),
                first(rs.getString("source_record_id"), rs.getString("payment_no")),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("dhb_order_no"),
                nullableLong(rs, "customer_id"),
                rs.getString("customer_code_snapshot"),
                rs.getString("customer_name_snapshot"),
                rs.getString("region_code"),
                null,
                rs.getString("owner_employee_code"),
                rs.getString("owner_employee_name"),
                nullableLong(rs, "department_id"),
                rs.getString("department_name"),
                instant(rs, "order_date"),
                decimal(rs, "payable_amount"),
                decimal(rs, "paid_amount"),
                rs.getString("payment_status_code"),
                instant(rs, "payment_time"),
                rs.getString("transaction_no"),
                stringList(rs.getString("voucher_keys_json")),
                List.of(),
                first(rs.getString("source_creator_name"), rs.getString("created_by")),
                firstInstant(rs, "source_created_at", "created_time"),
                first(rs.getString("source_modifier_name"), rs.getString("updated_by")),
                firstInstant(rs, "source_updated_at", "updated_time"),
                rs.getString("synced_by"),
                instant(rs, "synced_at"),
                rs.getString("checked_by"),
                instant(rs, "checked_at"),
                rs.getInt("revision"));
    }

    private static final String PAYMENT_ROW_SELECT =
            "SELECT p.id,p.payment_no,p.source_record_id,p.order_id,o.order_no,"
                    + " COALESCE((SELECT MAX(dhb.dhb_order_no) FROM order_number_mapping dhb WHERE dhb.tenant_id=o.tenant_id AND dhb.internal_order_no=o.order_no AND dhb.state='ACTIVE' AND dhb.deleted=0), CASE WHEN o.source_system_code='DINGHUOBAO' THEN o.source_order_no END) AS dhb_order_no,"
                    + " p.customer_id,p.customer_code_snapshot,p.customer_name_snapshot,"
                    + " COALESCE(snap.region_code,o.region_code) AS region_code,"
                    + " COALESCE(snap.employee_code,o.owner_employee_code) AS owner_employee_code,"
                    + " COALESCE(snap.employee_name,o.owner_employee_name_snapshot) AS owner_employee_name,"
                    + " snap.department_id,snap.department_name,o.order_date,o.payable_amount,"
                    + " p.paid_amount,p.payment_status_code,p.payment_time,p.transaction_no,"
                    + " p.voucher_keys_json,p.created_by,p.created_time,p.updated_by,p.updated_time,"
                    + " p.synced_by,p.synced_at,p.checked_by,p.checked_at,p.revision,"
                    + " o.source_creator_name,o.source_created_at,o.source_modifier_name,o.source_updated_at"
                    + " FROM order_payment_record p"
                    + " JOIN order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id AND o.deleted=0"
                    + " LEFT JOIN order_attribution_snapshot snap ON snap.tenant_id=o.tenant_id AND snap.order_id=o.id AND snap.state='FROZEN' ";

    @Override
    public OrderRegisterPaymentView checkPayment(
            String tenantId,
            long id,
            String transactionNo,
            int revision,
            String actorId,
            Instant checkedAt) {
        String current =
                jdbc.query(
                        "SELECT payment_status_code FROM order_payment_record"
                                + " WHERE tenant_id=? AND id=? AND deleted=0",
                        rs -> rs.next() ? rs.getString("payment_status_code") : null,
                        tenantId,
                        id);
        if (current == null) throw notFound("回款记录不存在");
        if ("CANCELLED".equals(current)) throw conflict("已取消的回款不能核对");
        if ("CHECKED".equals(current)) throw conflict("该回款已核对，无需重复核对");
        Long duplicate =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_payment_record"
                                + " WHERE tenant_id=? AND transaction_no=? AND deleted=0 AND id<>?",
                        Long.class,
                        tenantId,
                        transactionNo,
                        id);
        if (duplicate != null && duplicate > 0) {
            throw conflict("交易单号已被其他回款单使用，请核对后重试");
        }
        // 原子流转：只允许 RECEIVED + 页面版本命中时改状态，并发核对不会互相覆盖。
        int updated;
        try {
            updated =
                    jdbc.update(
                            "UPDATE order_payment_record SET payment_status_code='CHECKED',transaction_no=?,"
                                    + "checked_by=?,checked_at=?,updated_by=?,updated_time=UTC_TIMESTAMP(6),"
                                    + "revision=revision+1"
                                    + " WHERE tenant_id=? AND id=? AND deleted=0"
                                    + " AND payment_status_code='RECEIVED' AND revision=?",
                            transactionNo,
                            actorId,
                            Timestamp.from(checkedAt),
                            actorId,
                            tenantId,
                            id,
                            revision);
        } catch (DuplicateKeyException exception) {
            // 并发核对不同回款时撞上交易单号唯一约束：按业务冲突返回而不是 500。
            throw conflict("交易单号已被其他回款单使用，请核对后重试");
        }
        if (updated == 0) throw conflict("回款状态已变化，请刷新后重试");
        return paymentById(tenantId, id).orElseThrow(() -> notFound("回款记录不存在"));
    }

    private Optional<OrderRegisterPaymentView> paymentById(String tenantId, long id) {
        return jdbc.query(
                        PAYMENT_ROW_SELECT + " WHERE p.tenant_id=? AND p.id=? AND p.deleted=0",
                        JdbcOrderRegisterStore::paymentRow,
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static final class Sql {
        private final StringBuilder sql = new StringBuilder("1=1");
        private final List<Object> args = new ArrayList<>();

        void and(String condition, Object... values) {
            sql.append(" AND ").append(condition);
            args.addAll(List.of(values));
        }

        String sql() {
            return sql.toString();
        }

        List<Object> args() {
            return List.copyOf(args);
        }
    }
}

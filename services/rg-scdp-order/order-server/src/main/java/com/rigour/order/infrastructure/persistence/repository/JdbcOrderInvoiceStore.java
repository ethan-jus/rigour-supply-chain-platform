package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceListItemView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.domain.invoice.OrderInvoiceStatus;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 开票登记仓储；只维护 order_invoice，不参与订货宝同步，也不回写订单主表。 */
@Repository
public class JdbcOrderInvoiceStore implements OrderInvoiceStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final String COLUMNS =
            "id,sales_order_id,order_no,customer_id,customer_code,status,title_type,title,tax_no,"
                    + "invoice_type,bank_name,"
                    + "bank_account,register_address,register_phone,email,remark,amount,"
                    + "attachment_keys_json,invoice_no,applied_by,applied_at,invoiced_by,invoiced_at,"
                    + "updated_by,updated_at,revision";
    private static final String FROM_JOIN =
            "FROM order_invoice inv LEFT JOIN order_sales_order o"
                    + " ON o.tenant_id=inv.tenant_id AND o.id=inv.sales_order_id";

    private final JdbcTemplate jdbc;

    public JdbcOrderInvoiceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderInvoiceRow> findByOrderNo(String tenantId, String orderNo) {
        if (tenantId == null || orderNo == null || orderNo.isBlank()) return Optional.empty();
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM order_invoice"
                                + " WHERE tenant_id=? AND order_no=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                        this::mapRow,
                        tenantId,
                        orderNo)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<OrderInvoiceRow> findByOrderId(String tenantId, long salesOrderId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM order_invoice"
                                + " WHERE tenant_id=? AND sales_order_id=? AND deleted=0 LIMIT 1",
                        this::mapRow,
                        tenantId,
                        salesOrderId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<OrderInvoiceRow> findById(String tenantId, long id) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM order_invoice"
                                + " WHERE tenant_id=? AND id=? AND deleted=0 LIMIT 1",
                        this::mapRow,
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Map<Long, String> statusesByOrderIds(String tenantId, Collection<Long> orderIds) {
        if (tenantId == null || orderIds == null || orderIds.isEmpty()) return Map.of();
        List<Long> ids = orderIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        Map<Long, String> statuses = new LinkedHashMap<>();
        jdbc.query(
                "SELECT sales_order_id,status FROM order_invoice WHERE tenant_id=? AND deleted=0"
                        + " AND sales_order_id IN (" + placeholders + ")",
                rs -> {
                    statuses.put(rs.getLong("sales_order_id"), rs.getString("status"));
                },
                args.toArray());
        return Map.copyOf(statuses);
    }

    @Override
    public OrderInvoiceRow save(
            String tenantId, OrderInvoiceRow row, String expectedStatus, String actorId) {
        if (row.id() == null) {
            jdbc.update(
                    "INSERT INTO order_invoice (tenant_id,sales_order_id,order_no,customer_id,customer_code,"
                            + "status,title_type,title,"
                            + "tax_no,invoice_type,bank_name,bank_account,register_address,register_phone,"
                            + "email,remark,amount,attachment_keys_json,invoice_no,applied_by,applied_at,"
                            + "invoiced_by,invoiced_at,updated_by,updated_at,created_at,deleted)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)",
                    tenantId,
                    row.salesOrderId(),
                    row.orderNo(),
                    row.customerId(),
                    row.customerCode(),
                    row.status(),
                    row.titleType(),
                    row.title(),
                    row.taxNo(),
                    row.invoiceType(),
                    row.bankName(),
                    row.bankAccount(),
                    row.registerAddress(),
                    row.registerPhone(),
                    row.email(),
                    row.remark(),
                    row.amount(),
                    attachmentJson(row.attachmentKeys()),
                    row.invoiceNo(),
                    row.appliedBy(),
                    timestamp(row.appliedAt()),
                    row.invoicedBy(),
                    timestamp(row.invoicedAt()),
                    actorId);
            return findByOrderId(tenantId, row.salesOrderId()).orElseThrow();
        }
        boolean guardStatus = expectedStatus != null && !expectedStatus.isBlank();
        String statusGuard = guardStatus ? " AND status=?" : "";
        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(row.customerId());
        updateArgs.add(row.customerCode());
        updateArgs.add(row.status());
        updateArgs.add(row.titleType());
        updateArgs.add(row.title());
        updateArgs.add(row.taxNo());
        updateArgs.add(row.invoiceType());
        updateArgs.add(row.bankName());
        updateArgs.add(row.bankAccount());
        updateArgs.add(row.registerAddress());
        updateArgs.add(row.registerPhone());
        updateArgs.add(row.email());
        updateArgs.add(row.remark());
        updateArgs.add(row.amount());
        updateArgs.add(attachmentJson(row.attachmentKeys()));
        updateArgs.add(row.invoiceNo());
        updateArgs.add(row.appliedBy());
        updateArgs.add(timestamp(row.appliedAt()));
        updateArgs.add(row.invoicedBy());
        updateArgs.add(timestamp(row.invoicedAt()));
        updateArgs.add(actorId);
        updateArgs.add(tenantId);
        updateArgs.add(row.id());
        if (guardStatus) updateArgs.add(expectedStatus);
        updateArgs.add(row.revision());
        int updated =
                jdbc.update(
                "UPDATE order_invoice SET customer_id=?,customer_code=?,status=?,title_type=?,title=?,tax_no=?,invoice_type=?,"
                        + "bank_name=?,bank_account=?,register_address=?,register_phone=?,email=?,remark=?,"
                        + "amount=?,attachment_keys_json=?,invoice_no=?,applied_by=?,applied_at=?,"
                        + "invoiced_by=?,invoiced_at=?,updated_by=?,updated_at=UTC_TIMESTAMP(6),"
                        + "revision=revision+1"
                        + " WHERE tenant_id=? AND id=? AND deleted=0" + statusGuard + " AND revision=?",
                updateArgs.toArray());
        if (updated != 1) throw conflict("发票状态已变化，请刷新后重试");
        return findById(tenantId, row.id()).orElseThrow();
    }

    @Override
    public OrderRegisterPage<OrderInvoiceListItemView> page(
            String tenantId, int begin, int step, InvoicePageCriteria criteria) {
        Where where = invoiceWhere(tenantId, criteria, true);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) " + FROM_JOIN + " WHERE " + where.sql(),
                        Long.class,
                        where.args().toArray());
        List<OrderInvoiceListItemView> items =
                jdbc.query(
                        "SELECT inv.id,inv.sales_order_id,inv.order_no,o.customer_name_snapshot,"
                                + "inv.title,inv.invoice_type,inv.amount,inv.status,inv.applied_by,"
                                + "inv.applied_at,inv.invoice_no,inv.invoiced_at,inv.attachment_keys_json "
                                + FROM_JOIN
                                + " WHERE "
                                + where.sql()
                                + " ORDER BY inv.updated_at DESC,inv.id DESC LIMIT ? OFFSET ?",
                        (rs, index) ->
                                new OrderInvoiceListItemView(
                                        rs.getLong("id"),
                                        rs.getLong("sales_order_id"),
                                        rs.getString("order_no"),
                                        rs.getString("customer_name_snapshot"),
                                        rs.getString("title"),
                                        invoiceTypeName(rs.getString("invoice_type")),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("status"),
                                        OrderInvoiceStatus.displayNameOf(rs.getString("status")),
                                        rs.getString("applied_by"),
                                        instant(rs, "applied_at"),
                                        rs.getString("invoice_no"),
                                        instant(rs, "invoiced_at"),
                                        attachmentKeys(rs.getString("attachment_keys_json")).size()),
                        append(where.args(), step, begin).toArray());
        return new OrderRegisterPage<>(
                total == null ? 0 : total, begin, step, items, Map.of(), null);
    }

    @Override
    public Map<String, Long> statusCounts(String tenantId, InvoicePageCriteria criteria) {
        Where where = invoiceWhere(tenantId, criteria, false);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                "SELECT inv.status,COUNT(*) AS status_count " + FROM_JOIN + " WHERE " + where.sql()
                        + " GROUP BY inv.status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getLong("status_count"));
                },
                where.args().toArray());
        return Map.copyOf(counts);
    }

    private static Where invoiceWhere(
            String tenantId, InvoicePageCriteria criteria, boolean withStatus) {
        StringBuilder sql = new StringBuilder("inv.tenant_id=? AND inv.deleted=0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (withStatus && criteria.status() != null) {
            sql.append(" AND inv.status=?");
            args.add(criteria.status());
        }
        if (criteria.orderNo() != null) {
            // 与订单/明细/回款一致：订单号支持左右模糊
            sql.append(" AND inv.order_no LIKE ?");
            args.add("%" + criteria.orderNo() + "%");
        }
        if (criteria.customerName() != null) {
            sql.append(" AND o.customer_name_snapshot LIKE ?");
            args.add("%" + criteria.customerName() + "%");
        }
        if (criteria.appliedFrom() != null) {
            sql.append(" AND inv.applied_at>=?");
            args.add(Timestamp.from(criteria.appliedFrom()));
        }
        if (criteria.appliedTo() != null) {
            sql.append(" AND inv.applied_at<=?");
            args.add(Timestamp.from(criteria.appliedTo()));
        }
        return new Where(sql.toString(), args);
    }

    private static String invoiceTypeName(String invoiceType) {
        return switch (invoiceType == null ? "" : invoiceType) {
            case "NORMAL" -> "普通发票";
            case "SPECIAL" -> "专用发票";
            default -> null;
        };
    }

    private static List<Object> append(List<Object> args, Object... extra) {
        List<Object> values = new ArrayList<>(args);
        for (Object value : extra) values.add(value);
        return values;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private OrderInvoiceRow mapRow(ResultSet rs, int index) throws SQLException {
        return new OrderInvoiceRow(
                rs.getLong("id"),
                rs.getLong("sales_order_id"),
                rs.getString("order_no"),
                nullableLong(rs, "customer_id"),
                rs.getString("customer_code"),
                rs.getString("status"),
                rs.getString("title_type"),
                rs.getString("title"),
                rs.getString("tax_no"),
                rs.getString("invoice_type"),
                rs.getString("bank_name"),
                rs.getString("bank_account"),
                rs.getString("register_address"),
                rs.getString("register_phone"),
                rs.getString("email"),
                rs.getString("remark"),
                rs.getBigDecimal("amount"),
                attachmentKeys(rs.getString("attachment_keys_json")),
                rs.getString("invoice_no"),
                rs.getString("applied_by"),
                instant(rs, "applied_at"),
                rs.getString("invoiced_by"),
                instant(rs, "invoiced_at"),
                rs.getString("updated_by"),
                instant(rs, "updated_at"),
                rs.getInt("revision"));
    }

    @Override
    public List<OrderInvoiceProfileRow> profilesByCustomer(String tenantId, long customerId) {
        return jdbc.query(
                "SELECT id,customer_id,customer_code,title_type,title,tax_no,invoice_type,bank_name,"
                        + "bank_account,register_address,register_phone,email,remark,last_used_at"
                        + " FROM order_invoice_profile WHERE tenant_id=? AND customer_id=? AND deleted=0"
                        + " ORDER BY last_used_at DESC,id DESC LIMIT 50",
                JdbcOrderInvoiceStore::mapProfile,
                tenantId,
                customerId);
    }

    @Override
    public OrderInvoiceProfileRow saveProfile(String tenantId, OrderInvoiceProfileRow row, String actorId) {
        Long existingId =
                jdbc.query(
                                "SELECT id FROM order_invoice_profile WHERE tenant_id=? AND customer_id=?"
                                        + " AND title=? AND tax_no<=>? AND invoice_type=? AND deleted=0 LIMIT 1",
                                rs -> rs.next() ? rs.getLong("id") : null,
                                tenantId,
                                row.customerId(),
                                row.title(),
                                row.taxNo(),
                                row.invoiceType());
        if (existingId == null) {
            jdbc.update(
                    "INSERT INTO order_invoice_profile (tenant_id,customer_id,customer_code,title_type,title,"
                            + "tax_no,invoice_type,bank_name,bank_account,register_address,register_phone,email,"
                            + "remark,last_used_at,created_by,created_at,updated_by,updated_at,deleted)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),0)",
                    tenantId,
                    row.customerId(),
                    row.customerCode(),
                    row.titleType(),
                    row.title(),
                    row.taxNo(),
                    row.invoiceType(),
                    row.bankName(),
                    row.bankAccount(),
                    row.registerAddress(),
                    row.registerPhone(),
                    row.email(),
                    row.remark(),
                    actorId,
                    actorId);
        } else {
            jdbc.update(
                    "UPDATE order_invoice_profile SET customer_code=?,title_type=?,bank_name=?,bank_account=?,"
                            + "register_address=?,register_phone=?,email=?,remark=?,last_used_at=UTC_TIMESTAMP(6),"
                            + "updated_by=?,updated_at=UTC_TIMESTAMP(6)"
                            + " WHERE tenant_id=? AND id=? AND deleted=0",
                    row.customerCode(),
                    row.titleType(),
                    row.bankName(),
                    row.bankAccount(),
                    row.registerAddress(),
                    row.registerPhone(),
                    row.email(),
                    row.remark(),
                    actorId,
                    tenantId,
                    existingId);
        }
        return jdbc.query(
                        "SELECT id,customer_id,customer_code,title_type,title,tax_no,invoice_type,bank_name,"
                                + "bank_account,register_address,register_phone,email,remark,last_used_at"
                                + " FROM order_invoice_profile WHERE tenant_id=? AND id=? AND deleted=0",
                        JdbcOrderInvoiceStore::mapProfile,
                        tenantId,
                        existingId == null ? lastInsertId() : existingId)
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private Long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static OrderInvoiceProfileRow mapProfile(ResultSet rs, int index) throws SQLException {
        return new OrderInvoiceProfileRow(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("customer_code"),
                rs.getString("title_type"),
                rs.getString("title"),
                rs.getString("tax_no"),
                rs.getString("invoice_type"),
                rs.getString("bank_name"),
                rs.getString("bank_account"),
                rs.getString("register_address"),
                rs.getString("register_phone"),
                rs.getString("email"),
                rs.getString("remark"),
                instant(rs, "last_used_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String attachmentJson(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        return JSON.writeValueAsString(keys);
    }

    private static List<String> attachmentKeys(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> keys = JSON.readValue(json, STRING_LIST);
            return keys == null ? List.of() : List.copyOf(keys);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Where(String sql, List<Object> args) {
    }
}

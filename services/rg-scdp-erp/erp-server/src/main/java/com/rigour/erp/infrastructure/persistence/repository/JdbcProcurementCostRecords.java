package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ProcurementCostRecords;
import com.rigour.erp.domain.cost.ProcurementGoodsSourceRules;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** 复用采购单与现有成本表，一单一条汇总；未核实的约定运费不进入实际金额。 */
@Repository
public class JdbcProcurementCostRecords implements ProcurementCostRecords {
    private static final String FROM =
            """
FROM erp_procurement_order d
JOIN erp_inventory_warehouse w ON w.tenant_id COLLATE utf8mb4_general_ci=d.tenant_id AND w.deleted=0
  AND (w.warehouse_code COLLATE utf8mb4_general_ci=d.target_warehouse_code OR (d.target_warehouse_code IS NULL AND w.id=d.target_warehouse_id))
LEFT JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id AND s.deleted=0
  AND (s.supplier_code=d.supplier_code OR (d.supplier_code IS NULL AND s.id=d.supplier_id))
LEFT JOIN (SELECT tenant_id,procurement_no,SUM(line_amount) goods_amount,
  SUM(CASE WHEN unit_price IS NULL OR unit_price=0 THEN 1 ELSE 0 END) unknown_prices,COUNT(*) line_count
  FROM erp_procurement_order_line WHERE deleted=0 GROUP BY tenant_id,procurement_no) g
  ON g.tenant_id=d.tenant_id AND g.procurement_no=d.procurement_no
LEFT JOIN erp_cost_entry c ON c.id=(SELECT f.id FROM erp_cost_entry f
  WHERE f.deleted=0 AND f.tenant_id=d.tenant_id AND f.category_code='PURCHASE' AND f.type_code='PROCUREMENT_FREIGHT'
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(f.data_json,'$.goods.purchaseOrderId')) AS UNSIGNED)=d.id
  ORDER BY f.updated_time DESC,f.id DESC LIMIT 1)
""";
    private static final String SELECT =
            """
SELECT d.id,d.procurement_no,d.revision,s.supplier_name,w.warehouse_name,
  COALESCE(d.purchase_date,DATE(d.created_time)) purchase_date,d.source_system_code,d.source_document_no,
  g.goods_amount,g.unknown_prices,g.line_count,d.total_amount order_goods_amount,d.freight_amount,d.freight_bearer_code,
  c.id freight_id,c.revision freight_revision,c.status_code freight_status,c.amount actual_freight
""";
    private final JdbcTemplate jdbc;
    private final ErpWarehouseDataScope scope;

    public JdbcProcurementCostRecords(JdbcTemplate jdbc, ErpWarehouseDataScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private QueryWrapper<Object> query(String tenant, String action) {
        var q =
                new QueryWrapper<>()
                        .eq("d.tenant_id", tenant)
                        .eq("d.deleted", 0)
                        .eq("d.confirmation_status_code", "CONFIRMED")
                        .ne("d.status_code", "CANCELLED");
        var predicate = scope.predicate(action, "w.id");
        MybatisPlusErpCostRepository.bind(q, predicate.sql(), predicate.args());
        scope.applyCity(q, action, "w.region_code");
        return q;
    }

    private record Sql(String text, Object[] args) {}

    private Sql bind(QueryWrapper<?> query) {
        var args = new ArrayList<>();
        var result = new StringBuilder();
        var matcher =
                Pattern.compile("#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                        .matcher(query.getCustomSqlSegment());
        while (matcher.find()) {
            args.add(query.getParamNameValuePairs().get(matcher.group(1)));
            matcher.appendReplacement(result, "?");
        }
        matcher.appendTail(result);
        return new Sql(result.toString(), args.toArray());
    }

    @Override
    public MasterDataPageView<ProcurementCostRecordView> page(
            String tenant,
            int begin,
            int step,
            String number,
            String supplier,
            String status,
            LocalDate from,
            LocalDate to,
            String action) {
        var q =
                query(tenant, action)
                        .like(number != null && !number.isBlank(), "d.procurement_no", number)
                        .like(supplier != null && !supplier.isBlank(), "s.supplier_name", supplier)
                        .ge(from != null, "COALESCE(d.purchase_date,DATE(d.created_time))", from)
                        .le(to != null, "COALESCE(d.purchase_date,DATE(d.created_time))", to);
        if ("CONFIRMED".equals(status)) q.eq("c.status_code", "CONFIRMED");
        if ("PENDING".equals(status))
            q.and(x -> x.isNull("c.id").or().eq("c.status_code", "DRAFT"));
        if ("VOID".equals(status)) q.eq("c.status_code", "VOID");
        var sql = bind(q);
        Long count =
                jdbc.queryForObject("SELECT COUNT(*) " + FROM + sql.text(), Long.class, sql.args());
        var rows =
                jdbc.queryForList(
                        SELECT
                                + FROM
                                + sql.text()
                                + " ORDER BY d.purchase_date DESC,d.id DESC LIMIT "
                                + step
                                + " OFFSET "
                                + begin,
                        sql.args());
        return new MasterDataPageView<>(
                count == null ? 0 : count,
                begin,
                step,
                rows.stream().map(r -> view(r, List.of())).toList());
    }

    @Override
    public ProcurementCostRecordView record(
            String tenant, String code, String action, boolean lock) {
        var sql = bind(query(tenant, action).eq("d.procurement_no", code));
        var rows = jdbc.queryForList(SELECT + FROM + sql.text(), sql.args());
        if (rows.size() != 1)
            throw new BusinessException(ErrorCode.NOT_FOUND, "已确认采购单不存在、已取消或无访问权限", List.of());
        if (lock) {
            jdbc.queryForObject(
                    "SELECT id FROM erp_procurement_order WHERE tenant_id=? AND id=? AND deleted=0"
                            + " FOR UPDATE",
                    Long.class,
                    tenant,
                    rows.getFirst().get("id"));
            rows = jdbc.queryForList(SELECT + FROM + sql.text(), sql.args());
            if (rows.size() != 1)
                throw new BusinessException(ErrorCode.CONFLICT, "采购单已变化，请刷新", List.of());
        }
        boolean local = local(rows.getFirst());
        var lines =
                jdbc
                        .queryForList(
                                """
SELECT COALESCE(p.product_name,l.product_name_snapshot,l.product_code) product_name,l.variant_code,l.unit_code,l.quantity,l.unit_price,l.line_amount
FROM erp_procurement_order_line l LEFT JOIN erp_product p ON p.tenant_id COLLATE utf8mb4_general_ci=l.tenant_id AND p.deleted=0
  AND (p.product_code COLLATE utf8mb4_general_ci=l.product_code OR (l.product_code IS NULL AND p.id=l.product_id))
WHERE l.tenant_id=? AND l.procurement_no=? AND l.deleted=0 ORDER BY l.id
""",
                                tenant,
                                code)
                        .stream()
                        .map(
                                r -> {
                                    var price = decimal(r, "unit_price");
                                    boolean unknown =
                                            price == null || !local && price.signum() == 0;
                                    return new ProcurementCostRecordView.Line(
                                            text(r, "product_name"),
                                            text(r, "variant_code"),
                                            text(r, "unit_code"),
                                            decimal(r, "quantity"),
                                            unknown ? null : price,
                                            unknown ? null : decimal(r, "line_amount"));
                                })
                        .toList();
        return view(rows.getFirst(), lines);
    }

    private ProcurementCostRecordView view(
            Map<String, Object> r, List<ProcurementCostRecordView.Line> lines) {
        String sourceIssue =
                ProcurementGoodsSourceRules.issue(
                        text(r, "source_system_code"),
                        r.get("line_count") == null
                                ? 0
                                : ((Number) r.get("line_count")).longValue(),
                        r.get("unknown_prices") == null
                                ? 0
                                : ((Number) r.get("unknown_prices")).longValue(),
                        decimal(r, "order_goods_amount"),
                        decimal(r, "goods_amount"));
        BigDecimal goods = sourceIssue == null ? decimal(r, "order_goods_amount") : null;
        boolean confirmed = "CONFIRMED".equals(r.get("freight_status"));
        BigDecimal actual = confirmed ? decimal(r, "actual_freight") : null;
        BigDecimal total =
                goods == null ? null : goods.add(actual == null ? BigDecimal.ZERO : actual);
        String bearer = text(r, "freight_bearer_code");
        BigDecimal agreed = decimal(r, "freight_amount");
        return new ProcurementCostRecordView(
                ((Number) r.get("id")).longValue(),
                text(r, "procurement_no"),
                text(r, "source_system_code"),
                text(r, "source_document_no"),
                ((Number) r.get("revision")).intValue(),
                text(r, "supplier_name"),
                text(r, "warehouse_name"),
                ((java.sql.Date) r.get("purchase_date")).toLocalDate(),
                goods,
                new ProcurementCostRecordView.GoodsSourceCheck(
                        sourceIssue, decimal(r, "order_goods_amount"), decimal(r, "goods_amount")),
                agreed,
                bearer,
                actual,
                text(r, "freight_id"),
                r.get("freight_revision") == null
                        ? null
                        : ((Number) r.get("freight_revision")).intValue(),
                text(r, "freight_status"),
                confirmed
                        ? "CONFIRMED"
                        : "VOID".equals(r.get("freight_status")) ? "VOID" : "PENDING",
                total,
                lines);
    }

    @Override
    public List<String> freightRecordIds(String tenant, long orderId) {
        return jdbc
                .queryForList(
                        """
SELECT id FROM erp_cost_entry WHERE tenant_id=? AND category_code='PURCHASE'
  AND type_code='PROCUREMENT_FREIGHT'
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.goods.purchaseOrderId')) AS UNSIGNED)=?
ORDER BY created_time DESC,id DESC
""",
                        tenant,
                        orderId)
                .stream()
                .map(r -> text(r, "id"))
                .toList();
    }

    private static BigDecimal decimal(Map<String, Object> r, String key) {
        return r.get(key) == null ? null : new BigDecimal(r.get(key).toString());
    }

    private static String text(Map<String, Object> r, String key) {
        return Objects.toString(r.get(key), null);
    }

    private static boolean local(Map<String, Object> r) {
        return Set.of("SYSTEM", "MANUAL", "LOCAL", "ERP")
                .contains(
                        Objects.toString(r.get("source_system_code"), "SYSTEM")
                                .toUpperCase(Locale.ROOT));
    }
}

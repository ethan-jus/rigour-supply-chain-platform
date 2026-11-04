package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.CostLedgerReferenceStore;
import com.rigour.erp.application.service.cost.ErpCostService;
import com.rigour.erp.infrastructure.persistence.mapper.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcCostLedgerReferences implements CostLedgerReferenceStore {
    private final CostLedgerReferenceMapper mapper;
    private final ErpCostReferenceMapper sources;
    private final ErpWarehouseDataScope scope;
    public JdbcCostLedgerReferences(CostLedgerReferenceMapper mapper, ErpCostReferenceMapper sources, ErpWarehouseDataScope scope) {
        this.mapper = mapper; this.sources = sources; this.scope = scope;
    }
    public List<CostLedgerPartyView> parties(String tenant, String kind, String code,
            Long id, String keyword, int begin, String action) {
        boolean warehouse = "WAREHOUSE".equals(kind);
        if (!warehouse && !"SUPPLIER".equals(kind)) throw ErpCostService.bad("未知收发方类型");
        String a = warehouse ? "w" : "s";
        String field = warehouse ? "warehouse" : "supplier";
        var q = new QueryWrapper<Object>().eq(a + ".tenant_id", tenant).eq(a + ".deleted", 0);
        // 历史编码可查名；新建/确认必须仍为启用档案。
        if (!(action.endsWith(":read") || action.endsWith(":export")) || !text(code) && id == null) q.eq(a + ".status_code", "ACTIVE");
        q.eq(text(code), a + "." + field + "_code", code).eq(id != null, a + ".id", id);
        if (text(keyword)) q.and(w -> w.like(a + "." + field + "_name", keyword).or().like(a + "." + field + "_code", keyword));
        if (warehouse) {
            var p = scope.predicate(action, "w.id");
            MybatisPlusErpCostRepository.bind(q, p.sql(), p.args());
            scope.applyCity(q, action, "w.region_code");
        }
        q.orderByAsc(a + ".id").last("LIMIT 50 OFFSET " + Math.max(0, begin));
        return warehouse ? mapper.warehouses(q) : mapper.suppliers(q);
    }
    public List<ErpCostSourceView> sources(String tenant, CostLedgerSourceQuery f, String action) {
        boolean purchase = "PURCHASE_ORDER".equals(f.orderTypeCode());
        boolean transfer = "TRANSFER_ORDER".equals(f.orderTypeCode());
        if (!purchase && !transfer && !"OTHER_OUT".equals(f.orderTypeCode())) throw ErpCostService.bad("请选择采购、调拨或其他出库单，领用使用真实其他出库单");
        var q = new QueryWrapper<Object>().eq("d.tenant_id", tenant).eq("d.deleted", 0);
        if (purchase && !f.historical()) q.eq("d.confirmation_status_code", "CONFIRMED").ne("d.status_code", "CANCELLED");
        else if (transfer && !f.historical()) q.in("d.status_code", "OUT_CONFIRMED", "IN_CONFIRMED");
        else if (!purchase && !transfer) q.eq("d.stock_out_type_code", "OTHER").in(!f.historical(),"d.status_code", "CONFIRMED", "OUT_CONFIRMED");
        String[] warehouseFields = purchase ? new String[]{"w.id"} : transfer ? new String[]{"s.id", "w.id"} : new String[]{"w.id"};
        var p = scope.predicate(action, warehouseFields);
        MybatisPlusErpCostRepository.bind(q, p.sql(), p.args());
        scope.applyCity(q, action, "w.region_code");
        q.eq(text(f.cityCode()), "w.region_code", f.cityCode());
        String number = purchase ? "d.procurement_no" : transfer ? "d.transfer_no" : "d.stock_out_no";
        if (text(f.documentNo())) {
            if (f.exact()) q.eq(number, f.documentNo()); else q.like(number, f.documentNo());
        }
        if (text(f.partyName())) q.like(purchase ? "s.supplier_name" : transfer ? "w.warehouse_name" : "d.customer_name_snapshot", f.partyName());
        if (text(f.sourceWarehouseCode())) {
            if (purchase) q.apply("1=0"); else q.eq(transfer ? "s.warehouse_code" : "w.warehouse_code", f.sourceWarehouseCode());
        }
        q.eq(text(f.targetWarehouseCode()), "w.warehouse_code", f.targetWarehouseCode());
        String date = purchase ? "d.purchase_date" : "DATE(DATE_ADD(COALESCE(d.stock_out_time,d.created_time),INTERVAL 8 HOUR))";
        q.ge(f.dateFrom() != null, date, f.dateFrom()).le(f.dateTo() != null, date, f.dateTo());
        q.orderByDesc("d.id").last("LIMIT 50 OFFSET " + Math.max(0, f.begin()));
        return purchase ? sources.purchaseSources(q) : transfer ? sources.transferSources(q) : mapper.stockOuts(q);
    }
    private static boolean text(String v) { return v != null && !v.isBlank(); }
}

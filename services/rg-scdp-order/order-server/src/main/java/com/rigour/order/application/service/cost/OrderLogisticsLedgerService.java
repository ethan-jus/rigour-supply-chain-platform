package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.domain.cost.LogisticsLedgerRules;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class OrderLogisticsLedgerService {
    private final OrderLogisticsReferences references;
    private final OrderLedgerSourceStore sales;
    public OrderLogisticsLedgerService(OrderLogisticsReferences references, OrderLedgerSourceStore sales) { this.references = references; this.sales = sales; }
    public List<LogisticsLedgerPartyView> parties(String kind, String code, Long id, String keyword, String city, int begin, String action) {
        if (!Set.of("WAREHOUSE", "SUPPLIER", "CUSTOMER").contains(Objects.toString(kind, "")) || begin < 0 || begin > 100000) throw bad("收发方查询参数无效");
        if ("CUSTOMER".equals(kind)) references.city(city, action);
        return references.ledgerParties(kind, code, id, keyword, city, begin, action);
    }
    public List<LogisticsCostSourceView> sources(LogisticsLedgerSourceQuery q, String action) {
        if (q == null || q.historical() && (!q.exact() || q.documentNo()==null || q.documentNo().isBlank()) || q.begin() < 0 || q.begin() > 100000 || q.documentNo() != null && q.documentNo().length() > 100 || q.partyName() != null && q.partyName().length() > 200 || q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom())) throw bad("单据筛选条件无效");
        if (!"SALES_ORDER".equals(q.orderTypeCode())) return references.ledgerSources(q, action);
        Long warehouseId = null;
        if (q.sourceWarehouseCode() != null && !q.sourceWarehouseCode().isBlank()) warehouseId = Long.valueOf(party("WAREHOUSE", q.sourceWarehouseCode(), null, action).id());
        if (q.targetWarehouseCode() != null && !q.targetWarehouseCode().isBlank()) return List.of();
        List<LogisticsCostSourceView> result = new ArrayList<>();
        for (var s : sales.sales(AuthorizationContext.requireCurrent().tenantId().toString(), q, action, warehouseId)) {
            LogisticsLedgerPartyView warehouse = s.warehouseId() == null ? null : references.ledgerParties("WAREHOUSE", null, s.warehouseId(), null, null, 0, action).stream().findFirst().orElseThrow(() -> bad("来源发货仓库无权限"));
            result.add(new LogisticsCostSourceView(s.id(), s.documentNo(), "SALES_ORDER", s.cityCode(), s.warehouseId(), null,
                    warehouse == null ? null : warehouse.code(), warehouse == null ? null : warehouse.name(),
                    s.customerCode(), s.customerName(), s.statusCode(), s.sourceSystem(), s.originalNo(), s.documentDate(),
                    warehouse == null ? null : warehouse.address(), s.destinationAddress(), null, null));
        }
        return result;
    }
    private LogisticsLedgerPartyView party(String kind, String code, String city, String action) {
        return parties(kind, code, null, null, city, 0, action).stream().filter(p -> Objects.equals(code, p.code())).findFirst().orElseThrow(() -> bad("收发方不存在、已停用或无权限：" + code));
    }
    public LogisticsCostCommand normalize(LogisticsCostCommand c, String action, boolean confirm) {
        var ledger = c.ledger();
        if (confirm && ledger.sourceSync() != null && ledger.sourceSync().differencePending()) throw bad("采购来源存在未处理差异，请编辑核实后确认");
        java.math.BigDecimal amount;
        try { amount = LogisticsLedgerRules.validate(ledger, confirm); } catch (IllegalArgumentException e) { throw bad(e.getMessage()); }
        Set<Long> warehouses = new LinkedHashSet<>();
        Set<String> cities = new LinkedHashSet<>();
        cities.add(c.cityCode());
        String senderCity = ledger.sender().cityCode() == null || ledger.sender().cityCode().isBlank() ? c.cityCode() : ledger.sender().cityCode();
        references.city(senderCity,action);
        cities.add(senderCity);
        var sender = resolve(ledger.sender(), senderCity, action, warehouses);
        if(sender.cityCode()!=null && !sender.cityCode().isBlank()){references.city(sender.cityCode(),action);cities.add(sender.cityCode());}
        List<LogisticsCostLedger.Party> points = new ArrayList<>();
        for (var p : ledger.receivingPoints()) {
            references.city(p.cityCode(), action);
            cities.add(p.cityCode());
            points.add(resolve(p, p.cityCode(), action, warehouses));
        }
        Map<String, String> pointCities = new HashMap<>();
        points.forEach(p -> pointCities.put(p.pointCode(), p.cityCode()));
        for (var link : ledger.sources()) {
            if (!"NONE".equals(link.orderTypeCode())) {
                if ("REQUISITION_OUT".equals(link.orderTypeCode())) throw bad("当前领用请关联真实其他出库单并填写领用用途");
                var q = new LogisticsLedgerSourceQuery(link.orderTypeCode(), link.orderCode(), null, null, null, null, null, null, 0, true, "TRACE".equals(link.linkRoleCode()));
                var source = sources(q, action).stream().filter(s -> Objects.equals(s.documentNo(), link.orderCode())).findFirst().orElseThrow(() -> bad("来源单据不存在、状态不可引用或无权限：" + link.orderCode()));
                if (source.cityCode() != null) { references.city(source.cityCode(), action); cities.add(source.cityCode()); }
                if (source.warehouseId() != null) warehouses.add(source.warehouseId());
                if (source.targetWarehouseId() != null) warehouses.add(source.targetWarehouseId());
            }
            if (link.allocations() != null) for (var allocation : link.allocations()) {
                references.city(allocation.cityCode(), action);
                if (!Objects.equals(pointCities.get(allocation.receivingPointCode()), allocation.cityCode())) throw bad("费用归属城市须与实际收货点城市一致");
            }
        }
        if (!"SELF".equals(ledger.deliveryModeCode()) && !references.validCodes(AuthorizationContext.requireCurrent().tenantId().toString(), "COST_CARRIER").contains(ledger.carrierCode())) throw bad("请选择启用的物流公司");
        String tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        for (var fee : ledger.feeItems()) {
            if (!references.validCodes(tenant, "COST_LOGISTICS_FEE_TYPE").contains(fee.feeTypeCode())) throw bad("请选择启用的物流费用项目");
            if ("QUANTITY".equals(fee.allocationMethodCode()) && !references.validCodes(tenant,"PRODUCT_UNIT").contains(fee.basisUnitCode())) throw bad("请为数量基数选择有效统一单位");
        }
        if (ledger.transportMethodCode()!=null && !ledger.transportMethodCode().isBlank() && !references.validCodes(tenant,"COST_TRANSPORT_KIND").contains(ledger.transportMethodCode())) throw bad("请选择启用的运输方式");
        return new LogisticsCostCommand(c.requestId(), c.revision(), "物流运输-" + c.costDate(), ledger.transportPurposeCode(),
                c.cityCode(), c.cityName(), c.departmentId(), c.ownerEmployeeCode(), c.costDate(), amount,
                c.paymentStatusCode(), null, c.remark(), c.reason(),
                ledger.resolved(sender, List.copyOf(points), List.copyOf(warehouses)).withScopeCities(List.copyOf(cities)));
    }
    private LogisticsCostLedger.Party resolve(LogisticsCostLedger.Party p, String city, String action, Set<Long> warehouses) {
        if ("OTHER".equals(p.partyTypeCode())) return new LogisticsCostLedger.Party(p.pointCode(), p.partyTypeCode(), null, p.manualName(), p.manualName(), p.contactName(), p.phone(), p.address(), p.cityCode());
        var ref = party(p.partyTypeCode(), p.partyCode(), city, action);
        if ("WAREHOUSE".equals(p.partyTypeCode())) {
            warehouses.add(Long.valueOf(ref.id()));
            if (ref.cityCode() != null && p.cityCode() != null && !p.cityCode().isBlank() && !Objects.equals(ref.cityCode(), p.cityCode())) throw bad("仓库与收货城市不一致");
        }
        return new LogisticsCostLedger.Party(p.pointCode(), p.partyTypeCode(), ref.code(), null, ref.name(), p.contactName(), p.phone(), p.address(), "WAREHOUSE".equals(p.partyTypeCode()) ? ref.cityCode() : p.cityCode());
    }
    public LogisticsCostCommand enrich(LogisticsCostCommand c, String action) {
        if (c.ledger() == null) return c;
        var p = c.ledger();
        var sender = enrichParty(p.sender(), p.sender().cityCode() == null || p.sender().cityCode().isBlank() ? c.cityCode() : p.sender().cityCode(), action);
        var points = p.receivingPoints().stream().map(point -> enrichParty(point, point.cityCode(), action)).toList();
        return c.withLedger(p.resolved(sender, points, p.scopeWarehouseIds()), c.amount());
    }
    private LogisticsCostLedger.Party enrichParty(LogisticsCostLedger.Party p, String city, String action) {
        if ("OTHER".equals(p.partyTypeCode())) return p;
        var ref = parties(p.partyTypeCode(), p.partyCode(), null, null, city, 0, action).stream().filter(r -> Objects.equals(r.code(),p.partyCode())).findFirst();
        return new LogisticsCostLedger.Party(p.pointCode(),p.partyTypeCode(),p.partyCode(),null,ref.map(LogisticsLedgerPartyView::name).orElse(p.partyCode()),p.contactName(),p.phone(),p.address(),p.cityCode());
    }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }
}

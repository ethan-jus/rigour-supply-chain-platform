package com.rigour.order.domain.cost;

import com.rigour.order.api.v1.model.LogisticsCostLedger;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/** 逐项目守恒与固定协议校验，金额不信任页面合计。 */
public final class LogisticsLedgerRules {
    private LogisticsLedgerRules() {}

    public static BigDecimal validate(LogisticsCostLedger p, boolean confirm) {
        if (p == null || p.schemaVersion() != 2) throw bad("物流费用结构版本无效");
        choice(p.transportPurposeCode(), Set.of("PURCHASE", "TRANSFER", "DELIVERY", "NON_SALES", "OTHER"), "运输用途");
        choice(p.deliveryModeCode(), Set.of("SELF", "THIRD_PARTY"), "承运方式");
        required(p.costMonth(), 7, "费用归属月份");
        try { YearMonth.parse(p.costMonth()); } catch (RuntimeException e) { throw bad("费用归属月份无效"); }
        if ("THIRD_PARTY".equals(p.deliveryModeCode())) {
            required(p.carrierCode(), 50, "物流公司");
            if (confirm && blank(p.trackingNo())) required(p.noTrackingReason(), 500, "无运单号原因");
        }
        if (p.driverName()!=null && p.driverName().length()>100 || p.vehicleNo()!=null && p.vehicleNo().length()>50) throw bad("司机或车牌信息过长");
        if (p.trackingNo() != null && p.trackingNo().length() > 100) throw bad("运单号过长");
        if (Set.of("NON_SALES", "OTHER").contains(p.transportPurposeCode())) required(p.nonSalesPurposeCode(), 200, "运输用途说明");
        party(p.sender(), false);
        if (p.receivingPoints() == null || p.receivingPoints().isEmpty() || p.receivingPoints().size() > 100) throw bad("请填写1至100个收货点");
        Set<String> points = new HashSet<>();
        for (var point : p.receivingPoints()) {
            party(point, true);
            required(point.pointCode(), 50, "收货点标识");
            if (!points.add(point.pointCode())) throw bad("收货点重复");
        }
        if (p.feeItems() == null || p.feeItems().isEmpty() || p.feeItems().size() > 100) throw bad("请至少填写一项费用，最多100项");
        Map<String, LogisticsCostLedger.Fee> fees = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean unknown = false;
        for (var f : p.feeItems()) {
            if (f == null) throw bad("费用项目无效");
            required(f.feeKey(), 50, "费用项目标识");
            if (fees.putIfAbsent(f.feeKey(), f) != null) throw bad("费用项目标识重复");
            required(f.feeTypeCode(), 50, "费用类型");
            choice(f.bearerCode(), Set.of("COMPANY", "SUPPLIER", "CUSTOMER", "SHARED"), "费用承担方");
            choice(f.allocationMethodCode(), Set.of("MANUAL", "EQUAL", "WEIGHT", "VOLUME", "AMOUNT", "QUANTITY"), "分摊方式");
            if ("QUANTITY".equals(f.allocationMethodCode())) required(f.basisUnitCode(),50,"统一数量基数单位");
            LogisticsCostRules.money(f.feeAmount(), false);
            LogisticsCostRules.money(f.ourAmount(), confirm);
            if ("COMPANY".equals(f.bearerCode())) {
                if (!same(f.feeAmount(), f.ourAmount())) throw bad("我方承担时费用总额须等于我方费用");
            } else if ("SHARED".equals(f.bearerCode())) {
                if (confirm && (f.feeAmount() == null || f.ourAmount() == null) || f.feeAmount() != null && f.ourAmount() != null && f.ourAmount().compareTo(f.feeAmount()) > 0) throw bad("共同承担须填写总额和不超过总额的我方费用");
            } else if (f.ourAmount() == null || f.ourAmount().signum() != 0) throw bad("供应商或客户全额承担时我方费用须为0");
            if (f.ourAmount() == null) unknown = true;
            else {
                total = total.add(f.ourAmount());
                if (f.ourAmount().signum() == 0) required(f.zeroCostReason(), 500, "零元原因");
            }
        }
        if (p.sources() == null || p.sources().isEmpty() || p.sources().size() > 100) throw bad("请选择关联单据或登记无单据原因和费用归属");
        Map<String, BigDecimal> allocated = new HashMap<>();
        Set<String> documents = new HashSet<>();
        for (var s : p.sources()) {
            if (s == null) throw bad("关联单据无效");
            choice(s.orderTypeCode(), Set.of("PURCHASE_ORDER", "TRANSFER_ORDER", "SALES_ORDER", "REQUISITION_OUT", "OTHER_OUT", "NONE"), "关联单据类型");
            choice(s.linkRoleCode(), Set.of("COST", "TRACE"), "关联用途");
            if ("NONE".equals(s.orderTypeCode())) {
                required(s.noSourceReason(), 500, "无单据原因");
                if (!blank(s.orderCode())) throw bad("无单据费用不能填写关联单号");
            }
            else required(s.orderCode(), 64, "关联单据编码");
            if (!documents.add(s.orderTypeCode() + ":" + Objects.toString(s.orderCode(), ""))) throw bad("关联单据重复");
            var rows = s.allocations() == null ? List.<LogisticsCostLedger.Allocation>of() : s.allocations();
            if ("TRACE".equals(s.linkRoleCode()) && !rows.isEmpty()) throw bad("仅追溯单据不得分摊费用");
            if (rows.size() > 1000) throw bad("单据分摊明细过多");
            Set<String> unique = new HashSet<>();
            for (var a : rows) {
                if (a == null || !fees.containsKey(a.feeKey()) || !points.contains(a.receivingPointCode())) throw bad("分摊项目或收货点不存在");
                if (!unique.add(a.feeKey() + ":" + a.receivingPointCode())) throw bad("同一项目和收货点分摊重复");
                required(a.cityCode(), 64, "费用归属城市");
                LogisticsCostRules.money(a.amount(), true);
                String method = fees.get(a.feeKey()).allocationMethodCode();
                if (!Set.of("MANUAL", "EQUAL").contains(method) && fees.get(a.feeKey()).ourAmount() != null && fees.get(a.feeKey()).ourAmount().signum() > 0 && (a.basis() == null || a.basis().signum() <= 0 || a.basis().scale() > 6)) throw bad("自动分摊基数须为正数且最多6位小数");
                allocated.merge(a.feeKey(), a.amount(), BigDecimal::add);
            }
        }
        for (var f : fees.values()) {
            BigDecimal sum = allocated.getOrDefault(f.feeKey(), BigDecimal.ZERO);
            if (f.ourAmount() == null && sum.signum() != 0 || f.ourAmount() != null && sum.compareTo(f.ourAmount()) != 0) throw bad("每项费用的分摊金额必须等于我方费用");
            if (f.ourAmount() != null && f.ourAmount().signum() > 0 && !"MANUAL".equals(f.allocationMethodCode())) {
                record Row(String key, LogisticsCostLedger.Allocation value) {}
                List<Row> rows = new ArrayList<>();
                for (var source : p.sources()) if ("COST".equals(source.linkRoleCode()) && source.allocations() != null)
                    for (var a : source.allocations()) if (a.feeKey().equals(f.feeKey())) rows.add(new Row(source.orderTypeCode() + "|" + Objects.toString(source.orderCode(), "") + "|" + a.receivingPointCode(), a));
                rows.sort(Comparator.comparing(Row::key));
                if (rows.size() > 1) {
                    var expected = com.rigour.shared.core.math.MoneyAllocation.split(f.ourAmount(), rows.stream().map(r -> "EQUAL".equals(f.allocationMethodCode()) ? BigDecimal.ONE : r.value().basis()).toList());
                    for (int i = 0; i < rows.size(); i++) if (expected.get(i).compareTo(rows.get(i).value().amount()) != 0) throw bad("分摊金额与所选方式不符，请重新计算分摊");
                }
            }
        }
        return unknown ? null : total.setScale(2);
    }

    private static void party(LogisticsCostLedger.Party p, boolean receiver) {
        if (p == null) throw bad(receiver ? "请填写收货点" : "请填写发货方");
        choice(p.partyTypeCode(), Set.of("SUPPLIER", "WAREHOUSE", "CUSTOMER", "OTHER"), "收发方类型");
        if ("OTHER".equals(p.partyTypeCode())) required(p.manualName(), 200, "收发方名称");
        else required(p.partyCode(), 64, "收发方编码");
        required(p.address(), 500, receiver ? "收货地址" : "发货地址");
        if (receiver) {
            required(p.contactName(), 100, "收货人");
            required(p.phone(), 50, "收货联系方式");
            required(p.cityCode(), 64, "收货城市");
        }
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static void choice(String v, Set<String> values, String name) { if (v == null || !values.contains(v)) throw bad("请选择有效的" + name); }
    private static void required(String v, int max, String name) { if (blank(v) || v.length() > max) throw bad("请填写有效的" + name); }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

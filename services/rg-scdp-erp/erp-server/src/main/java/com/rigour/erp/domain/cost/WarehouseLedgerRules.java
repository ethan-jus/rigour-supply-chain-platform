package com.rigour.erp.domain.cost;

import com.rigour.erp.api.v1.model.WarehouseCostLedger;
import com.rigour.shared.core.math.MoneyAllocation;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 仓储费用按项目归属月份，付款日期与押金不参与费用分摊。 */
public final class WarehouseLedgerRules {
    private WarehouseLedgerRules() {}
    public static boolean sameComputation(WarehouseCostLedger a, WarehouseCostLedger b) {
        if(!a.withFees(List.of()).equals(b.withFees(List.of())) || a.feeItems().size()!=b.feeItems().size())return false;
        for(int i=0;i<a.feeItems().size();i++){
            var x=a.feeItems().get(i);var y=b.feeItems().get(i);
            if(!x.withAmountAndMonths(null,List.of()).equals(y.withAmountAndMonths(null,List.of())) || !equalMoney(x.feeAmount(),y.feeAmount()) || x.monthAllocations()==null || y.monthAllocations()==null || x.monthAllocations().size()!=y.monthAllocations().size())return false;
            for(int n=0;n<x.monthAllocations().size();n++)if(!x.monthAllocations().get(n).month().equals(y.monthAllocations().get(n).month()) || !equalMoney(x.monthAllocations().get(n).amount(),y.monthAllocations().get(n).amount()))return false;
        }
        return true;
    }
    private static boolean equalMoney(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null && a.compareTo(b)==0;}

    public static WarehouseCostLedger normalize(WarehouseCostLedger p, boolean confirm) {
        if (p == null || p.schemaVersion() != 2) throw bad("仓储费用结构版本无效");
        required(p.warehouseCode(), 50, "仓库编码");
        CostRules.money(p.depositAmount(), false);
        if (p.feeItems() == null || p.feeItems().isEmpty() || p.feeItems().size() > 100) throw bad("请填写1至100项仓储费用");
        Set<String> keys = new HashSet<>();
        List<WarehouseCostLedger.Fee> result = new ArrayList<>();
        for (var f : p.feeItems()) {
            if (f == null) throw bad("费用项目无效");
            required(f.feeKey(), 50, "费用项目标识");
            if (!keys.add(f.feeKey())) throw bad("费用项目重复");
            required(f.feeTypeCode(), 50, "费用类型");
            BigDecimal amount = f.feeAmount();
            if (f.quantity() != null || f.unitCode() != null && !f.unitCode().isBlank() || f.unitPrice() != null) {
                if (f.quantity() == null || f.quantity().signum() <= 0 || f.quantity().scale() > 6 || f.unitPrice() == null || f.unitPrice().signum() < 0 || f.unitPrice().scale() > 6) throw bad("计费数量、单位、单价须完整且有效，最多6位小数");
                required(f.unitCode(), 50, "计费单位");
                amount = f.quantity().multiply(f.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            }
            CostRules.money(amount, confirm);
            String method = Objects.toString(f.allocationMethodCode(), "");
            if (!Set.of("SINGLE_DATE", "MONTH_EQUAL", "DAY_PRORATA", "MANUAL").contains(method)) throw bad("请选择费用归属方式");
            List<WarehouseCostLedger.Month> months;
            if ("SINGLE_DATE".equals(method)) {
                if (f.attributionDate() == null) throw bad("请选择费用归属日期");
                months = List.of(new WarehouseCostLedger.Month(YearMonth.from(f.attributionDate()).toString(), amount));
            } else {
                if (f.serviceFrom() == null || f.serviceTo() == null || f.serviceTo().isBefore(f.serviceFrom())) throw bad("请填写有效的费用服务起止日期");
                var start = YearMonth.from(f.serviceFrom());
                var end = YearMonth.from(f.serviceTo());
                long length = ChronoUnit.MONTHS.between(start, end) + 1;
                if (length > 120) throw bad("一次费用服务期间最多120个月");
                if ("MANUAL".equals(method)) {
                    if (f.monthAllocations() == null || f.monthAllocations().isEmpty() || f.monthAllocations().size() > 120) throw bad("请填写分月归属金额");
                    Set<String> seen = new HashSet<>();
                    BigDecimal sum = BigDecimal.ZERO;
                    for (var m : f.monthAllocations()) {
                        if (m == null) throw bad("归属月份无效");
                        YearMonth month;
                        try { month = YearMonth.parse(m.month()); } catch (RuntimeException ex) { throw bad("归属月份无效"); }
                        if (month.isBefore(start) || month.isAfter(end) || !seen.add(m.month())) throw bad("归属月份超出服务期间或重复");
                        CostRules.money(m.amount(), amount != null || confirm);
                        if (m.amount() != null) sum = sum.add(m.amount());
                    }
                    if (amount != null && sum.compareTo(amount) != 0 || amount == null && sum.signum() != 0) throw bad("各月归属金额合计必须等于项目金额");
                    months = f.monthAllocations().stream().sorted(Comparator.comparing(WarehouseCostLedger.Month::month)).toList();
                } else {
                    List<YearMonth> calendar = new ArrayList<>();
                    List<BigDecimal> weights = new ArrayList<>();
                    for (var month = start; !month.isAfter(end); month = month.plusMonths(1)) {
                        calendar.add(month);
                        LocalDate from = f.serviceFrom().isAfter(month.atDay(1)) ? f.serviceFrom() : month.atDay(1);
                        LocalDate to = f.serviceTo().isBefore(month.atEndOfMonth()) ? f.serviceTo() : month.atEndOfMonth();
                        weights.add("MONTH_EQUAL".equals(method) ? BigDecimal.ONE : BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1));
                    }
                    var money = amount == null ? null : MoneyAllocation.split(amount, weights);
                    months = new ArrayList<>();
                    for (int i = 0; i < calendar.size(); i++) months.add(new WarehouseCostLedger.Month(calendar.get(i).toString(), money == null ? null : money.get(i)));
                }
            }
            result.add(f.withAmountAndMonths(amount, months));
        }
        return p.withFees(List.copyOf(result));
    }
    public static BigDecimal total(WarehouseCostLedger p) {
        if (p.feeItems().stream().anyMatch(f -> f.feeAmount() == null)) return null;
        return p.feeItems().stream().map(WarehouseCostLedger.Fee::feeAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }
    public static String firstMonth(WarehouseCostLedger p) { return months(p).stream().min(String::compareTo).orElse(null); }
    public static String lastMonth(WarehouseCostLedger p) { return months(p).stream().max(String::compareTo).orElse(null); }
    private static List<String> months(WarehouseCostLedger p) { return p.feeItems().stream().flatMap(f -> f.monthAllocations().stream()).map(WarehouseCostLedger.Month::month).toList(); }
    private static void required(String v, int max, String name) { if (v == null || v.isBlank() || v.length() > max) throw bad("请填写有效的" + name); }
    private static IllegalArgumentException bad(String m) { return new IllegalArgumentException(m); }
}

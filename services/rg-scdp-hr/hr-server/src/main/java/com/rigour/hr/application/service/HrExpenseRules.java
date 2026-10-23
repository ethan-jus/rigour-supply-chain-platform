package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.*;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetails;

import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;

/** 报销阶段校验；金额是本条明细金额，行程分组不另记总额。 */
public final class HrExpenseRules {
    public static final Set<String> CATEGORIES =
            Set.of("expense", "office", "warehouse", "freight", "marketing", "purchase");
    public static final Set<String> TRAVEL = Set.of("TRAVEL", "ACCOMMODATION", "TAXI");

    private HrExpenseRules() {}

    /** 费用类型是业务输入，成本类别由服务端确定，不能由录入人指定报表归集。 */
    public static HrExpenseCommand classify(HrExpenseCommand c) {
        String category =
                switch (c.typeCode() == null ? "" : c.typeCode()) {
                    case "OFFICE",
                                    "OFFICE_SUPPLIES",
                                    "CONSUMABLE",
                                    "CONSUMABLES",
                                    "FIXED_ASSET",
                                    "OFFICE_RENT",
                                    "NETWORK",
                                    "OFFICE_MOVE",
                                    "OFFICE_MOVING" ->
                            "office";
                    case "FREIGHT", "CARGO" -> "freight";
                    case "WAREHOUSE_RENT" -> "warehouse";
                    case "MARKETING" -> "marketing";
                    case "GOODS_PURCHASE" -> "purchase";
                    default -> "expense";
                };
        var d = c.expense();
        var classified =
                d == null
                        ? new HrExpenseDetails(
                                category, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null)
                        : new HrExpenseDetails(
                                category,
                                d.purpose(),
                                d.payeeName(),
                                d.itemName(),
                                d.quantity(),
                                d.unit(),
                                d.unitPrice(),
                                d.amountReason(),
                                d.tripReference(),
                                d.startDate(),
                                d.endDate(),
                                d.departure(),
                                d.destination(),
                                d.evidenceNo(),
                                d.noEvidenceReason(),
                                d.freightLeg(),
                                d.relatedCost(),
                                d.courier());
        return new HrExpenseCommand(
                c.requestId(),
                c.revision(),
                c.title(),
                c.typeCode(),
                c.employeeCode(),
                c.cityCode(),
                c.cityName(),
                c.costDate(),
                c.amount(),
                c.paymentStatusCode(),
                c.remark(),
                c.reason(),
                c.costMonth(),
                classified);
    }

    public static String month(HrExpenseCommand c) {
        return c.costMonth() == null || c.costMonth().isBlank()
                ? YearMonth.from(c.costDate()).toString()
                : c.costMonth();
    }

    public static void validate(HrExpenseCommand c, boolean confirm, long attachments) {
        try {
            YearMonth.parse(month(c));
        } catch (RuntimeException e) {
            throw bad("费用月份格式应为 YYYY-MM");
        }
        if (c.typeCode() == null) throw bad("请选择报销类型");
        if (!Set.of("OFFICE_RENT", "WAREHOUSE_RENT", "NETWORK").contains(c.typeCode())
                && !month(c).equals(YearMonth.from(c.costDate()).toString()))
            throw bad("本类报销按实际发生月份统计，请核对费用日期和月份");
        var d = c.expense();
        if (d == null) {
            throw bad("请填写费用用途 / 出差事由");
        }
        required(d.purpose(), 1000, "费用用途 / 出差事由");
        if (d.costCategory() == null || !CATEGORIES.contains(d.costCategory()))
            throw bad("成本归集类别无效");
        if (TRAVEL.contains(c.typeCode()) && !"expense".equals(d.costCategory()))
            throw bad("差旅、住宿和打车统一在报销中统计，不计入人力薪酬");
        text(d.purpose(), 1000, "费用用途");
        text(d.payeeName(), 200, "收款对象");
        text(d.itemName(), 200, "物品名称");
        text(d.unit(), 30, "单位");
        text(d.amountReason(), 500, "金额差异说明");
        text(d.tripReference(), 100, "出差事项编号");
        text(d.departure(), 300, "出发地");
        text(d.destination(), 300, "目的地");
        text(d.evidenceNo(), 200, "凭据编号");
        text(d.noEvidenceReason(), 500, "无凭据说明");
        if (d.startDate() != null && d.endDate() != null && d.endDate().isBefore(d.startDate()))
            throw bad("结束日期不能早于开始日期");
        if (d.quantity() != null
                && (d.quantity().signum() <= 0
                        || d.quantity().precision() > 16
                        || d.quantity().precision() - d.quantity().scale() > 16
                        || d.quantity().scale() > 6)) throw bad("数量须大于零且最多六位小数");
        if (d.unitPrice() != null
                && (d.unitPrice().signum() < 0
                        || d.unitPrice().precision() > 16
                        || d.unitPrice().precision() - d.unitPrice().scale() > 16
                        || d.unitPrice().scale() > 6)) throw bad("单价须为非负数且最多六位小数");
        if (d.freightLeg() != null
                && !d.freightLeg().isBlank()
                && !Set.of("PURCHASE", "TRANSFER", "DELIVERY").contains(d.freightLeg()))
            throw bad("运输环节无效");
        if (d.relatedCost() != null) {
            var x = d.relatedCost();
            if (x.source() == null
                    || !Set.of("ERP", "ORDER").contains(x.source())
                    || x.revision() < 0) throw bad("原费用引用无效");
            if ("ERP".equals(x.source())
                    && (x.category() == null
                            || !Set.of("PURCHASE", "RENTAL", "MARKETING").contains(x.category())))
                throw bad("请选择可关联的进货、场地或营销费用");
            if ("ORDER".equals(x.source()) && !"LOGISTICS".equals(x.category()))
                throw bad("原运费类别无效");
            try {
                if (!UUID.fromString(x.id()).toString().equalsIgnoreCase(x.id()))
                    throw new IllegalArgumentException("Non-canonical UUID");
            } catch (RuntimeException e) {
                throw bad("请选择有效原费用单据");
            }
        }
        if ("COURIER".equals(c.typeCode())) {
            var courier = d.courier();
            if (courier == null) throw bad("请填写寄送内容、收件客户 / 合作方、快递公司和运单号");
            required(courier.contents(), 200, "寄送内容");
            required(courier.recipientName(), 200, "收件客户 / 合作方");
            required(courier.companyName(), 100, "快递公司");
            required(courier.trackingNo(), 100, "运单号");
        }
        if (!confirm) return;
        required(d.purpose(), 1000, "费用用途");
        if (attachments == 0
                && (d.evidenceNo() == null || d.evidenceNo().isBlank())
                && (d.noEvidenceReason() == null || d.noEvidenceReason().isBlank()))
            throw bad("请上传费用凭据、登记凭据编号或说明无凭据原因");
        if (TRAVEL.contains(c.typeCode())) {
            required(d.destination(), 300, "目的地");
            if (!"ACCOMMODATION".equals(c.typeCode())) required(d.departure(), 300, "出发地");
            if (d.startDate() == null || d.endDate() == null) throw bad("请补充行程或住宿起止日期");
        }
        if (Set.of("OFFICE", "OFFICE_SUPPLIES", "CONSUMABLE", "CONSUMABLES", "FIXED_ASSET")
                .contains(c.typeCode())) {
            required(d.itemName(), 200, "物品名称");
            required(d.unit(), 30, "单位");
            if (d.quantity() == null) throw bad("请填写物品数量");
        }
        if ("freight".equals(d.costCategory())
                && (d.freightLeg() == null || d.freightLeg().isBlank())) throw bad("请填写货运报销的运输环节");
        if (d.unitPrice() != null
                && d.quantity() != null
                && d.unitPrice()
                                .multiply(d.quantity())
                                .setScale(2, RoundingMode.HALF_UP)
                                .compareTo(c.amount())
                        != 0) required(d.amountReason(), 500, "金额与单价乘数量的差异说明");
    }
}

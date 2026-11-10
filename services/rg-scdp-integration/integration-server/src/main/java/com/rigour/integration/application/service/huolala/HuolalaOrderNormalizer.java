package com.rigour.integration.application.service.huolala;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

/** 列表和详情使用各自字段，保留未知金额，不使用废弃pay_status，也不重复扣优惠。 */
public final class HuolalaOrderNormalizer {
    private HuolalaOrderNormalizer() {}

    public static CarrierOrder normalize(
            Connection c,
            JsonNode list,
            JsonNode detail,
            String rawId,
            String employeeCode,
            String employeeName) {
        return normalize(c, list, detail, rawId, employeeCode, employeeName, null, null);
    }

    public static CarrierOrder normalize(
            Connection c,
            JsonNode list,
            JsonNode detail,
            String rawId,
            String employeeCode,
            String employeeName,
            String staffId,
            String employeePhone) {
        JsonNode item = detail.path("order_detail_item").path("order_item");
        boolean detailed = !item.isMissingNode() && !item.isNull();
        if (!detailed) item = list;
        String no = item.path("order_display_id").asText(list.path("order_display_id").asText(""));
        if (!no.matches("[0-9]{6,80}")) throw new IllegalArgumentException("货拉拉订单号缺失或精度异常");
        String enterprise = item.path("ep_id").asText(list.path("ep_id").asText(""));
        if (!c.enterpriseId().equals(enterprise))
            throw new IllegalArgumentException("平台订单所属企业与连接配置不一致");
        int status = item.path("order_status").asInt(-1);
        BigDecimal price = cents(item, "price_fen"),
                paid = cents(item, detailed ? "paid_amount" : "paid_amount_fen"),
                unpaid = cents(item, "unpaid_amount"),
                refund = cents(item, "refund_amount"),
                coupon = cents(list, "coupon_fen"),
                bonus = cents(item, "bonus_pay_fen");
        List<Stop> stops = new ArrayList<>();
        for (var s : array(item.path("addr_info_arr")))
            stops.add(
                    new Stop(
                            s.path("name").asText(""),
                            join(s.path("addr").asText(""), s.path("house_number").asText("")),
                            s.path("contact_name").asText(s.path("contacts_name").asText("")),
                            s.path("contact_phone_no")
                                    .asText(s.path("contacts_phone_no").asText(""))));
        List<Fee> fees = new ArrayList<>();
        int feeIndex = 0;
        for (var f : array(item.path("order_price_arr"))) {
            String lineId = "fee-" + (feeIndex++), name = f.path("bill_name").asText("");
            List<Voucher> vouchers = new ArrayList<>();
            int imageIndex = 0;
            for (var image : array(f.path("img_urls"))) {
                String url = image.asText("");
                if (!url.isBlank())
                    vouchers.add(
                            new Voucher(
                                    voucherId(c.id(), no, lineId, url),
                                    name + "凭证" + (++imageIndex),
                                    null,
                                    null,
                                    "PENDING",
                                    null));
            }
            fees.add(
                    new Fee(
                            f.path("type").asText(""),
                            name,
                            cents(f, "price_fen"),
                            lineId,
                            List.copyOf(vouchers)));
        }
        String issue = null;
        if (!detailed) issue = "未取得订单详情，请核对原始账单";
        else if (status != 2) issue = "平台账单尚未最终完成，或订单已取消、存在申诉";
        else if (price == null || price.signum() < 0) issue = "平台未提供有效账单金额";
        else if (refund == null) issue = "平台退款信息未明确，请核对最终净费用";
        else if (refund.signum() > 0) issue = "该订单存在退款，请核对最终净费用，不能重复减退款";
        else if (item.path("is_complain_order").asInt() == 1) issue = "平台账单存在申诉，请核对最终净费用";
        else if (bonus != null && bonus.signum() > 0) issue = "平台账单使用赠金，请核对我方实际承担净费用";
        else if (paid == null
                || unpaid == null
                || paid.signum() < 0
                || unpaid.signum() < 0
                || price.compareTo(paid.add(unpaid)) != 0) issue = "平台总价与已付、未付金额不一致，请核对最终净费用";
        String sourceUser = list.path("user_id").asText("");
        String useTime = item.path("order_time").asText(list.path("order_time").asText(""));
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(
                "收货人信息",
                stops.stream()
                        .skip(1)
                        .map(s -> join(s.name(), s.contactName(), s.address()))
                        .collect(java.util.stream.Collectors.joining("；")));
        raw.put("下单人(员工)", Objects.toString(employeeName, ""));
        raw.put("下单员工手机号", Objects.toString(employeePhone, ""));
        raw.put("车型", item.path("vehicle_type_name").asText(""));
        raw.put("用车原由", item.path("order_reason").asText(""));
        raw.put("订单备注", item.path("remark").asText(""));
        raw.put("详情状态", detailed ? "DETAIL" : "LIST");
        raw.put("平台订单状态码", String.valueOf(status));
        raw.put("路线已提供", String.valueOf(item.path("addr_info_arr").isArray()));
        raw.put("费用明细已提供", String.valueOf(item.path("order_price_arr").isArray()));
        raw.put("回单已提供", String.valueOf(item.path("receipt_url").isArray()));
        raw.put("账单凭证已提供", String.valueOf(item.path("img_urls").isArray()));
        raw.put("下单时间", item.path("create_time").asText(list.path("create_time").asText("")));
        raw.put("司机", detail.path("driver_info").path("name").asText(""));
        raw.put("司机联系电话", detail.path("driver_info").path("phone_no").asText(""));
        raw.put("车牌号", detail.path("driver_info").path("license_plate").asText(""));
        String label =
                switch (status) {
                    case 0 -> "匹配中";
                    case 1, 15 -> "待装货";
                    case 2 -> "已完成";
                    case 3 -> "已取消";
                    case 4 -> "司机拒绝";
                    case 5 -> "超时取消";
                    case 6 -> "订单未支付";
                    case 7, 16 -> "运送中";
                    case 8 -> "五天未装货取消";
                    case 9 -> "十天未装货取消";
                    case 10 -> "司机完成";
                    case 13 -> detailed ? "司机发送账单" : "账单确认";
                    case 14 -> "申诉中";
                    case 11 -> "异常完成";
                    case 17 -> "支付失败取消";
                    case 18 -> "支付超时取消";
                    default -> "未知状态（" + status + "）";
                };
        var facts =
                new SourceFacts(
                        c.id(),
                        rawId,
                        sourceUser,
                        staffId,
                        employeeName,
                        employeeCode,
                        price,
                        price != null && price.signum() >= 0 ? price : null,
                        unpaid,
                        refund,
                        coupon,
                        bonus,
                        item.path("complete_time")
                                .asText(list.path("order_status_2_time").asText("")),
                        item.path("update_bill_time").asText(""),
                        issue == null ? "FINAL" : "REVIEW",
                        issue,
                        List.copyOf(stops),
                        List.copyOf(fees),
                        voucherList(c.id(), no, "receipts", item.path("receipt_url"), "运输回单"),
                        voucherList(c.id(), no, "billVouchers", item.path("img_urls"), "账单凭证"));
        return new CarrierOrder(enterprise, no, label, useTime, paid, raw, List.of(), 0, facts);
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node.isArray() ? node : List.of();
    }

    static List<Voucher> voucherList(
            String connector, String no, String line, JsonNode urls, String name) {
        var values = new ArrayList<Voucher>();
        int index = 0;
        for (var value : array(urls))
            if (!value.asText("").isBlank())
                values.add(
                        new Voucher(
                                voucherId(connector, no, line, value.asText()),
                                name + (++index),
                                null,
                                null,
                                "PENDING",
                                null));
        return List.copyOf(values);
    }

    /** 付款进度及同步时间变化不作成本差异；账单、退款、费用构成变化必须重新核对。 */
    public static String fingerprint(CarrierOrder order, ObjectMapper json) {
        var f = order.facts();
        var values = new TreeMap<String, Object>();
        values.put("orderNo", order.orderNo());
        values.put("enterprise", order.enterpriseId());
        values.put("coupon", f.couponAmount());
        values.put("bonus", f.bonusAmount());
        values.put("bill", f.billAmount());
        values.put("refund", f.refundAmount());

        values.put(
                "fees",
                f.fees().stream()
                        .sorted(
                                Comparator.comparing(Fee::code)
                                        .thenComparing(Fee::name)
                                        .thenComparing(x -> Objects.toString(x.amount(), "")))
                        .map(x -> Arrays.asList(x.code(), x.name(), x.amount()))
                        .toList());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(values)));
        } catch (Exception e) {
            throw new IllegalStateException("账单摘要计算失败");
        }
    }

    public static String voucherId(String connector, String order, String line, String url) {
        try {
            var uri = java.net.URI.create(url);
            String identity =
                    connector
                            + "|"
                            + order
                            + "|"
                            + line
                            + "|"
                            + uri.getHost()
                            + "|"
                            + uri.getPath();
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            identity.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("平台凭证地址无效");
        }
    }

    private static BigDecimal cents(JsonNode n, String field) {
        var v = n.path(field);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) return null;
        String value = v.asText();
        if (!value.matches("-?[0-9]{1,16}"))
            throw new IllegalArgumentException("平台金额字段不是整数分：" + field);
        return new BigDecimal(value).movePointLeft(2).setScale(2);
    }

    private static String join(String... values) {
        return Arrays.stream(values)
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }
}

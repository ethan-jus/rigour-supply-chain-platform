package com.rigour.integration.application.service.thirdparty.huolala;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** 企业详情字段路径共用，保证展示引用与COS队列逐项对应；平台URL仅留在Integration。 */
public final class HuolalaEvidence {
    private HuolalaEvidence() {}

    public record Attachment(String lineId, String name, String url) {}

    public static JsonNode detailField(JsonNode detail, String name) {
        var node = detail.path("order_detail_item");
        var value = node.path(name);
        // 企业接口以order_detail_item为准；兼容已落地的旧结构，避免丢弃旧凭证。
        return value.isMissingNode() || value.isNull() ? node.path("order_item").path(name) : value;
    }

    public static String feeLine(JsonNode fee, Map<String, Integer> occurrences) {
        String identity =
                fee.path("type").asText("")
                        + "|"
                        + fee.path("bill_name").asText("")
                        + "|"
                        + fee.path("price_fen").asText("");
        int occurrence = occurrences.merge(identity, 1, Integer::sum);
        return "fee-"
                + UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "-"
                + occurrence;
    }

    public static List<Attachment> attachments(JsonNode detail) {
        var result = new ArrayList<Attachment>();
        var item = detail.path("order_detail_item").path("order_item");
        var occurrences = new HashMap<String, Integer>();
        for (var fee : item.path("order_price_arr"))
            add(
                    result,
                    feeLine(fee, occurrences),
                    fee.path("bill_name").asText("费用") + "凭证",
                    fee.path("img_urls"));
        add(result, "receipts", "拍照回单", detailField(detail, "receipt_url"));
        // 兼容历史回执中的整单凭证；文档中的费用票据仍逐项保留在费用行。
        add(result, "billVouchers", "账单凭证", detailField(detail, "img_urls"));
        var stops = item.path("addr_info_arr");
        for (int i = 0; stops.isArray() && i < stops.size(); i++)
            add(result, "goods-" + i, phase(i, stops.size()), stops.get(i).path("goods_imgs"));
        return List.copyOf(result);
    }

    public static String phase(int index, int size) {
        return index == 0 ? "装货照片" : index == size - 1 ? "卸货照片" : "途经点照片";
    }

    private static void add(List<Attachment> target, String line, String name, JsonNode urls) {
        if (!urls.isArray()) return;
        var seen = new HashMap<String, Integer>();
        if (name.length() > 235) name = name.substring(0, 235);
        int index = 0;
        for (var value : urls) {
            if (!value.isTextual() || value.asText().isBlank()) continue;
            String url = value.asText().trim();
            // 同一文件的签名更新不会产生第二份引用；保留最新URL供失败任务重试。
            String id = HuolalaOrderNormalizer.voucherId("", "", line, url);
            Integer position = seen.get(id);
            if (position == null) {
                seen.put(id, target.size());
                target.add(new Attachment(line, name + (++index), url));
            } else target.set(position, new Attachment(line, target.get(position).name(), url));
        }
    }
}

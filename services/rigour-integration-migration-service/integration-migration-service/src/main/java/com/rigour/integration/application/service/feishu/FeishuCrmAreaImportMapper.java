package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从飞书各业务表中抽取 CRM 区域/城市主数据。 */
final class FeishuCrmAreaImportMapper {
    private static final List<String> REGION_FIELDS = List.of(
            "销售区域", "所属地区", "区域", "地区", "大区", "业务区域", "归属地区");
    private static final List<String> CITY_FIELDS = List.of(
            "城市", "市", "意向城市", "订单城市", "所属城市");

    List<ExternalCrmAreaRowCommand> rows(List<StoredRawRow> rawRows) {
        Map<String, ExternalCrmAreaRowCommand> result = new LinkedHashMap<>();
        for (StoredRawRow row : rawRows == null ? List.<StoredRawRow>of() : rawRows) {
            String regionName = value(row.values(), REGION_FIELDS);
            String cityName = value(row.values(), CITY_FIELDS);
            if (regionName == null && cityName == null) continue;
            String sourceTenantKey = text(row.tableCode(), 128);
            String sourceAreaId = sourceAreaId(sourceTenantKey, regionName, cityName);
            String key = (regionName == null ? "" : regionName) + '\n' + (cityName == null ? "" : cityName);
            result.putIfAbsent(key, new ExternalCrmAreaRowCommand(
                    null,
                    sourceTenantKey,
                    sourceAreaId,
                    regionName,
                    cityName,
                    row.sourceCreatedAt(),
                    null));
        }
        return List.copyOf(result.values());
    }

    private static String sourceAreaId(String tableCode, String regionName, String cityName) {
        String value = (tableCode == null ? "FEISHU" : tableCode)
                + ":" + (regionName == null ? "-" : regionName)
                + ":" + (cityName == null ? "-" : cityName);
        return value.length() <= 128 ? value : "FEISHU_AREA:" + sha256(value).substring(0, 32);
    }

    private static String value(Map<String, String> values, List<String> names) {
        if (values == null || values.isEmpty()) return null;
        for (String name : names) {
            String value = text(values.get(name), 80);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM不支持SHA-256", exception);
        }
    }
}

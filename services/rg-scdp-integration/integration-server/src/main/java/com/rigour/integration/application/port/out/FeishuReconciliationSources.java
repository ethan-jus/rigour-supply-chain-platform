package com.rigour.integration.application.port.out;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 由受控配置提供的租户来源目录；用户不能传入外部连接定位参数。 */
public interface FeishuReconciliationSources {
    List<Source> forTenant(UUID tenantId);

    record Source(String id, String name, UUID tenantId, String appToken, List<Table> tables, String sourceUrl) {
        public Source(String id, String name, UUID tenantId, String appToken, List<Table> tables) {
            this(id, name, tenantId, appToken, tables, null);
        }
        public Source {
            tables = List.copyOf(tables);
            sourceUrl = resolveSourceUrl(sourceUrl, appToken);
        }
        public boolean filtered() { return tables.stream().anyMatch(Table::filtered); }

        private static String resolveSourceUrl(String value, String appToken) {
            if (value == null || value.isBlank()) return "https://feishu.cn/base/" + appToken;
            try {
                URI uri = URI.create(value);
                String host = uri.getHost();
                if (value.length() > 2000 || !"https".equalsIgnoreCase(uri.getScheme())
                        || host == null || !host.toLowerCase(Locale.ROOT).endsWith(".feishu.cn")
                        || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                        || uri.getRawFragment() != null || !("/base/" + appToken).equals(uri.getRawPath())) {
                    throw new IllegalArgumentException();
                }
                return value;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("飞书对账来源URL必须是同一Base的HTTPS飞书租户地址");
            }
        }
    }

    record Table(String tableId, String tableCode, String viewId) {
        public boolean filtered() { return viewId != null && !viewId.isBlank(); }
        public String name() {
            return switch (tableCode) {
                case "FEISHU_SALES_ORDER" -> "销售订单";
                case "FEISHU_SALES_ORDER_LINE" -> "销售订单明细";
                case "FEISHU_PRODUCT" -> "商品库";
                default -> throw new IllegalStateException("未支持的飞书对账表类型");
            };
        }
    }
}

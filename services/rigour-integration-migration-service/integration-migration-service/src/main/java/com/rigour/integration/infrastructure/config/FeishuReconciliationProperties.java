package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.FeishuReconciliationSources;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 来源白名单只在服务端配置；无配置时不猜测租户、Base 或表。 */
@ConfigurationProperties(prefix = "rigour.integration.feishu.reconciliation")
public class FeishuReconciliationProperties implements FeishuReconciliationSources {
    private List<SourceConfig> sources = new ArrayList<>();

    public List<SourceConfig> getSources() { return sources; }
    public void setSources(List<SourceConfig> value) { sources = value; }

    @Override
    public List<Source> forTenant(UUID tenantId) {
        if (tenantId == null || sources == null) return List.of();
        List<Source> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SourceConfig source : sources) {
            if (source == null || !tenantId.equals(source.tenantId)) continue;
            if (!identifier(source.id, 128) || !ids.add(source.id)
                    || source.name == null || source.name.isBlank() || source.name.length() > 255
                    || !identifier(source.appToken, 128) || source.tables == null
                    || source.tables.isEmpty() || source.tables.size() > 3) {
                throw invalid();
            }
            Set<String> tableIds = new HashSet<>();
            Set<String> tableCodes = new HashSet<>();
            List<Table> tables = new ArrayList<>();
            for (TableConfig table : source.tables) {
                if (table == null || !identifier(table.tableId, 128) || !tableIds.add(table.tableId)
                        || !Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE", "FEISHU_PRODUCT").contains(
                                table.tableCode == null ? "" : table.tableCode)
                        || !tableCodes.add(table.tableCode)
                        || (table.viewId != null && !table.viewId.isBlank() && !identifier(table.viewId, 128))) {
                    throw invalid();
                }
                tables.add(new Table(table.tableId, table.tableCode,
                        table.viewId == null || table.viewId.isBlank() ? null : table.viewId));
            }
            if (!tableCodes.containsAll(Set.of("FEISHU_SALES_ORDER", "FEISHU_SALES_ORDER_LINE"))) throw invalid();
            try {
                result.add(new Source(source.id, source.name, tenantId, source.appToken, tables, source.sourceUrl));
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }
        return List.copyOf(result);
    }

    private static boolean identifier(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9_-]+");
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("当前租户飞书对账来源配置无效，请联系管理员核对配置");
    }

    public static class SourceConfig {
        private String id;
        private String name;
        private UUID tenantId;
        private String appToken;
        private String sourceUrl;
        private List<TableConfig> tables = new ArrayList<>();
        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public UUID getTenantId() { return tenantId; }
        public void setTenantId(UUID value) { tenantId = value; }
        public String getAppToken() { return appToken; }
        public void setAppToken(String value) { appToken = value; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String value) { sourceUrl = value; }
        public List<TableConfig> getTables() { return tables; }
        public void setTables(List<TableConfig> value) { tables = value; }
    }

    public static class TableConfig {
        private String tableId;
        private String tableCode;
        private String viewId;
        public String getTableId() { return tableId; }
        public void setTableId(String value) { tableId = value; }
        public String getTableCode() { return tableCode; }
        public void setTableCode(String value) { tableCode = value; }
        public String getViewId() { return viewId; }
        public void setViewId(String value) { viewId = value; }
    }
}

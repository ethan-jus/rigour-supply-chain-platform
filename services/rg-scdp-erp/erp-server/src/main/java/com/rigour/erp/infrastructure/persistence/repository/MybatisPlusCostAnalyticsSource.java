package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.application.port.out.AnalyticsSourceSnapshotStore.Page;
import com.rigour.erp.infrastructure.persistence.entity.ErpCostEntity;
import com.rigour.erp.infrastructure.persistence.mapper.ErpCostMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 成本事实使用现有 BaseMapper；TEXT_UUID 的数据库排序与游标采用相同的规范 UUID 文本。 */
@Repository
public class MybatisPlusCostAnalyticsSource {
    public static final String DATASET = "ERP_COST_ENTRY";
    public static final List<String> COLUMNS =
            List.of(
                    "id",
                    "tenant_id",
                    "document_no",
                    "title",
                    "type_code",
                    "city_code",
                    "department_id",
                    "cost_date",
                    "amount",
                    "status_code",
                    "payment_status_code",
                    "data_json",
                    "revision",
                    "created_by",
                    "updated_time",
                    "category_code",
                    "premises_type",
                    "source_system",
                    "owner_employee_code",
                    "owner_employee_name",
                    "warehouse_id",
                    "source_warehouse_id",
                    "warehouse_name",
                    "sources_json");
    private final ErpCostMapper mapper;

    public MybatisPlusCostAnalyticsSource(ErpCostMapper mapper) {
        this.mapper = mapper;
    }

    private com.rigour.erp.application.port.out.ProcurementGoodsCosts goods;

    @org.springframework.beans.factory.annotation.Autowired
    void procurementGoods(com.rigour.erp.application.port.out.ProcurementGoodsCosts goods) {
        this.goods = goods;
    }

    public String version(String tenant) {
        return mapper.analyticsVersion(tenant) + (goods == null ? "" : ":" + goods.version(tenant));
    }

    @Transactional(readOnly = true)
    public Page page(String tenant, String after) {
        String cursor = after == null ? "" : after.toLowerCase(Locale.ROOT);
        if (!cursor.isEmpty()
                && !cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("TEXT_UUID源游标无效");
        String version = version(tenant);
        var query =
                new QueryWrapper<ErpCostEntity>()
                        .select(COLUMNS.toArray(String[]::new))
                        .eq("tenant_id", tenant)
                        .eq("deleted", 0)
                        .gt(!cursor.isEmpty(), "id", cursor)
                        .orderByAsc("id")
                        .last("LIMIT 1000");
        List<Map<String, String>> items = new ArrayList<>();
        var departments =
                goods == null ? Map.<String, String>of() : goods.warehouseDepartments(tenant);
        for (var row : mapper.selectMaps(query)) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : COLUMNS) values.put(column, exact(row.get(column)));
            if (goods != null && "PURCHASE".equals(values.get("category_code"))) {
                values.put("data_json", goods.reconcileLegacy(tenant, values.get("data_json")));
                values.put("department_id", departments.get(values.get("warehouse_id")));
                values.put("owner_employee_code", null);
                values.put("owner_employee_name", null);
            }
            items.add(values);
        }
        if (goods != null)
            for (var row : goods.page(tenant, cursor)) {
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : COLUMNS) values.put(column, row.get(column));
                items.add(values);
            }
        items.sort(Comparator.comparing(v -> v.get("id")));
        return new Page(version, items.stream().limit(1000).toList());
    }

    private static String exact(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        if (v instanceof byte[] bytes)
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return v.toString();
    }
}

package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.application.port.out.AnalyticsSourceSnapshotStore.Page;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 成本事实使用现有 BaseMapper；TEXT_UUID 的数据库排序与游标采用相同的规范 UUID 文本。 */
@Repository
public class MybatisPlusCostAnalyticsSource {
    public static final String DATASET = "ORDER_LOGISTICS_COST";
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
                    "payer_code",
                    "source_system",
                    "owner_employee_code",
                    "owner_employee_name",
                    "warehouse_id",
                    "source_warehouse_id",
                    "warehouse_name",
                    "sources_json");
    private final OrderLogisticsCostMapper mapper;

    public MybatisPlusCostAnalyticsSource(OrderLogisticsCostMapper mapper) {
        this.mapper = mapper;
    }

    public String version(String tenant) {
        return mapper.analyticsVersion(tenant);
    }

    @Transactional(readOnly = true)
    public Page page(String tenant, String after) {
        String cursor = after == null ? "" : after.toLowerCase(Locale.ROOT);
        if (!cursor.isEmpty()
                && !cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("TEXT_UUID源游标无效");
        String version = version(tenant);
        var query =
                new QueryWrapper<OrderLogisticsCostEntity>()
                        .select(COLUMNS.toArray(String[]::new))
                        .eq("tenant_id", tenant)
                        .gt(!cursor.isEmpty(), "id", cursor)
                        .orderByAsc("id")
                        .last("LIMIT 1000");
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : mapper.selectMaps(query)) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : COLUMNS) values.put(column, exact(row.get(column)));
            items.add(values);
        }
        return new Page(version, items);
    }

    private static String exact(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        if (v instanceof byte[] bytes)
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return v.toString();
    }
}

package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.AnalyticsSourceSnapshotStore;
import com.rigour.order.infrastructure.persistence.mapper.OrderAnalyticsSourceMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderAnalyticsSourceSql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 本领域源快照；保持原始列名、空值与金额精度，不跨库读取。 */
@Repository
public class MybatisPlusAnalyticsSourceSnapshotStore implements AnalyticsSourceSnapshotStore {
    private final OrderAnalyticsSourceMapper mapper;

    public MybatisPlusAnalyticsSourceSnapshotStore(OrderAnalyticsSourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String version(String tenant, String dataset) {
        OrderAnalyticsSourceSql.dataset(dataset);
        return mapper.version(tenant, dataset);
    }

    @Override
    @Transactional(readOnly = true)
    public Page page(String tenant, String dataset, String after) {
        var d = OrderAnalyticsSourceSql.dataset(dataset);
        String cursor = after == null ? "" : after;
        if (!cursor.isEmpty() && !cursor.matches(d.binaryKey() ? "[0-9a-fA-F]{32}" : "[0-9]{1,20}"))
            throw new IllegalArgumentException("源数据游标无效");
        String version = version(tenant, dataset);
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : mapper.page(tenant, dataset, cursor)) {
            Map<String, String> item = new LinkedHashMap<>();
            for (String column : d.columns()) item.put(column, exact(row.get(column)));
            items.add(item);
        }
        return new Page(version, items);
    }

    private static String exact(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return HexFormat.of().withUpperCase().formatHex(bytes);
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof java.sql.Timestamp timestamp)
            return timestamp.toLocalDateTime().toString().replace('T', ' ');
        if (value instanceof java.time.LocalDateTime time) return time.toString().replace('T', ' ');
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        return value.toString();
    }
}

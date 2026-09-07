package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** 按后台同一筛选读取最小投影，只保留分组统计；日期沿用 JDBC 时间语义再转换为中国日期。 */
@Repository
class TemporaryCheckinStatisticsRepository {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    TemporaryCheckinStatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    AttendanceSummary aggregate(UUID tenantId, TemporaryCheckinService.AdminQuery filters,
            TemporaryCheckinRepository.AdminReadOptions options) {
        StringBuilder sql = new StringBuilder("""
                WITH visit_ranks AS (
                    SELECT id, ROW_NUMBER() OVER (
                        PARTITION BY tenant_id,salesperson_id,store_id ORDER BY submitted_at,id
                    ) AS visit_ordinal
                    FROM temp_sales_checkin_submission WHERE tenant_id=? AND status='SUBMITTED'
                )
                SELECT s.id,s.status,s.city,s.salesperson_id,s.salesperson_name_snapshot,
                       s.store_id,s.submitted_at,s.review_status
                FROM temp_sales_checkin_submission s LEFT JOIN visit_ranks r ON r.id=s.id
                WHERE s.tenant_id=?
                """);
        List<Object> arguments = new ArrayList<>(List.of(SalesUuidCodec.encode(tenantId), SalesUuidCodec.encode(tenantId)));
        TemporaryCheckinRepository.appendAdminFilters(sql, arguments, filters.from(), filters.toExclusive(),
                filters.city(), filters.salespersonId(), filters.status(), filters.visitType(), filters.escapedQuery(), options);
        Accumulator result = new Accumulator();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql.toString(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // MySQL 的前向流避免把完整明细缓存在驱动；其他测试驱动使用普通读取提示。
            statement.setFetchSize(connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
                    ? Integer.MIN_VALUE : 256);
            for (int index = 0; index < arguments.size(); index++) statement.setObject(index + 1, arguments.get(index));
            return statement;
        }, (RowCallbackHandler) result::accept);
        return result.finish();
    }

    private static final class Accumulator {
        private long totalVisits;
        private long pendingReviewTotal;
        private final Set<UUID> checkedInSalespeople = new HashSet<>();
        private final Map<DayKey, DayAccumulator> groups = new HashMap<>();

        void accept(ResultSet row) throws SQLException {
            totalVisits++;
            boolean pending = "PENDING".equals(row.getString("review_status"));
            if (pending) pendingReviewTotal++;
            if (!"SUBMITTED".equals(row.getString("status"))) return;
            UUID salespersonId = SalesUuidCodec.decode(row.getBytes("salesperson_id"));
            checkedInSalespeople.add(salespersonId);
            Timestamp submitted = row.getTimestamp("submitted_at");
            // 损坏的历史提交没有可归属日期，不能补造日期；仍保留总数和已打卡人数。
            if (submitted == null) return;
            Instant at = submitted.toInstant();
            DayKey key = new DayKey(at.atZone(BUSINESS_ZONE).toLocalDate(), row.getString("city"), salespersonId);
            groups.computeIfAbsent(key, DayAccumulator::new).accept(row, at, pending);
        }

        AttendanceSummary finish() {
            List<DailyAttendance> items = groups.values().stream().map(DayAccumulator::finish)
                    .sorted(Comparator.comparing(DailyAttendance::date).reversed()
                            .thenComparing(DailyAttendance::city, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(DailyAttendance::salespersonName, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(item -> item.salespersonId().toString()))
                    .toList();
            return new AttendanceSummary(totalVisits, checkedInSalespeople.size(), pendingReviewTotal, items);
        }
    }

    private record DayKey(LocalDate date, String city, UUID salespersonId) { }

    private static final class DayAccumulator {
        private final DayKey key;
        private final Set<UUID> stores = new HashSet<>();
        private long visits;
        private long pendingReviews;
        private Instant first;
        private Instant last;
        private String latestId;
        private String salespersonName;

        DayAccumulator(DayKey key) { this.key = key; }

        void accept(ResultSet row, Instant at, boolean pending) throws SQLException {
            visits++;
            if (pending) pendingReviews++;
            byte[] storeId = row.getBytes("store_id");
            if (storeId != null) stores.add(SalesUuidCodec.decode(storeId));
            if (first == null || at.isBefore(first)) first = at;
            String id = SalesUuidCodec.decode(row.getBytes("id")).toString();
            if (last == null || at.isAfter(last) || (at.equals(last) && id.compareTo(latestId) > 0)) {
                last = at;
                latestId = id;
                salespersonName = row.getString("salesperson_name_snapshot");
            }
        }

        DailyAttendance finish() {
            return new DailyAttendance(key.date(), key.city(), key.salespersonId(), salespersonName,
                    visits, stores.size(), first, last, pendingReviews);
        }
    }

    record AttendanceSummary(long totalVisits, long checkedInSalespeople, long pendingReviewTotal,
            List<DailyAttendance> items) { }

    record DailyAttendance(LocalDate date, String city, UUID salespersonId, String salespersonName,
            long visitCount, long storeCount, Instant firstCheckinAt, Instant lastCheckinAt,
            long pendingReviewCount) { }
}

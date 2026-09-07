package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.AdminReadOptions;
import com.rigour.sales.temporarycheckin.TemporaryCheckinStatisticsRepository.AttendanceSummary;
import com.rigour.sales.temporarycheckin.TemporaryCheckinStatisticsRepository.DailyAttendance;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 复用后台城市权限及筛选，汇总匹配记录；未打卡不等同于旷工或缺勤。 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class TemporaryCheckinStatisticsService {
    private final TemporaryCheckinService checkins;
    private final TemporaryCheckinStatisticsRepository repository;
    private final TemporaryCheckinProperties properties;

    TemporaryCheckinStatisticsService(TemporaryCheckinService checkins,
            TemporaryCheckinStatisticsRepository repository, TemporaryCheckinProperties properties) {
        this.checkins = checkins; this.repository = repository; this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AttendancePage summary(AdminScope scope, LocalDate from, LocalDate to, String city,
            UUID salespersonId, String status, String visitType, String query, AdminReadOptions options,
            Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? 50 : requestedSize;
        if (page < 0 || size < 1 || size > 100) throw TemporaryCheckinException.badRequest("汇总页码不能小于0，每页数量须在1到100之间");
        AttendanceSummary all = exportSummary(scope, from, to, city, salespersonId, status, visitType, query, options);
        int total = all.items().size();
        long offset = (long) page * size;
        List<DailyAttendance> items = offset >= total ? List.of()
                : all.items().subList((int) offset, (int) Math.min(offset + size, total));
        return new AttendancePage(all.totalVisits(), all.checkedInSalespeople(), all.pendingReviewTotal(),
                items, page, size, total, total == 0 ? 0 : (int) (((long) total - 1) / size + 1));
    }

    /** 导出读取同一筛选的全量日汇总；不复用页面分页，也不截断为明细导出条数。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AttendanceSummary exportSummary(AdminScope scope, LocalDate from, LocalDate to, String city,
            UUID salespersonId, String status, String visitType, String query, AdminReadOptions options) {
        var filters = checkins.normalizeAdminQuery(scope, from, to, city, salespersonId, status, visitType, query);
        return repository.aggregate(properties.requireTenantId(), filters, options);
    }

    record AttendancePage(long totalVisits, long checkedInSalespeople, long pendingReviewTotal,
            List<DailyAttendance> items, int page, int size, long totalElements, int totalPages) { }
}

package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 使用既有后台会话与城市权限提供拜访打卡汇总，不据记录缺失推导人事考勤结论。 */
@RestController
@RequestMapping("/sales-checkin/admin/api/v1/submissions")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class TemporaryCheckinStatisticsController {
    private final TemporaryCheckinStatisticsService service;
    private final TemporaryCheckinAdminAccessPolicy access;

    TemporaryCheckinStatisticsController(TemporaryCheckinStatisticsService service, TemporaryCheckinAdminAccessPolicy access) {
        this.service = service; this.access = access;
    }

    @GetMapping("/attendance-summary")
    ResponseEntity<TemporaryCheckinStatisticsService.AttendancePage> summary(HttpServletRequest request,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "visitType", required = false) String visitType,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "locationStatus", required = false) String locationStatus,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "mediaStatus", required = false) String mediaStatus,
            @RequestParam(name = "summaryPage", required = false) Integer page,
            @RequestParam(name = "summarySize", required = false) Integer size) {
        var scope = access.requireScope(request);
        var options = new TemporaryCheckinRepository.AdminReadOptions(locationStatus, reviewStatus, mediaStatus, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(scope, from, to,
                city, salespersonId, status, visitType, query, options, page, size));
    }
}

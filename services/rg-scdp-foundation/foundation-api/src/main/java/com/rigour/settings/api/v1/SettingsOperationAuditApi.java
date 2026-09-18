package com.rigour.settings.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** 系统设置基础日志；按所属服务读取当前租户数据。 */
public interface SettingsOperationAuditApi {
    record Audit(
            String id,
            String actor,
            String action,
            String targetId,
            String result,
            String summary,
            Instant occurredAt) {}

    record Page(List<Audit> items, long total, int page, int pageSize) {}

    @GetMapping("/api/v1/business-settings/operation-audits")
    ApiResponse<Page> audits(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize);
}

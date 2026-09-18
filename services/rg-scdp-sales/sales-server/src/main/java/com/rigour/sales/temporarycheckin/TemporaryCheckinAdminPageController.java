package com.rigour.sales.temporarycheckin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 管理页静态入口保持可访问，页面数据和媒体由应用会话过滤器保护。 */
@Controller
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinAdminPageController {

    @GetMapping("/sales-checkin/admin/")
    public String page() {
        return "forward:/sales-checkin/admin/index.html";
    }
}

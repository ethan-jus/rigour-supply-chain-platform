package com.rigour.sales.temporarycheckin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 受 Nginx Basic Auth 保护的管理页入口；数据接口仍单独校验 Nginx 注入的账号范围。 */
@Controller
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinAdminPageController {

    @GetMapping("/sales-checkin/admin/")
    public String page() {
        return "forward:/sales-checkin/admin/index.html";
    }
}

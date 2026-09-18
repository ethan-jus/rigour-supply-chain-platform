package com.rigour.sales.temporarycheckin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 临时打卡页面入口，显式转发到独立静态目录，避免依赖容器的目录索引行为。 */
@Controller
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinPageController {

    @GetMapping({"/sales-checkin", "/sales-checkin/"})
    public String index() {
        return "forward:/sales-checkin/index.html";
    }
}

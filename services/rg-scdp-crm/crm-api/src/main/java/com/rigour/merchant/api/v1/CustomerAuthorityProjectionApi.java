package com.rigour.merchant.api.v1;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** BI 权威归属投影契约，仅允许具有专用权限的受信租户服务读取。 */
@RequestMapping("/api/v1/crm/analytics-authority")
public interface CustomerAuthorityProjectionApi {
    @GetMapping("/version")
    Version version();

    @GetMapping
    Page page(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "500") int step);

    record Version(String version) {}

    record Page(
            String version, List<Map<String, Object>> items, List<Map<String, Object>> regions) {}
}

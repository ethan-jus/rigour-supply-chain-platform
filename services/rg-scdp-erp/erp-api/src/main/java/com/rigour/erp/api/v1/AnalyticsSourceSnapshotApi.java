package com.rigour.erp.api.v1;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** BI 源投影契约：固定数据集、租户隔离、稳定游标，金额和大整数以精确文本传递。 */
@RequestMapping("/api/v1/erp/analytics-source")
public interface AnalyticsSourceSnapshotApi {
    @GetMapping("/{dataset}/version")
    Version version(@PathVariable String dataset);

    @GetMapping("/{dataset}")
    Page page(@PathVariable String dataset, @RequestParam(defaultValue = "") String after);

    record Version(String version) {}

    record Page(String version, List<Map<String, String>> items) {}
}

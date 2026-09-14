package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 基于文件批次或只读在线采集创建复核证据，不执行领域导入；后续查询只读 BI 本地快照。 */
public interface AnalyticsReconciliationReviewApi {
    String PATH = "/api/v1/analytics/reconciliation-reviews";

    @PostMapping(PATH)
    ApiResponse<BiReconciliationReview.Page> capture(@RequestBody BiReconciliationReview.Command command);

    @GetMapping(PATH)
    ApiResponse<List<BiReconciliationReview.History>> history();

    @GetMapping(PATH + "/{id}")
    ApiResponse<BiReconciliationReview.Page> page(@PathVariable String id,
            @RequestParam(defaultValue = "ORDER") String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String sales,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize);
}

package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsReconciliationReviewApi;
import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.application.service.BiReconciliationReviewService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 对账中心薄控制器；授权、采集与比较均在用例层。 */
@RestController
public final class AnalyticsReconciliationReviewController implements AnalyticsReconciliationReviewApi {
    private final BiReconciliationReviewService service;
    public AnalyticsReconciliationReviewController(BiReconciliationReviewService service) { this.service=service; }
    @Override public ApiResponse<BiReconciliationReview.Page> capture(BiReconciliationReview.Command command) {
        return ApiResponse.success(service.capture(command));
    }
    @Override public ApiResponse<List<BiReconciliationReview.History>> history() {
        return ApiResponse.success(service.history());
    }
    @Override public ApiResponse<BiReconciliationReview.Page> page(String id,String kind,String status,String city,
            String sales,String orderNo,String keyword,int page,int pageSize) {
        return ApiResponse.success(service.get(id,kind,status,city,sales,orderNo,keyword,page,pageSize));
    }
}

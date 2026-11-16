package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesFieldworkReviewApi;
import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.*;
import com.rigour.sales.application.service.SalesFieldworkReviewService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalesFieldworkReviewController implements SalesFieldworkReviewApi {
    private final SalesFieldworkReviewService service;
    public SalesFieldworkReviewController(SalesFieldworkReviewService service){this.service=service;}
    @Override public ApiResponse<Case> start(UUID profile,UUID day,com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview command){return ApiResponse.success(service.start(profile,day,command));}
    @Override public ApiResponse<List<Case>> own(LocalDate date){return ApiResponse.success(service.own(date));}
    @Override public ApiResponse<List<Case>> day(UUID profile,UUID day){return ApiResponse.success(service.managementDay(profile,day));}
    @Override public ApiResponse<List<Case>> visit(UUID visit){return ApiResponse.success(service.managementVisit(visit));}
    @Override public ApiResponse<Case> statement(UUID id,Statement command){return ApiResponse.success(service.statement(id,command));}
    @Override public ApiResponse<Case> review(UUID id,Review command){return ApiResponse.success(service.review(id,command));}
}

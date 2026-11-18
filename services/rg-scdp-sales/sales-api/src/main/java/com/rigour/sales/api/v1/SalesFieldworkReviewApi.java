package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.*;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

public interface SalesFieldworkReviewApi {
    @PostMapping("/api/v1/sales/me/work-days/{day}/adjustments")
    ApiResponse<Case> adjustment(@PathVariable("day") UUID day, @RequestBody Statement command);
    @PostMapping("/api/v1/sales/management/profiles/{profile}/work-days/{day}/cases")
    ApiResponse<Case> start(@PathVariable("profile") UUID profile,@PathVariable("day") UUID day,
            @RequestBody com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.StartReview command);
    @GetMapping("/api/v1/sales/me/fieldwork/cases") ApiResponse<List<Case>> own(@RequestParam("date") LocalDate date);
    @GetMapping("/api/v1/sales/management/profiles/{profile}/work-days/{day}/cases") ApiResponse<List<Case>> day(@PathVariable("profile") UUID profile,@PathVariable("day") UUID day);
    @GetMapping("/api/v1/sales/management/visits/{visit}/cases") ApiResponse<List<Case>> visit(@PathVariable("visit") UUID visit);
    @PostMapping("/api/v1/sales/me/fieldwork/cases/{id}/statements") ApiResponse<Case> statement(@PathVariable("id") UUID id,@RequestBody Statement command);
    @PostMapping("/api/v1/sales/management/fieldwork/cases/{id}/reviews") ApiResponse<Case> review(@PathVariable("id") UUID id,@RequestBody Review command);
}

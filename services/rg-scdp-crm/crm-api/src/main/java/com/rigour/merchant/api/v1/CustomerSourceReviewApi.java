package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.CustomerSourceReviewModels.*;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/v1/crm/dhb/customer-reviews")
public interface CustomerSourceReviewApi {
    @GetMapping
    List<Review> reviews(@RequestParam(required = false) UUID connectorId);

    @PostMapping
    void decide(@RequestBody Decision command);
}

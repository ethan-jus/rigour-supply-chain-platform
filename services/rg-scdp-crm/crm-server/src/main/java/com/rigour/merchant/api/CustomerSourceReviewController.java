package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CustomerSourceReviewApi;
import com.rigour.merchant.api.v1.model.CustomerSourceReviewModels.*;
import com.rigour.merchant.application.service.CrmMasterDataSyncService;
import com.rigour.shared.context.AuthorizationContext;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CustomerSourceReviewController implements CustomerSourceReviewApi {
    private final CrmMasterDataSyncService service;

    public CustomerSourceReviewController(CrmMasterDataSyncService service) {
        this.service = service;
    }

    @Override
    public List<Review> reviews(UUID connectorId) {
        return service.customerReviews(AuthorizationContext.requireCurrent(), connectorId);
    }

    @Override
    public void decide(Decision command) {
        service.decideCustomer(AuthorizationContext.requireCurrent(), command);
    }
}

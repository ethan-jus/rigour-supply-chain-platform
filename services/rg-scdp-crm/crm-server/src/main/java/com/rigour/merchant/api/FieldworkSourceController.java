package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.FieldworkSources.*;
import com.rigour.merchant.application.port.out.StoreReferenceClient;
import com.rigour.merchant.infrastructure.persistence.repository.FieldworkSourceQuery;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.exception.RequestValidationException;

import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class FieldworkSourceController {
    private final FieldworkSourceQuery source;

    private final StoreReferenceClient references;

    public FieldworkSourceController(FieldworkSourceQuery source, StoreReferenceClient references) {
        this.references = references;
        this.source = source;
    }

    @PostMapping("/internal/v1/crm/fieldwork/sources")
    public ApiResponse<Result> sources(@RequestBody Query query) {
        var tenant = requireSalesTenant();
        if (query == null
                || query.date() == null
                || query.asOf() == null
                || query.employees() == null
                || query.visits() == null
                || query.employees().size() > 200
                || query.visits().size() > 200) throw new RequestValidationException("外勤数据范围无效");
        return ApiResponse.success(source.find(tenant.toString(), query));
    }

    @PostMapping("/internal/v1/crm/fieldwork/store-regions")
    public ApiResponse<Map<Long, String>> storeRegions(@RequestBody List<Long> stores) {
        var tenant = requireSalesTenant();
        if (stores == null || stores.size() > 200 || stores.stream().anyMatch(id -> id == null || id <= 0))
            throw new RequestValidationException("门店范围无效");
        if (stores.isEmpty()) return ApiResponse.success(Map.of());
        return ApiResponse.success(source.storeRegions(tenant.toString(), stores, references.divisions()));
    }

    private UUID requireSalesTenant() {
        var caller = AuthorizationContext.requireCurrent();
        String action = "crm:fieldwork:source-read";
        if (!"SERVICE".equals(caller.principalScope())
                || caller.tenantId() == null
                || !UUID.nameUUIDFromBytes(
                                "service:rigour-sales-work-service"
                                        .getBytes(StandardCharsets.UTF_8))
                        .equals(caller.principalId()))
            throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        return caller.tenantId();
    }
}

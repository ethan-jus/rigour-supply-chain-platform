package com.rigour.order.api;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.service.cost.OrderLogisticsLedgerService;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public final class OrderLogisticsLedgerReferenceController {
    private final OrderLogisticsLedgerService service;
    public OrderLogisticsLedgerReferenceController(OrderLogisticsLedgerService service) { this.service = service; }
    private static String action() { String action = "order:logistics-cost:read"; var a = AuthorizationContext.requireCurrent(); if (a.tenantId() == null) throw new AuthorizationDeniedException(action); AuthorizationContext.requirePermission(action); return action; }
    @GetMapping("/api/v1/orders/logistics-costs/ledger/parties")
    public ApiResponse<List<LogisticsLedgerPartyView>> parties(@RequestParam String kind,
            @RequestParam(required=false) String code, @RequestParam(required=false) Long id,
            @RequestParam(defaultValue="") String keyword, @RequestParam(required=false) String cityCode,
            @RequestParam(defaultValue="0") int begin) {
        return ApiResponse.success(service.parties(kind, code, id, keyword, cityCode, begin, action()));
    }
    @GetMapping("/api/v1/orders/logistics-costs/ledger/sources")
    public ApiResponse<List<LogisticsCostSourceView>> sources(@ModelAttribute LogisticsLedgerSourceQuery query) {
        return ApiResponse.success(service.sources(query, action()));
    }
}

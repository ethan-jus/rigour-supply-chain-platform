package com.rigour.erp.api;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.service.cost.CostLedgerReferenceService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public final class CostLedgerReferenceController {
    private final CostLedgerReferenceService service;
    public CostLedgerReferenceController(CostLedgerReferenceService service) { this.service = service; }
    @GetMapping("/api/v1/erp/cost-ledger/parties")
    public ApiResponse<List<CostLedgerPartyView>> parties(@RequestParam String kind,
            @RequestParam(required=false) String code, @RequestParam(required=false) Long id,
            @RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="0") int begin,
            @RequestParam String action) {
        return ApiResponse.success(service.parties(kind, code, id, keyword, begin, action));
    }
    @GetMapping("/api/v1/erp/cost-ledger/sources")
    public ApiResponse<List<ErpCostSourceView>> sources(@ModelAttribute CostLedgerSourceQuery query, @RequestParam String action) {
        return ApiResponse.success(service.sources(query, action));
    }
}

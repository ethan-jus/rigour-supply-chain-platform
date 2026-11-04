package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.CostLedgerReferenceStore;
import com.rigour.shared.context.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class CostLedgerReferenceService {
    private final CostLedgerReferenceStore store;
    public CostLedgerReferenceService(CostLedgerReferenceStore store) { this.store = store; }
    private String actor(String action) {
        if (action == null || !Set.of("order:logistics-cost:read", "order:logistics-cost:write", "order:logistics-cost:confirm", "order:logistics-cost:void", "order:logistics-cost:export", "erp:cost:rental:read", "erp:cost:rental:write", "erp:cost:rental:confirm", "erp:cost:rental:void", "erp:cost:rental:export").contains(action)) throw new AuthorizationDeniedException("cost-ledger-reference");
        var actor = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission(action);
        if (actor.tenantId() == null) throw new AuthorizationDeniedException(action);
        return actor.tenantId().toString();
    }
    public List<CostLedgerPartyView> parties(String kind, String code, Long id, String keyword, int begin, String action) {
        String tenant = actor(action);
        if (begin < 0 || begin > 100000 || code != null && code.length() > 64 || keyword != null && keyword.length() > 200) throw ErpCostService.bad("档案查询参数无效");
        return store.parties(tenant, kind, code, id, keyword, begin, action);
    }
    public CostLedgerPartyView warehouse(String code, String action) {
        return parties("WAREHOUSE", code, null, null, 0, action).stream().filter(p -> Objects.equals(code, p.code())).findFirst().orElseThrow(() -> ErpCostService.bad("仓库不存在、停用或无权限"));
    }
    public List<ErpCostSourceView> sources(CostLedgerSourceQuery f, String action) {
        String tenant = actor(action);
        if (!action.startsWith("order:logistics-cost:") || f == null || f.historical() && (!f.exact() || f.documentNo()==null || f.documentNo().isBlank()) || f.begin() < 0 || f.begin() > 100000 || f.documentNo() != null && f.documentNo().length() > 100 || f.partyName() != null && f.partyName().length() > 200 || f.dateFrom() != null && f.dateTo() != null && f.dateTo().isBefore(f.dateFrom())) throw ErpCostService.bad("单据筛选条件无效");
        return store.sources(tenant, f, action);
    }
}

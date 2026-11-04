package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ErpProductDictionary;
import com.rigour.erp.domain.cost.WarehouseLedgerRules;
import com.rigour.shared.context.AuthorizationContext;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
public final class WarehouseLedgerService {
    private final CostLedgerReferenceService references;
    private final ErpProductDictionary dictionary;
    public WarehouseLedgerService(CostLedgerReferenceService references, ErpProductDictionary dictionary) { this.references = references; this.dictionary = dictionary; }
    public ErpCostCommand normalize(ErpCostCommand c, String action, boolean confirm) {
        if (c.paymentStatusCode() == null || c.paymentStatusCode().isBlank()) throw ErpCostService.bad("请选择付款状态");
        WarehouseCostLedger p;
        try { p = WarehouseLedgerRules.normalize(c.warehouseLedger(), confirm); } catch (IllegalArgumentException e) { throw ErpCostService.bad(e.getMessage()); }
        var warehouse = references.warehouse(p.warehouseCode(), action);
        if (warehouse.cityCode() == null || warehouse.cityCode().isBlank())
            throw ErpCostService.bad("仓库档案未配置归属城市，请维护仓库档案后重试");
        if (!Objects.equals(warehouse.cityCode(), c.cityCode())) throw ErpCostService.bad("费用归属城市与仓库所在城市不一致");
        if ("PAID".equals(c.paymentStatusCode()) && p.paymentDate() == null) throw ErpCostService.bad("请填写付款日期");
        String tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        for (var f : p.feeItems()) {
            if (!dictionary.validCodes(tenant, "COST_WAREHOUSE_FEE_TYPE").contains(f.feeTypeCode())) throw ErpCostService.bad("请选择启用的仓储费用项目");
            if (f.unitCode() != null && !f.unitCode().isBlank() && !dictionary.validCodes(tenant, "PRODUCT_UNIT").contains(f.unitCode())) throw ErpCostService.bad("请选择有效计费单位");
        }
        if (p.serviceProviderCode() != null && !p.serviceProviderCode().isBlank()
                && references.parties("SUPPLIER", p.serviceProviderCode(), null, null, 0, action).stream().noneMatch(ref -> Objects.equals(ref.code(), c.warehouseLedger().serviceProviderCode()))) throw ErpCostService.bad("收费方供应商不存在、停用或无权限");
        return new ErpCostCommand(c.requestId(), c.revision(), "仓储费用-" + c.costDate(), "WAREHOUSE",
                c.cityCode(), c.cityName(), c.departmentId(), c.ownerEmployeeCode(), c.costDate(),
                WarehouseLedgerRules.total(p), c.paymentStatusCode(), null, c.remark(), c.reason(),
                null, null, c.ownerName(), p.serviceProviderCode() == null || p.serviceProviderCode().isBlank() ? c.payeeName() : null, null, c.externalDocuments(), p);
    }
    public CostLedgerPartyView warehouse(String code, String action) { return references.warehouse(code, action); }
}

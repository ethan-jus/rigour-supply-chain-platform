package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.InternalStockBalanceView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockBalanceStore;
import com.rigour.erp.application.port.out.ErpStockBalanceStore.StockBalanceSearchCriteria;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** ERP 库存余额查询用例；库存数量只读，所有变动都必须有库存流水追溯。 */
@Service
public final class ErpStockBalanceService {
    private static final Logger log = LoggerFactory.getLogger(ErpStockBalanceService.class);
    private static final String READ_PERMISSION = "erp:supply:read";

    private final ErpStockBalanceStore store;

    public ErpStockBalanceService(ErpStockBalanceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public MasterDataPageView<InternalStockBalanceView> stockBalances(
            int begin, int step, String productCode, String productName,
            Long warehouseId, String warehouseName) {
        String tenantId = tenant();
        StockBalanceSearchCriteria criteria = new StockBalanceSearchCriteria(
                ErpServiceValidation.text(productCode, 50, "productCode"),
                ErpServiceValidation.text(productName, 200, "productName"),
                ErpServiceValidation.optionalId(warehouseId, "warehouseId"),
                ErpServiceValidation.text(warehouseName, 120, "warehouseName"));
        MasterDataPageView<InternalStockBalanceView> result = store.stockBalances(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP库存余额查询完成 tenantId={} productCode={} productName={} warehouseId={} warehouseName={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.productCode()),
                ErpServiceValidation.value(criteria.productName()), criteria.warehouseId(),
                ErpServiceValidation.value(criteria.warehouseName()), result.items().size(), result.total());
        return result;
    }

    private static String tenant() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(READ_PERMISSION);
        return caller.tenantId().toString();
    }
}

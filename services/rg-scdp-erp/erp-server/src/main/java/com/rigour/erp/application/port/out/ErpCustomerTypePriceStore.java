package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.CustomerTypePriceImportItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import java.util.List;

/** ERP 客户类型等级价持久化端口；只操作 `erp_customer_type_price`。 */
public interface ErpCustomerTypePriceStore {
    List<CustomerTypePriceView> pricesByProductIds(String tenantId, List<Long> productIds);

    List<CustomerTypePriceView> syncVariantPrices(
            String tenantId, Long productVariantId, List<CustomerTypePriceItem> items, String actorId);

    CustomerTypePriceImportResult importPrices(
            String tenantId, List<CustomerTypePriceImportItem> items, String actorId);
}

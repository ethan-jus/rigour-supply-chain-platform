package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 自研商品品牌持久化端口；只操作 `erp_product_brand`。 */
public interface ErpProductBrandStore {
    MasterDataPageView<InternalProductBrandView> brands(
            String tenantId, int begin, int step, BrandSearchCriteria criteria);

    Optional<InternalProductBrandView> brand(String tenantId, Long id);

    boolean existsByCode(String tenantId, String brandCode);

    InternalProductBrandView create(String tenantId, String brandCode,
                                    InternalProductBrandCommand command, String actorId);

    InternalProductBrandView update(String tenantId, Long id,
                                    InternalProductBrandCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 品牌列表独立筛选条件。 */
    record BrandSearchCriteria(String brandCode, String brandName) {
    }
}

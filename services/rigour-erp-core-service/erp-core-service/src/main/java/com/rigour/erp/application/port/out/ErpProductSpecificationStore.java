package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 自研商品规格持久化端口；只操作 `erp_product_specification` 及其规格值。 */
public interface ErpProductSpecificationStore {
    MasterDataPageView<InternalProductSpecificationView> specifications(
            String tenantId, int begin, int step, SpecificationSearchCriteria criteria);

    Optional<InternalProductSpecificationView> specification(String tenantId, Long id);

    boolean existsByCode(String tenantId, String specificationCode, Long excludeId);

    InternalProductSpecificationView create(String tenantId, InternalProductSpecificationCommand command,
                                            String actorId);

    InternalProductSpecificationView update(String tenantId, Long id,
                                            InternalProductSpecificationCommand command,
                                            String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 商品规格列表独立筛选条件；不使用 keyword 汇总多个业务字段。 */
    record SpecificationSearchCriteria(String specificationCode, String specificationName, String statusCode) {
    }
}

package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 自研商品分类持久化端口；只操作 `erp_product_category`。 */
public interface ErpProductCategoryStore {
    MasterDataPageView<InternalProductCategoryView> categories(
            String tenantId, int begin, int step, CategorySearchCriteria criteria);

    Optional<InternalProductCategoryView> category(String tenantId, Long id);

    boolean existsByCode(String tenantId, String categoryCode);

    boolean hasChildren(String tenantId, Long id);

    boolean hasAncestor(String tenantId, Long categoryId, Long ancestorId);

    InternalProductCategoryView create(String tenantId, String categoryCode,
                                       InternalProductCategoryCommand command,
                                       int categoryLevel, String actorId);

    InternalProductCategoryView update(String tenantId, Long id,
                                       InternalProductCategoryCommand command,
                                       int categoryLevel, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 分类列表独立筛选条件；不使用 keyword 汇总多个业务字段。 */
    record CategorySearchCriteria(String categoryCode, String categoryName, Long parentId) {
    }
}

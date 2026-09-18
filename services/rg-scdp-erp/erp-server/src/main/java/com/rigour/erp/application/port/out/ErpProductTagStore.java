package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 自研商品标签持久化端口；只操作 `erp_product_tag`。 */
public interface ErpProductTagStore {
    MasterDataPageView<InternalProductTagView> tags(
            String tenantId, int begin, int step, TagSearchCriteria criteria);

    Optional<InternalProductTagView> tag(String tenantId, Long id);

    boolean existsByCode(String tenantId, String tagCode);

    InternalProductTagView create(String tenantId, String tagCode,
                                  InternalProductTagCommand command, String actorId);

    InternalProductTagView update(String tenantId, Long id,
                                  InternalProductTagCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 标签列表独立筛选条件。 */
    record TagSearchCriteria(String tagCode, String tagName, String tagTypeCode) {
    }
}

package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductTagApi;
import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.product.ErpProductTagService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品标签 HTTP 边界；只承载标签维护。 */
@RestController
public final class ErpProductTagController implements ErpProductTagApi {
    private final ErpProductTagService tagService;

    public ErpProductTagController(ErpProductTagService tagService) {
        this.tagService = tagService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalProductTagView>> tags(
            int begin, int step, String tagCode, String tagName, String tagTypeCode) {
        return ApiResponse.success(tagService.tags(begin, step, tagCode, tagName, tagTypeCode));
    }

    @Override
    public ApiResponse<InternalProductTagView> tag(Long id) {
        return ApiResponse.success(tagService.tag(id));
    }

    @Override
    public ApiResponse<InternalProductTagView> createTag(InternalProductTagCommand command) {
        return ApiResponse.success(tagService.create(command));
    }

    @Override
    public ApiResponse<InternalProductTagView> updateTag(Long id, InternalProductTagCommand command) {
        return ApiResponse.success(tagService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteTag(Long id, int revision) {
        tagService.delete(id, revision);
        return ApiResponse.success(null);
    }
}

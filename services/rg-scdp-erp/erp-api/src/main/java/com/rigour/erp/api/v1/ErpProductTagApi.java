package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 商品标签维护接口；标签用于商品展示、营销推荐和前台筛选。 */
public interface ErpProductTagApi {
    String BASE_PATH = "/api/v1/erp/product-tags";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalProductTagView>> tags(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String tagCode,
            @RequestParam(required = false) String tagName,
            @RequestParam(required = false) String tagTypeCode);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductTagView> tag(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalProductTagView> createTag(@RequestBody InternalProductTagCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductTagView> updateTag(
            @PathVariable("id") Long id,
            @RequestBody InternalProductTagCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteTag(@PathVariable("id") Long id, @RequestParam int revision);
}

package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.BrandView;
import com.rigour.erp.api.v1.model.CategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductPageView;
import com.rigour.erp.api.v1.model.SkuPageView;
import com.rigour.erp.api.v1.model.SpecificationView;
import com.rigour.erp.api.v1.model.TagView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 商品/SPU V1 契约；查询只读取 ERP 本地商品模型，订货宝同步由 ERP 编排。 */
public interface ErpProductApi {

    String BASE_PATH = "/api/v1/erp";
    String PRODUCTS_PATH = BASE_PATH + "/products";
    String SKUS_PATH = BASE_PATH + "/skus";
    String CATEGORIES_PATH = BASE_PATH + "/categories";
    String BRANDS_PATH = BASE_PATH + "/brands";
    String SPECIFICATIONS_PATH = BASE_PATH + "/specifications";
    String TAGS_PATH = BASE_PATH + "/tags";

    /** 分页查询 ERP 本地 SPU 档案，不会在查询链路访问订货宝。 */
    @GetMapping(PRODUCTS_PATH)
    ApiResponse<ProductPageView> list(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String internalStatus,
            @RequestParam(required = false) String sourcePutaway);

    /** SKU 来自 getGoodsList.multi，本接口只查询 ERP 已落库的可销售规格组合。 */
    @GetMapping(SKUS_PATH)
    ApiResponse<SkuPageView> skus(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String internalStatus,
            @RequestParam(required = false) String sourcePutaway);

    /** 分页查询 ERP 本地商品分类。 */
    @GetMapping(CATEGORIES_PATH)
    ApiResponse<MasterDataPageView<CategoryView>> categories(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status);

    /** 分页查询 ERP 本地商品品牌。 */
    @GetMapping(BRANDS_PATH)
    ApiResponse<MasterDataPageView<BrandView>> brands(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status);

    /** 分页查询 ERP 本地商品规格及规格值。 */
    @GetMapping(SPECIFICATIONS_PATH)
    ApiResponse<MasterDataPageView<SpecificationView>> specifications(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status);

    /** 分页查询 ERP 本地商品标签。 */
    @GetMapping(TAGS_PATH)
    ApiResponse<MasterDataPageView<TagView>> tags(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status);

    /** 保留 API 模型对时间字段的直接引用，避免调用方把更新时间解析成来源字符串。 */
    interface TimeFields {
        Instant syncedAt();
    }
}

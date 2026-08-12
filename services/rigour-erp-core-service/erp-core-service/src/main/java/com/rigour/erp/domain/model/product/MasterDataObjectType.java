package com.rigour.erp.domain.model.product;

/** ERP 商品主数据对象类型；值同时用于同步批次和 Integration 目标发现。 */
public enum MasterDataObjectType {
    /** 商品/SPU；同步时同时导入 getGoodsList.multi 中的 SKU。 */
    PRODUCT_SPU,
    /** 商品分类。 */
    CATEGORY,
    /** 商品品牌。 */
    BRAND,
    /** 规格维度及规格值字典。 */
    SPECIFICATION,
    /** 商品标签。 */
    TAG
}

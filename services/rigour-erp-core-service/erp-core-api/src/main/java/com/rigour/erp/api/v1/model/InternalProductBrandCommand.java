package com.rigour.erp.api.v1.model;

/** ERP 自研商品品牌保存命令；品牌编码由后端统一生成。 */
public record InternalProductBrandCommand(
        String brandName,
        String remark,
        Integer revision) {
}

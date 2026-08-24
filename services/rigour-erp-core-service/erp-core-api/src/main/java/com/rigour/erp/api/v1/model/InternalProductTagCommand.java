package com.rigour.erp.api.v1.model;

/** ERP 自研商品标签保存命令；标签类型来自 PRODUCT_TAG_TYPE 字典。 */
public record InternalProductTagCommand(
        String tagName,
        String tagTypeCode,
        String remark,
        Integer revision) {
}

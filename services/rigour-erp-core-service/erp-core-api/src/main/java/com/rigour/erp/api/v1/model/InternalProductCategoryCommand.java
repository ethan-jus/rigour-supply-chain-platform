package com.rigour.erp.api.v1.model;

/**
 * ERP 自研商品分类保存命令。
 *
 * <p>分类编码由后端生成。修改和删除通过 revision 做乐观锁保护。</p>
 */
public record InternalProductCategoryCommand(
        Long parentId,
        String categoryName,
        Integer ordinal,
        String remark,
        Integer revision) {
}

package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 商品分类列表投影。 */
public record CategoryView(
        /** ERP 分类 UUID。 */
        String id,
        /** 订货宝分类来源 ID。 */
        String sourceCategoryId,
        /** 订货宝外部引用编码。 */
        String externalReferenceId,
        /** ERP 租户内唯一的分类编码。 */
        String categoryCode,
        /** 分类名称。 */
        String name,
        /** ERP 父分类 UUID；订货宝接口未返回父级时为空。 */
        String parentId,
        /** ERP 分类层级，根级为 1。 */
        int categoryLevel,
        /** 订货宝父分类来源 ID。 */
        String sourceParentId,
        /** 订货宝分类编码。 */
        String sourceCategoryNumber,
        /** 是否为订货宝默认分类。 */
        Boolean sourceDefaultFlag,
        /** ERP 分类启用状态。 */
        String status,
        /** 分类数据主权状态。 */
        String ownershipState,
        /** 最近一次成功处理该来源分类的时间。 */
        Instant syncedAt) {
}

package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 商品品牌列表投影。 */
public record BrandView(
        /** ERP 品牌 UUID。 */
        String id,
        /** 订货宝品牌来源 ID。 */
        String sourceBrandId,
        /** 订货宝外部引用编码。 */
        String externalReferenceId,
        /** ERP 租户内唯一的品牌编码。 */
        String brandCode,
        /** 品牌名称。 */
        String name,
        /** 订货宝品牌编码。 */
        String sourceBrandNumber,
        /** 订货宝品牌排序。 */
        Integer sourceSortOrder,
        /** 订货宝品牌说明。 */
        String sourceDescription,
        /** ERP 品牌启用状态。 */
        String status,
        /** 品牌数据主权状态。 */
        String ownershipState,
        /** 最近一次成功处理该来源品牌的时间。 */
        Instant syncedAt) {
}

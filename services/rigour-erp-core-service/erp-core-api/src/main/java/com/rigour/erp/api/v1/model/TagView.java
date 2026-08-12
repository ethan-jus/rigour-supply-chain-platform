package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 商品标签列表投影。 */
public record TagView(
        /** ERP 标签 UUID。 */
        String id,
        /** 订货宝标签来源 ID。 */
        String sourceTagId,
        /** ERP 租户内唯一的标签编码。 */
        String tagCode,
        /** 标签名称。 */
        String name,
        /** 订货宝标签分组来源 ID。 */
        String sourceGroupId,
        /** 订货宝标签分组名称。 */
        String sourceGroupName,
        /** 订货宝标签排序。 */
        Integer sourceSortOrder,
        /** 订货宝标签关联数量快照。 */
        Integer sourceRelationCount,
        /** 订货宝标签创建时间。 */
        Instant sourceCreatedAt,
        /** 订货宝标签更新时间。 */
        Instant sourceUpdatedAt,
        /** 标签展示颜色；来源未提供时为空。 */
        String color,
        /** ERP 标签启用状态。 */
        String status,
        /** 标签数据主权状态。 */
        String ownershipState,
        /** 最近一次成功处理该来源标签的时间。 */
        Instant syncedAt) {
}

package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 商品规格字典列表投影。 */
public record SpecificationView(
        /** ERP 规格 UUID。 */
        String id,
        /** 订货宝规格来源 ID。 */
        String sourceSpecificationId,
        /** ERP 租户内唯一的规格编码。 */
        String specificationCode,
        /** 规格名称，例如颜色或尺寸。 */
        String name,
        /** 订货宝父级规格 ID。 */
        String sourceParentId,
        /** 该规格下已落库的规格值数量。 */
        int valueCount,
        /** 该规格下的来源规格值。 */
        java.util.List<SpecificationValueView> values,
        /** ERP 规格启用状态。 */
        String status,
        /** 规格数据主权状态。 */
        String ownershipState,
        /** 最近一次成功处理该来源规格的时间。 */
        Instant syncedAt) {
    public SpecificationView {
        values = values == null ? java.util.List.of() : java.util.List.copyOf(values);
    }
}

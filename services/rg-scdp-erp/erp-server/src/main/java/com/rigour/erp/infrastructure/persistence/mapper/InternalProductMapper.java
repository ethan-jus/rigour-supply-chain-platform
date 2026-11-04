package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;

/** ERP 自研商品 Mapper。 */
public interface InternalProductMapper extends BaseMapper<InternalProductEntity> {
    @org.apache.ibatis.annotations.Select(
            "SELECT (EXISTS(SELECT 1 FROM erp_procurement_order_line l WHERE l.tenant_id=#{tenant}"
                + " AND (l.product_code=#{code} OR l.product_id=#{id}))) OR (EXISTS(SELECT 1 FROM"
                + " erp_stock_balance b WHERE b.tenant_id=#{tenant} AND b.product_id=#{id})) OR"
                + " (EXISTS(SELECT 1 FROM erp_stock_flow f WHERE f.tenant_id=#{tenant} AND"
                + " f.product_id=#{id}))")
    boolean unitDefinitionInUse(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("id") Long id,
            @org.apache.ibatis.annotations.Param("code") String code);
}

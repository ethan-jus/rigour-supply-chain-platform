package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** ERP 自研采购订单头 Mapper。 */
public interface InternalProcurementOrderMapper extends BaseMapper<InternalProcurementOrderEntity> {
    @Select("SELECT COALESCE(SUM(COALESCE(total_amount,0) + CASE WHEN freight_bearer_code='BUYER' "
            + "THEN COALESCE(freight_amount,0) ELSE 0 END),0) "
            + "FROM erp_procurement_order ${ew.customSqlSegment}")
    BigDecimal purchaseAmount(@Param(Constants.WRAPPER) Wrapper<InternalProcurementOrderEntity> query);

    // 历史主表与新增编码列的排序规则不同，编码关联需显式统一比较规则。
    @Select("SELECT l.unit_code AS unitCode, SUM(l.quantity) AS quantity "
            + "FROM erp_procurement_order_line l JOIN "
            + "(SELECT id,tenant_id,procurement_no FROM erp_procurement_order ${ew.customSqlSegment}) h "
            + "ON l.tenant_id COLLATE utf8mb4_general_ci=h.tenant_id "
            + "AND (l.procurement_no COLLATE utf8mb4_general_ci=h.procurement_no "
            + "OR (l.procurement_no IS NULL AND l.procurement_order_id=h.id)) "
            + "WHERE l.deleted=0 AND l.unit_code IS NOT NULL GROUP BY l.unit_code ORDER BY l.unit_code")
    List<UnitQuantity> purchaseQuantities(@Param(Constants.WRAPPER) Wrapper<InternalProcurementOrderEntity> query);

    record UnitQuantity(String unitCode, BigDecimal quantity) { }
}

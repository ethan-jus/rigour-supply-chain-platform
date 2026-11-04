package com.rigour.erp.infrastructure.persistence.mapper;

import com.rigour.erp.api.v1.model.InternalProcurementProductView;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/** 采购编码引用与入库聚合，始终限定本ERP库及同一租户。 */
@Mapper
public interface ProcurementReferenceMapper {
    String CATALOG_FROM =
            " FROM erp_product_variant v JOIN erp_product p ON p.tenant_id=v.tenant_id AND"
                + " p.id=v.product_id WHERE p.tenant_id=#{tenant} AND p.deleted=0 AND v.deleted=0"
                + " AND p.submit_status_code='SUBMITTED' <if test='productName != null'> AND"
                + " p.product_name LIKE CONCAT('%',#{productName},'%')</if> <if test='productCode"
                + " != null'> AND p.product_code LIKE CONCAT('%',#{productCode},'%')</if> <if"
                + " test='variantCode != null'> AND v.variant_code LIKE"
                + " CONCAT('%',#{variantCode},'%')</if>";

    @Select("<script>SELECT COUNT(*)" + CATALOG_FROM + "</script>")
    long productCount(
            @Param("tenant") String tenant,
            @Param("productName") String productName,
            @Param("productCode") String productCode,
            @Param("variantCode") String variantCode);

    @Select(
            "<script>SELECT p.product_code productCode,v.variant_code variantCode,p.product_name"
                + " productName, v.specification_snapshot"
                + " specification,COALESCE(v.unit_code,p.unit_code) defaultUnitCode, p.unit_code"
                + " baseUnitCode,p.middle_unit_code middleUnitCode,p.base_to_middle_rate"
                + " baseToMiddleRate, p.big_unit_code bigUnitCode,p.base_to_big_rate"
                + " baseToBigRate,v.purchase_price purchasePrice"
                    + CATALOG_FROM
                    + " ORDER BY p.id,v.id LIMIT #{begin},#{step}</script>")
    List<InternalProcurementProductView> products(
            @Param("tenant") String tenant,
            @Param("productName") String productName,
            @Param("productCode") String productCode,
            @Param("variantCode") String variantCode,
            @Param("begin") int begin,
            @Param("step") int step);

    @Select(
            "SELECT l.id lineId,COUNT(r.id) receiptCount, SUM(CASE WHEN r.unit_code COLLATE utf8mb4_general_ci=l.unit_code"
                + " THEN r.quantity*COALESCE(l.conversion_rate,1) WHEN l.conversion_rate>0 AND"
                + " r.unit_code COLLATE utf8mb4_general_ci=l.base_unit_code THEN r.quantity ELSE 0"
                + " END) receivedBaseQuantity, SUM(CASE WHEN r.unit_code COLLATE utf8mb4_general_ci=l.unit_code OR"
                + " (l.conversion_rate>0 AND r.unit_code COLLATE"
                + " utf8mb4_general_ci=l.base_unit_code) THEN 0 ELSE 1 END) invalidUnits FROM"
                + " erp_procurement_order_line l JOIN erp_stock_in_order_line r ON"
                + " r.tenant_id COLLATE utf8mb4_general_ci=l.tenant_id AND ((r.procurement_no=l.procurement_no AND"
                + " r.procurement_variant_code=l.variant_code) OR (r.procurement_no IS NULL AND"
                + " r.procurement_order_line_id=l.id)) JOIN erp_stock_in_order h ON"
                + " h.tenant_id=r.tenant_id AND h.id=r.stock_in_order_id AND"
                + " h.status_code='CONFIRMED' AND h.deleted=0 WHERE l.tenant_id=#{tenant} AND"
                + " l.procurement_no=#{procurementNo} AND l.deleted=0 AND r.deleted=0 GROUP BY"
                + " l.id")
    List<ReceiptTotal> receipts(
            @Param("tenant") String tenant, @Param("procurementNo") String procurementNo);

    record ReceiptTotal(
            Long lineId, long receiptCount, BigDecimal receivedBaseQuantity, long invalidUnits) {}
}

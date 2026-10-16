package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ErpCostReferenceMapper extends BaseMapper<InternalInventoryWarehouseEntity> {
    @Select(
            "SELECT p.id,p.product_code code,p.product_name name,p.id"
                    + " productId,p.product_specification specification,p.unit_code unit FROM"
                    + " erp_product p ${ew.customSqlSegment}")
    List<com.rigour.erp.api.v1.model.ErpCostReferenceView> products(
            @Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            "SELECT v.id,v.variant_code code,p.product_name name,v.product_id"
                    + " productId,v.specification_snapshot specification,v.unit_code unit FROM"
                    + " erp_product_variant v JOIN erp_product p ON p.tenant_id=v.tenant_id AND"
                    + " p.id=v.product_id AND p.deleted=0 ${ew.customSqlSegment}")
    List<com.rigour.erp.api.v1.model.ErpCostReferenceView> variants(
            @Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            "SELECT s.id,s.supplier_code code,s.supplier_name name,NULL productId,NULL"
                    + " specification,NULL unit FROM erp_supplier_profile s ${ew.customSqlSegment}")
    List<com.rigour.erp.api.v1.model.ErpCostReferenceView> suppliers(
            @Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            "SELECT d.id,d.procurement_no documentNo,'PURCHASE' sourceType,w.region_code"
                + " cityCode,NULL warehouseId,w.id targetWarehouseId,s.supplier_code"
                + " senderCode,s.supplier_name senderName,w.warehouse_code"
                + " receiverCode,w.warehouse_name receiverName,d.status_code statusCode,"
                + " COALESCE(NULLIF(d.source_system_code,''),'SYSTEM') sourceSystem,"
                + " d.source_document_no originalNo, NULL documentDate, s.address originAddress,"
                + " w.address destinationAddress, NULL warehouseTypeCode, w.warehouse_type_code"
                + " targetWarehouseTypeCode FROM erp_procurement_order d JOIN erp_supplier_profile"
                + " s ON s.tenant_id=d.tenant_id AND s.id=d.supplier_id AND s.deleted=0 JOIN"
                + " erp_inventory_warehouse w ON w.tenant_id=d.tenant_id AND"
                + " w.id=d.target_warehouse_id AND w.deleted=0 ${ew.customSqlSegment}")
    List<ErpCostSourceView> purchaseSources(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            "SELECT d.id,d.transfer_no documentNo,'TRANSFER' sourceType,w.region_code cityCode,s.id"
                + " warehouseId,w.id targetWarehouseId,s.warehouse_code senderCode,s.warehouse_name"
                + " senderName,w.warehouse_code receiverCode,w.warehouse_name"
                + " receiverName,d.status_code statusCode, 'SYSTEM' sourceSystem, d.transfer_no"
                + " originalNo, NULL documentDate, s.address originAddress, w.address"
                + " destinationAddress, s.warehouse_type_code warehouseTypeCode,"
                + " w.warehouse_type_code targetWarehouseTypeCode FROM erp_transfer_order d JOIN"
                + " erp_inventory_warehouse s ON s.tenant_id=d.tenant_id AND"
                + " s.id=d.source_warehouse_id AND s.deleted=0 JOIN erp_inventory_warehouse w ON"
                + " w.tenant_id=d.tenant_id AND w.id=d.target_warehouse_id AND w.deleted=0"
                + " ${ew.customSqlSegment}")
    List<ErpCostSourceView> transferSources(@Param(Constants.WRAPPER) Wrapper<?> query);
}

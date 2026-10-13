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
            "SELECT d.id,d.procurement_no documentNo,'PURCHASE' sourceType,w.region_code"
                + " cityCode,NULL warehouseId,w.id targetWarehouseId,s.supplier_code"
                + " senderCode,s.supplier_name senderName,w.warehouse_code"
                + " receiverCode,w.warehouse_name receiverName,d.status_code statusCode FROM"
                + " erp_procurement_order d JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id"
                + " AND s.id=d.supplier_id AND s.deleted=0 JOIN erp_inventory_warehouse w ON"
                + " w.tenant_id=d.tenant_id AND w.id=d.target_warehouse_id AND w.deleted=0"
                + " ${ew.customSqlSegment}")
    List<ErpCostSourceView> purchaseSources(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            "SELECT d.id,d.transfer_no documentNo,'TRANSFER' sourceType,w.region_code cityCode,s.id"
                + " warehouseId,w.id targetWarehouseId,s.warehouse_code senderCode,s.warehouse_name"
                + " senderName,w.warehouse_code receiverCode,w.warehouse_name"
                + " receiverName,d.status_code statusCode FROM erp_transfer_order d JOIN"
                + " erp_inventory_warehouse s ON s.tenant_id=d.tenant_id AND"
                + " s.id=d.source_warehouse_id AND s.deleted=0 JOIN erp_inventory_warehouse w ON"
                + " w.tenant_id=d.tenant_id AND w.id=d.target_warehouse_id AND w.deleted=0"
                + " ${ew.customSqlSegment}")
    List<ErpCostSourceView> transferSources(@Param(Constants.WRAPPER) Wrapper<?> query);
}

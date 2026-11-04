package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.erp.api.v1.model.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CostLedgerReferenceMapper {
    @Select("SELECT w.id,w.warehouse_code code,w.warehouse_name name,w.region_code cityCode,w.address,NULL contactName,NULL phone,'WAREHOUSE' partyTypeCode FROM erp_inventory_warehouse w ${ew.customSqlSegment}")
    List<CostLedgerPartyView> warehouses(@Param(Constants.WRAPPER) Wrapper<?> q);
    @Select("SELECT s.id,s.supplier_code code,s.supplier_name name,NULL cityCode,s.address,s.contact_name contactName,s.contact_phone phone,'SUPPLIER' partyTypeCode FROM erp_supplier_profile s ${ew.customSqlSegment}")
    List<CostLedgerPartyView> suppliers(@Param(Constants.WRAPPER) Wrapper<?> q);
    @Select("SELECT d.id,d.stock_out_no documentNo,'OTHER_OUT' sourceType,w.region_code cityCode,w.id warehouseId,NULL targetWarehouseId,w.warehouse_code senderCode,w.warehouse_name senderName,NULL receiverCode,d.customer_name_snapshot receiverName,d.status_code statusCode,COALESCE(d.source_system_code,'SYSTEM') sourceSystem,d.source_document_no originalNo,DATE(DATE_ADD(COALESCE(d.stock_out_time,d.created_time),INTERVAL 8 HOUR)) documentDate,w.address originAddress,NULL destinationAddress,w.warehouse_type_code warehouseTypeCode,NULL targetWarehouseTypeCode FROM erp_stock_out_order d JOIN erp_inventory_warehouse w ON w.tenant_id=d.tenant_id AND w.id=d.warehouse_id AND w.deleted=0 ${ew.customSqlSegment}")
    List<ErpCostSourceView> stockOuts(@Param(Constants.WRAPPER) Wrapper<?> q);
}

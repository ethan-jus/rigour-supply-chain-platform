package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.api.v1.model.CityProductSupplyView.Stock;
import com.rigour.analytics.api.v1.model.CityProductSupplyView.Operation;
import com.rigour.analytics.application.port.out.CityProductSupplyStore.Checkpoint;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 以ERP主键、仓库和单位查询本地事实；不使用名称或包装起订量匹配。 */
@Mapper
public interface CityProductSupplyMapper {
    @Select("""
            <script>
            SELECT TRIM(CAST(warehouse_id AS CHAR(64))) AS warehouseId, warehouse_name AS warehouseName,
                   region_code AS warehouseRegionCode, TRIM(CAST(product_id AS CHAR(64))) AS productId,
                   product_name AS productName, TRIM(CAST(product_variant_id AS CHAR(64))) AS skuId,
                   specification_snapshot AS specification, unit_code AS unitCode,
                   available_quantity AS availableQuantity, locked_quantity AS lockedQuantity,
                   in_transit_quantity AS inTransitQuantity, synced_time AS syncedAt
              FROM bi_inventory_balance_current
             WHERE tenant_id = #{tenantId} AND product_id = #{productId}
               AND warehouse_id = #{warehouseId} AND deleted = 0
             <if test="skuId != null">AND product_variant_id = #{skuId}</if>
             ORDER BY product_variant_id LIMIT #{limit}
            </script>
            """)
    List<Stock> stocks(@Param("tenantId") String tenantId, @Param("productId") Long productId,
            @Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT TRIM(CAST(product_id AS CHAR(64))) AS productId, TRIM(CAST(product_variant_id AS CHAR(64))) AS skuId,
                   unit_code AS unitCode,
                   CONCAT(EXTRACT(YEAR FROM (operation_time + INTERVAL '8' HOUR)), '-',
                          LPAD(EXTRACT(MONTH FROM (operation_time + INTERVAL '8' HOUR)), 2, '0')) AS `month`,
                   SUM(CASE WHEN operation_type = 'PROCUREMENT' THEN quantity ELSE 0 END) AS procurementQuantity,
                   SUM(CASE WHEN operation_type = 'SALES_SHIPMENT' THEN quantity ELSE 0 END) AS shippedQuantity,
                   MAX(synced_time) AS syncedAt
              FROM bi_inventory_operation_fact
             WHERE tenant_id = #{tenantId} AND product_id = #{productId} AND deleted = 0
               AND operation_time &gt;= #{from} AND operation_time &lt;= #{to}
             <if test="skuId != null">AND product_variant_id = #{skuId}</if>
             GROUP BY product_id, product_variant_id, unit_code, `month`
             ORDER BY `month`, product_variant_id, unit_code LIMIT #{limit}
            </script>
            """)
    List<Operation> operations(@Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("productId") Long productId, @Param("skuId") Long skuId, @Param("limit") int limit);

    @Select("""
            SELECT status_code AS status, last_success_time AS lastSuccessAt FROM bi_etl_checkpoint
             WHERE tenant_id = #{tenantId} AND source_code = #{sourceCode}
            """)
    Checkpoint checkpoint(@Param("tenantId") String tenantId, @Param("sourceCode") String sourceCode);
}

package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderProductRepairLineEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 修复行证据只追加，不删除历史。 */
@Mapper
public interface OrderProductRepairLineMapper extends BaseMapper<OrderProductRepairLineEntity> {
    /** 只在 Order 自有表内按当前版本、商品、数量及单位核验，不跨库关联。 */
    @Select("""
            <script>
            SELECT r.*, o.source_order_no FROM order_product_repair_line r
            JOIN order_sales_order o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
            JOIN order_sales_order_line l ON l.tenant_id = r.tenant_id AND l.order_id = r.order_id AND l.id = r.line_id
            WHERE r.tenant_id = #{tenant} AND o.deleted = 0 AND l.deleted = 0
              AND o.source_system_code = 'FEISHU' AND o.order_status_code != 'CANCELLED'
              AND o.total_quantity > 0 AND l.quantity > 0
              AND l.revision = r.line_revision AND l.product_id = r.product_id
              AND l.product_variant_id = r.product_variant_id AND l.quantity = r.transaction_quantity
              AND (l.unit_code = r.stored_unit_code OR (l.unit_code IS NULL AND r.stored_unit_code IS NULL))
              AND l.id &gt; #{afterLineId}
              <if test="owner != null">AND o.owner_sales_user_id = #{owner}</if>
            ORDER BY l.id LIMIT #{limit}
            </script>
            """)
    List<OrderProductRepairLineEntity> currentEvidence(@Param("tenant") String tenant, @Param("owner") String owner,
                                                       @Param("afterLineId") long afterLineId, @Param("limit") int limit);
}

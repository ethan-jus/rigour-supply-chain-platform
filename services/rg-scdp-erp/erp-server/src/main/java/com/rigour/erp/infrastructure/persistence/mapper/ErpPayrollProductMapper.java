package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ErpPayrollProductMapper extends BaseMapper<InternalProductVariantEntity> {
    @Select(
            """
SELECT p.id productId,v.id productVariantId,p.product_code productCode,v.variant_code variantCode,
p.product_name productName,v.specification_snapshot specification,v.unit_code unitCode,p.category_id categoryId,c.category_name categoryName
FROM erp_product p JOIN erp_product_variant v ON v.tenant_id=p.tenant_id AND v.product_id=p.id
LEFT JOIN erp_product_category c ON c.tenant_id=p.tenant_id AND c.id=p.category_id AND c.deleted=0
${ew.customSqlSegment}
""")
    List<ErpPayrollProductView> products(@Param(Constants.WRAPPER) Wrapper<?> query);
}

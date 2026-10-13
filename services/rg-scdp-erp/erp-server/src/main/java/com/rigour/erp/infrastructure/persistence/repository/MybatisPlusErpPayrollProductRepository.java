package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.erp.application.port.out.ErpPayrollProductStore;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.mapper.ErpPayrollProductMapper;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisPlusErpPayrollProductRepository implements ErpPayrollProductStore {
    private final ErpPayrollProductMapper mapper;

    public MybatisPlusErpPayrollProductRepository(ErpPayrollProductMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ErpPayrollProductView> products(String tenant, String keyword, Long variant) {
        var q =
                new QueryWrapper<InternalProductVariantEntity>()
                        .eq("p.tenant_id", tenant)
                        .eq("p.deleted", 0)
                        .eq("v.deleted", 0)
                        .eq("p.submit_status_code", "SUBMITTED");
        if (variant != null) q.eq("v.id", variant);
        else
            q.and(
                    w ->
                            w.like("p.product_name", keyword)
                                    .or()
                                    .like("p.product_code", keyword)
                                    .or()
                                    .like("v.variant_code", keyword));
        return mapper.products(q.orderByAsc("p.id", "v.id").last("LIMIT 100"));
    }
}

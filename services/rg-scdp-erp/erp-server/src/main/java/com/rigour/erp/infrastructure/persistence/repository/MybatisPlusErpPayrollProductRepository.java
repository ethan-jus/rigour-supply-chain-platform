package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.erp.application.port.out.ErpPayrollProductStore;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.mapper.ErpPayrollProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductCategoryMapper;

import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;

@Repository
public class MybatisPlusErpPayrollProductRepository implements ErpPayrollProductStore {
    private final ErpPayrollProductMapper mapper;

    private final InternalProductCategoryMapper categories;

    public MybatisPlusErpPayrollProductRepository(
            ErpPayrollProductMapper mapper, InternalProductCategoryMapper categories) {
        this.mapper = mapper;
        this.categories = categories;
    }

    @Override
    public List<ErpPayrollCategoryView> categories(String tenant) {
        return categories
                .selectList(
                        new QueryWrapper<InternalProductCategoryEntity>()
                                .eq("tenant_id", tenant)
                                .eq("deleted", 0)
                                .orderByAsc("ordinal", "id"))
                .stream()
                .map(
                        c ->
                                new ErpPayrollCategoryView(
                                        c.getId(),
                                        c.getParentId(),
                                        c.getCategoryName(),
                                        c.getOrdinal() == null ? 0 : c.getOrdinal()))
                .toList();
    }

    @Override
    public List<ErpPayrollProductView> productsByVariants(String tenant, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return mapper.products(
                new QueryWrapper<InternalProductVariantEntity>()
                        .eq("p.tenant_id", tenant)
                        .eq("p.deleted", 0)
                        .eq("v.deleted", 0)
                        .eq("p.submit_status_code", "SUBMITTED")
                        .in("v.id", ids)
                        .orderByAsc("v.id"));
    }

    @Override
    public List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId) {
        return products(tenant, keyword, variant, categoryId, 0);
    }

    @Override
    public List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId, int begin) {
        var q =
                new QueryWrapper<InternalProductVariantEntity>()
                        .eq("p.tenant_id", tenant)
                        .eq("p.deleted", 0)
                        .eq("v.deleted", 0)
                        .eq("p.submit_status_code", "SUBMITTED");
        if (categoryId != null) {
            var all = categories(tenant);
            if (all.stream().noneMatch(c -> c.id().equals(categoryId))) return List.of();
            var ids = new HashSet<Long>();
            ids.add(categoryId);
            boolean changed;
            do {
                changed = false;
                for (var c : all) if (ids.contains(c.parentId())) changed |= ids.add(c.id());
            } while (changed);
            q.in("p.category_id", ids);
        }
        if (variant != null) q.eq("v.id", variant);
        else
            q.and(
                    w ->
                            w.like("p.product_name", keyword)
                                    .or()
                                    .like("p.product_code", keyword)
                                    .or()
                                    .like("v.variant_code", keyword)
                                    .or()
                                    .like("v.specification_snapshot", keyword));
        return mapper.products(
                q.orderByAsc("p.id", "v.id")
                        .last(begin == 0 ? "LIMIT 100" : "LIMIT 100 OFFSET " + begin));
    }
}

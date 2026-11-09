package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.merchant.application.port.out.CostCustomerReferenceStore;
import com.rigour.merchant.infrastructure.persistence.entity.InternalCustomerEntity;
import com.rigour.merchant.infrastructure.persistence.mapper.InternalCustomerMapper;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisPlusCostCustomerReferenceRepository implements CostCustomerReferenceStore {
    private final InternalCustomerMapper mapper;

    public MybatisPlusCostCustomerReferenceRepository(InternalCustomerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CostCustomerView> customersByCodes(String tenant, String city, List<String> codes) {
        return mapper
                .selectList(
                        new LambdaQueryWrapper<InternalCustomerEntity>()
                                .eq(InternalCustomerEntity::getTenantId, tenant)
                                .eq(InternalCustomerEntity::getRegionCode, city)
                                .eq(InternalCustomerEntity::getDeleted, 0)
                                .eq(InternalCustomerEntity::getStatusCode, "ACTIVE")
                                .in(InternalCustomerEntity::getCustomerCode, codes))
                .stream()
                .map(
                        r ->
                                new CostCustomerView(
                                        r.getId().toString(),
                                        r.getCustomerCode(),
                                        r.getCustomerName(),
                                        r.getContactName(),
                                        r.getContactPhone(),
                                        r.getAddress()))
                .toList();
    }

    public List<CostCustomerView> customers(
            String tenant, String city, String keyword, Long id, int begin) {
        var query =
                new LambdaQueryWrapper<InternalCustomerEntity>()
                        .select(
                                InternalCustomerEntity::getId,
                                InternalCustomerEntity::getCustomerCode,
                                InternalCustomerEntity::getCustomerName,
                                InternalCustomerEntity::getContactName,
                                InternalCustomerEntity::getContactPhone,
                                InternalCustomerEntity::getAddress)
                        .eq(InternalCustomerEntity::getTenantId, tenant)
                        .eq(InternalCustomerEntity::getRegionCode, city)
                        .eq(InternalCustomerEntity::getDeleted, 0)
                        .eq(InternalCustomerEntity::getStatusCode, "ACTIVE")
                        .eq(id != null, InternalCustomerEntity::getId, id);
        if (id == null && keyword != null && !keyword.isBlank())
            query.and(
                    q ->
                            q.apply("LOCATE({0},customer_name)>0", keyword.trim())
                                    .or()
                                    .apply("LOCATE({0},customer_code)>0", keyword.trim()));
        query.orderByAsc(InternalCustomerEntity::getId)
                .last("LIMIT 50 OFFSET " + Math.max(0, begin));
        return mapper.selectList(query).stream()
                .map(
                        r ->
                                new CostCustomerView(
                                        r.getId().toString(),
                                        r.getCustomerCode(),
                                        r.getCustomerName(),
                                        r.getContactName(),
                                        r.getContactPhone(),
                                        r.getAddress()))
                .toList();
    }
}

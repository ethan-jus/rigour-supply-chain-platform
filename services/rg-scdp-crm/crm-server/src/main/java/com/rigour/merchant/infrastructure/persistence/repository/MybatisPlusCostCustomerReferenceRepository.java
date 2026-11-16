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
    public java.util.Map<String, List<CostCustomerView>> customersByCities(
            String tenant, java.util.Map<String, List<String>> requested) {
        if (requested.isEmpty()) return java.util.Map.of();
        var query =
                contactQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenant)
                        .eq(InternalCustomerEntity::getDeleted, 0)
                        .eq(InternalCustomerEntity::getStatusCode, "ACTIVE");
        // 城市和客户编码必须成对约束，不能展开成两个独立 IN 而读取其他城市同名编码。
        query.and(
                cities ->
                        requested.forEach(
                                (city, codes) ->
                                        cities.or(
                                                pair ->
                                                        pair.eq(
                                                                        InternalCustomerEntity
                                                                                ::getRegionCode,
                                                                        city)
                                                                .in(
                                                                        InternalCustomerEntity
                                                                                ::getCustomerCode,
                                                                        codes))));
        var grouped = new java.util.LinkedHashMap<String, List<CostCustomerView>>();
        requested.keySet().forEach(city -> grouped.put(city, new java.util.ArrayList<>()));
        for (var row : mapper.selectList(query)) grouped.get(row.getRegionCode()).add(view(row));
        grouped.replaceAll((city, rows) -> List.copyOf(rows));
        return java.util.Collections.unmodifiableMap(grouped);
    }

    private static LambdaQueryWrapper<InternalCustomerEntity> contactQuery() {
        // 选单仅需要收货资料，避免传输、反序列化客户完整来源快照等大字段。
        return new LambdaQueryWrapper<InternalCustomerEntity>()
                .select(
                        InternalCustomerEntity::getId,
                        InternalCustomerEntity::getRegionCode,
                        InternalCustomerEntity::getCustomerCode,
                        InternalCustomerEntity::getCustomerName,
                        InternalCustomerEntity::getContactName,
                        InternalCustomerEntity::getContactPhone,
                        InternalCustomerEntity::getAddress);
    }

    private static CostCustomerView view(InternalCustomerEntity row) {
        return new CostCustomerView(
                row.getId().toString(),
                row.getCustomerCode(),
                row.getCustomerName(),
                row.getContactName(),
                row.getContactPhone(),
                row.getAddress());
    }

    @Override
    public List<CostCustomerView> customersByCodes(String tenant, String city, List<String> codes) {
        return mapper
                .selectList(
                        contactQuery()
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

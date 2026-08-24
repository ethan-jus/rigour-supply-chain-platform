package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore.CustomerSearchCriteria;
import com.rigour.merchant.infrastructure.persistence.entity.InternalCustomerEntity;
import com.rigour.merchant.infrastructure.persistence.mapper.InternalCustomerMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus CRM 自研客户仓储；只维护 `crm_customer` 新表。 */
@Repository
public class MybatisPlusInternalCustomerRepository
        extends ServiceImpl<InternalCustomerMapper, InternalCustomerEntity>
        implements CrmInternalCustomerStore {
    private final Clock clock;

    public MybatisPlusInternalCustomerRepository(InternalCustomerMapper mapper, Clock crmClock) {
        this.baseMapper = mapper;
        this.clock = crmClock;
    }

    @Override
    public PageView<InternalCustomerSummaryView> customers(String tenantId, int begin, int step,
                                                           CustomerSearchCriteria criteria) {
        InternalCustomerMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalCustomerSummaryView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalCustomerEntity::getUpdatedTime)
                        .orderByDesc(InternalCustomerEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusInternalCustomerRepository::summary)
                .toList();
        return new PageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalCustomerDetailView> customer(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId)
                        .eq(InternalCustomerEntity::getId, id)
                        .eq(InternalCustomerEntity::getDeleted, 0)
                        .last("LIMIT 1")))
                .map(MybatisPlusInternalCustomerRepository::detail);
    }

    @Override
    public boolean existsByCode(String tenantId, String customerCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getCustomerCode, customerCode)) > 0;
    }

    @Override
    @Transactional
    public InternalCustomerDetailView create(String tenantId, String customerCode,
                                             InternalCustomerCommand command, String actorId) {
        LocalDateTime now = now();
        InternalCustomerEntity entity = new InternalCustomerEntity();
        entity.setTenantId(tenantId);
        entity.setCustomerCode(customerCode);
        entity.setCustomerName(command.customerName());
        entity.setContactName(command.contactName());
        entity.setContactPhone(command.contactPhone());
        entity.setCustomerTypeCode(command.customerTypeCode());
        entity.setRegionCode(command.regionCode());
        entity.setOwnerSalesUserId(command.ownerSalesUserId());
        entity.setOwnerSalesName(command.ownerSalesName());
        entity.setOwnerStaffCode(command.ownerStaffCode());
        entity.setOwnerStaffNameSnapshot(command.ownerStaffNameSnapshot());
        entity.setSettlementTypeCode(command.settlementTypeCode());
        entity.setAddress(command.address());
        entity.setStatusCode(command.statusCode());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        try {
            getBaseMapper().insert(entity);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("客户编号已存在");
        }
        return customer(tenantId, entity.getId()).orElseThrow(() -> notFound("客户不存在"));
    }

    @Override
    @Transactional
    public InternalCustomerDetailView update(String tenantId, Long id,
                                             InternalCustomerCommand command, String actorId) {
        InternalCustomerEntity existing = requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getCustomerName, command.customerName())
                .set(InternalCustomerEntity::getContactName, command.contactName())
                .set(InternalCustomerEntity::getContactPhone, command.contactPhone())
                .set(InternalCustomerEntity::getCustomerTypeCode, command.customerTypeCode())
                .set(InternalCustomerEntity::getRegionCode, command.regionCode())
                .set(InternalCustomerEntity::getOwnerSalesUserId, command.ownerSalesUserId())
                .set(InternalCustomerEntity::getOwnerSalesName, command.ownerSalesName())
                .set(InternalCustomerEntity::getOwnerStaffCode, command.ownerStaffCode())
                .set(InternalCustomerEntity::getOwnerStaffNameSnapshot, command.ownerStaffNameSnapshot())
                .set(InternalCustomerEntity::getSettlementTypeCode, command.settlementTypeCode())
                .set(InternalCustomerEntity::getAddress, command.address())
                .set(InternalCustomerEntity::getStatusCode, command.statusCode())
                .set(InternalCustomerEntity::getRemark, command.remark())
                .set(InternalCustomerEntity::getRevision, command.revision() + 1)
                .set(InternalCustomerEntity::getUpdatedBy, actorId)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getRevision, command.revision())
                .eq(InternalCustomerEntity::getDeleted, 0));
        if (updated != 1) {
            throw conflict("客户已被其他人修改，请刷新后重试");
        }
        return customer(tenantId, existing.getId()).orElseThrow(() -> notFound("客户不存在"));
    }

    @Override
    @Transactional
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getDeleted, 1)
                .set(InternalCustomerEntity::getRevision, revision + 1)
                .set(InternalCustomerEntity::getUpdatedBy, actorId)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getRevision, revision)
                .eq(InternalCustomerEntity::getDeleted, 0));
        if (updated != 1) {
            throw conflict("客户已被其他人修改，请刷新后重试");
        }
    }

    private InternalCustomerEntity requireActive(String tenantId, Long id) {
        InternalCustomerEntity entity = getBaseMapper().selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (entity == null) throw notFound("客户不存在");
        return entity;
    }

    private LambdaQueryWrapper<InternalCustomerEntity> query(String tenantId, CustomerSearchCriteria criteria) {
        LambdaQueryWrapper<InternalCustomerEntity> query = Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getDeleted, 0);
        if (criteria.customerCode() != null) {
            query.like(InternalCustomerEntity::getCustomerCode, criteria.customerCode());
        }
        if (criteria.customerName() != null) {
            query.like(InternalCustomerEntity::getCustomerName, criteria.customerName());
        }
        if (criteria.contactPhone() != null) {
            query.like(InternalCustomerEntity::getContactPhone, criteria.contactPhone());
        }
        if (criteria.customerTypeCode() != null) {
            query.eq(InternalCustomerEntity::getCustomerTypeCode, criteria.customerTypeCode());
        }
        if (criteria.regionCode() != null) {
            query.eq(InternalCustomerEntity::getRegionCode, criteria.regionCode());
        }
        if (criteria.ownerSalesUserId() != null) {
            query.eq(InternalCustomerEntity::getOwnerSalesUserId, criteria.ownerSalesUserId());
        }
        if (criteria.ownerStaffCode() != null) {
            query.eq(InternalCustomerEntity::getOwnerStaffCode, criteria.ownerStaffCode());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalCustomerEntity::getStatusCode, criteria.statusCode());
        }
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalCustomerSummaryView summary(InternalCustomerEntity entity) {
        return new InternalCustomerSummaryView(entity.getId(), entity.getCustomerCode(), entity.getCustomerName(),
                entity.getContactName(), entity.getContactPhone(), entity.getCustomerTypeCode(), entity.getRegionCode(), entity.getOwnerSalesUserId(),
                entity.getOwnerSalesName(), entity.getOwnerStaffCode(), entity.getOwnerStaffNameSnapshot(),
                entity.getSettlementTypeCode(), entity.getStatusCode(), entity.getRevision(), instant(entity.getUpdatedTime()));
    }

    private static InternalCustomerDetailView detail(InternalCustomerEntity entity) {
        return new InternalCustomerDetailView(entity.getId(), entity.getCustomerCode(), entity.getCustomerName(),
                entity.getContactName(), entity.getContactPhone(), entity.getCustomerTypeCode(), entity.getRegionCode(), entity.getOwnerSalesUserId(),
                entity.getOwnerSalesName(), entity.getOwnerStaffCode(), entity.getOwnerStaffNameSnapshot(),
                entity.getSettlementTypeCode(), entity.getAddress(), entity.getStatusCode(), entity.getRemark(),
                entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()), entity.getUpdatedBy(),
                instant(entity.getUpdatedTime()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}

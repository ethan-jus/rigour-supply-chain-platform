package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore.SupplierSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalSupplierProfileEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
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

/** MyBatis-Plus 供应商档案仓储；CRUD 内部统一使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusSupplierProfileRepository
        extends ServiceImpl<InternalSupplierProfileMapper, InternalSupplierProfileEntity>
        implements ErpSupplierProfileStore {
    private final Clock clock;

    public MybatisPlusSupplierProfileRepository(InternalSupplierProfileMapper mapper, Clock erpClock) {
        this.baseMapper = mapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalSupplierProfileView> suppliers(
            String tenantId, int begin, int step, SupplierSearchCriteria criteria) {
        InternalSupplierProfileMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalSupplierProfileView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalSupplierProfileEntity::getUpdatedTime)
                        .orderByDesc(InternalSupplierProfileEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusSupplierProfileRepository::view)
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalSupplierProfileView> supplier(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusSupplierProfileRepository::view);
    }

    @Override
    public boolean existsByCode(String tenantId, String supplierCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                .eq(InternalSupplierProfileEntity::getSupplierCode, supplierCode)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalSupplierProfileView create(
            String tenantId, String supplierCode, InternalSupplierProfileCommand command, String actorId) {
        LocalDateTime now = now();
        InternalSupplierProfileEntity entity = new InternalSupplierProfileEntity();
        entity.setTenantId(tenantId);
        entity.setSupplierCode(supplierCode);
        entity.setSupplierName(command.supplierName());
        entity.setContactName(command.contactName());
        entity.setContactPhone(command.contactPhone());
        entity.setAddress(command.address());
        entity.setBankName(command.bankName());
        entity.setBankAccountNo(command.bankAccountNo());
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
            throw conflict("供应商编号已存在");
        }
        return supplier(tenantId, entity.getId()).orElseThrow(() -> notFound("供应商不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalSupplierProfileView update(
            String tenantId, Long id, InternalSupplierProfileCommand command, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSupplierProfileEntity>lambdaUpdate()
                .set(InternalSupplierProfileEntity::getSupplierName, command.supplierName())
                .set(InternalSupplierProfileEntity::getContactName, command.contactName())
                .set(InternalSupplierProfileEntity::getContactPhone, command.contactPhone())
                .set(InternalSupplierProfileEntity::getAddress, command.address())
                .set(InternalSupplierProfileEntity::getBankName, command.bankName())
                .set(InternalSupplierProfileEntity::getBankAccountNo, command.bankAccountNo())
                .set(InternalSupplierProfileEntity::getStatusCode, command.statusCode())
                .set(InternalSupplierProfileEntity::getRemark, command.remark())
                .set(InternalSupplierProfileEntity::getRevision, command.revision() + 1)
                .set(InternalSupplierProfileEntity::getUpdatedBy, actorId)
                .set(InternalSupplierProfileEntity::getUpdatedTime, now)
                .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                .eq(InternalSupplierProfileEntity::getId, id)
                .eq(InternalSupplierProfileEntity::getRevision, command.revision())
                .eq(InternalSupplierProfileEntity::getDeleted, 0));
        if (updated != 1) throw conflict("供应商已被其他人修改，请刷新后重试");
        return supplier(tenantId, id).orElseThrow(() -> notFound("供应商不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSupplierProfileEntity>lambdaUpdate()
                .set(InternalSupplierProfileEntity::getDeleted, 1)
                .set(InternalSupplierProfileEntity::getRevision, revision + 1)
                .set(InternalSupplierProfileEntity::getUpdatedBy, actorId)
                .set(InternalSupplierProfileEntity::getUpdatedTime, now)
                .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                .eq(InternalSupplierProfileEntity::getId, id)
                .eq(InternalSupplierProfileEntity::getRevision, revision)
                .eq(InternalSupplierProfileEntity::getDeleted, 0));
        if (updated != 1) throw conflict("供应商已被其他人修改，请刷新后重试");
    }

    private Optional<InternalSupplierProfileEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                .eq(InternalSupplierProfileEntity::getId, id)
                .eq(InternalSupplierProfileEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("供应商不存在"));
    }

    private LambdaQueryWrapper<InternalSupplierProfileEntity> query(
            String tenantId, SupplierSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSupplierProfileEntity> query =
                Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                        .eq(InternalSupplierProfileEntity::getDeleted, 0);
        if (criteria.supplierCode() != null) {
            query.like(InternalSupplierProfileEntity::getSupplierCode, criteria.supplierCode());
        }
        if (criteria.supplierName() != null) {
            query.like(InternalSupplierProfileEntity::getSupplierName, criteria.supplierName());
        }
        if (criteria.contactPhone() != null) {
            query.like(InternalSupplierProfileEntity::getContactPhone, criteria.contactPhone());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalSupplierProfileEntity::getStatusCode, criteria.statusCode());
        }
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalSupplierProfileView view(InternalSupplierProfileEntity entity) {
        return new InternalSupplierProfileView(entity.getId(), entity.getSupplierCode(),
                entity.getSupplierName(), entity.getContactName(), entity.getContactPhone(), entity.getAddress(),
                entity.getBankName(), entity.getBankAccountNo(), entity.getStatusCode(), entity.getRemark(),
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

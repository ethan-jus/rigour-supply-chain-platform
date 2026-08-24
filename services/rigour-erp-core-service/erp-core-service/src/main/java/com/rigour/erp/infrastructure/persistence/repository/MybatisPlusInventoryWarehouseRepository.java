package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore.WarehouseSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
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

/** MyBatis-Plus 仓库仓储；CRUD 内部使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusInventoryWarehouseRepository
        extends ServiceImpl<InternalInventoryWarehouseMapper, InternalInventoryWarehouseEntity>
        implements ErpInventoryWarehouseStore {
    private final Clock clock;

    public MybatisPlusInventoryWarehouseRepository(InternalInventoryWarehouseMapper mapper, Clock erpClock) {
        this.baseMapper = mapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalInventoryWarehouseView> warehouses(
            String tenantId, int begin, int step, WarehouseSearchCriteria criteria) {
        InternalInventoryWarehouseMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalInventoryWarehouseView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalInventoryWarehouseEntity::getDefaultFlag)
                        .orderByDesc(InternalInventoryWarehouseEntity::getUpdatedTime)
                        .orderByDesc(InternalInventoryWarehouseEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusInventoryWarehouseRepository::view)
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalInventoryWarehouseView> warehouse(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusInventoryWarehouseRepository::view);
    }

    @Override
    public boolean existsByCode(String tenantId, String warehouseCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getWarehouseCode, warehouseCode)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalInventoryWarehouseView create(String tenantId, String warehouseCode,
                                                 InternalInventoryWarehouseCommand command,
                                                 String actorId) {
        LocalDateTime now = now();
        if (Boolean.TRUE.equals(command.defaultFlag())) {
            clearOtherDefaultWarehouses(tenantId, null, actorId, now);
        }
        InternalInventoryWarehouseEntity entity = new InternalInventoryWarehouseEntity();
        entity.setTenantId(tenantId);
        entity.setWarehouseCode(warehouseCode);
        entity.setWarehouseName(command.warehouseName());
        entity.setRegionCode(command.regionCode());
        entity.setWarehouseTypeCode(command.warehouseTypeCode());
        entity.setDefaultFlag(Boolean.TRUE.equals(command.defaultFlag()));
        entity.setAddress(command.address());
        entity.setContactName(command.contactName());
        entity.setContactPhone(command.contactPhone());
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
            throw conflict("仓库编号已存在");
        }
        return warehouse(tenantId, entity.getId()).orElseThrow(() -> notFound("仓库不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalInventoryWarehouseView update(String tenantId, Long id,
                                                 InternalInventoryWarehouseCommand command,
                                                 String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        if (Boolean.TRUE.equals(command.defaultFlag())) {
            clearOtherDefaultWarehouses(tenantId, id, actorId, now);
        }
        int updated = getBaseMapper().update(null, Wrappers.<InternalInventoryWarehouseEntity>lambdaUpdate()
                .set(InternalInventoryWarehouseEntity::getWarehouseName, command.warehouseName())
                .set(InternalInventoryWarehouseEntity::getRegionCode, command.regionCode())
                .set(InternalInventoryWarehouseEntity::getWarehouseTypeCode, command.warehouseTypeCode())
                .set(InternalInventoryWarehouseEntity::getDefaultFlag, command.defaultFlag())
                .set(InternalInventoryWarehouseEntity::getAddress, command.address())
                .set(InternalInventoryWarehouseEntity::getContactName, command.contactName())
                .set(InternalInventoryWarehouseEntity::getContactPhone, command.contactPhone())
                .set(InternalInventoryWarehouseEntity::getStatusCode, command.statusCode())
                .set(InternalInventoryWarehouseEntity::getRemark, command.remark())
                .set(InternalInventoryWarehouseEntity::getRevision, command.revision() + 1)
                .set(InternalInventoryWarehouseEntity::getUpdatedBy, actorId)
                .set(InternalInventoryWarehouseEntity::getUpdatedTime, now)
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getId, id)
                .eq(InternalInventoryWarehouseEntity::getRevision, command.revision())
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0));
        if (updated != 1) throw conflict("仓库已被其他人修改，请刷新后重试");
        return warehouse(tenantId, id).orElseThrow(() -> notFound("仓库不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalInventoryWarehouseEntity>lambdaUpdate()
                .set(InternalInventoryWarehouseEntity::getDeleted, 1)
                .set(InternalInventoryWarehouseEntity::getRevision, revision + 1)
                .set(InternalInventoryWarehouseEntity::getUpdatedBy, actorId)
                .set(InternalInventoryWarehouseEntity::getUpdatedTime, now)
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getId, id)
                .eq(InternalInventoryWarehouseEntity::getRevision, revision)
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0));
        if (updated != 1) throw conflict("仓库已被其他人修改，请刷新后重试");
    }

    private Optional<InternalInventoryWarehouseEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getId, id)
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("仓库不存在"));
    }

    private LambdaQueryWrapper<InternalInventoryWarehouseEntity> query(
            String tenantId, WarehouseSearchCriteria criteria) {
        LambdaQueryWrapper<InternalInventoryWarehouseEntity> query =
                Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0);
        if (criteria.warehouseCode() != null) {
            query.like(InternalInventoryWarehouseEntity::getWarehouseCode, criteria.warehouseCode());
        }
        if (criteria.warehouseName() != null) {
            query.like(InternalInventoryWarehouseEntity::getWarehouseName, criteria.warehouseName());
        }
        if (criteria.regionCode() != null) {
            query.eq(InternalInventoryWarehouseEntity::getRegionCode, criteria.regionCode());
        }
        if (criteria.defaultFlag() != null) {
            query.eq(InternalInventoryWarehouseEntity::getDefaultFlag, criteria.defaultFlag());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalInventoryWarehouseEntity::getStatusCode, criteria.statusCode());
        }
        return query;
    }

    private void clearOtherDefaultWarehouses(String tenantId, Long currentId, String actorId, LocalDateTime now) {
        var update = Wrappers.<InternalInventoryWarehouseEntity>lambdaUpdate()
                .set(InternalInventoryWarehouseEntity::getDefaultFlag, false)
                .set(InternalInventoryWarehouseEntity::getUpdatedBy, actorId)
                .set(InternalInventoryWarehouseEntity::getUpdatedTime, now)
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getDefaultFlag, true)
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0);
        if (currentId != null) {
            update.ne(InternalInventoryWarehouseEntity::getId, currentId);
        }
        getBaseMapper().update(null, update);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalInventoryWarehouseView view(InternalInventoryWarehouseEntity entity) {
        return new InternalInventoryWarehouseView(entity.getId(), entity.getWarehouseCode(),
                entity.getWarehouseName(), entity.getRegionCode(), entity.getWarehouseTypeCode(),
                entity.getDefaultFlag(), entity.getAddress(), entity.getContactName(), entity.getContactPhone(),
                entity.getStatusCode(), entity.getRemark(), entity.getRevision(), entity.getCreatedBy(),
                instant(entity.getCreatedTime()), entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
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

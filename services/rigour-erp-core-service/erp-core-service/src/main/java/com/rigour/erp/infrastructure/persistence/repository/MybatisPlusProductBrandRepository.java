package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductBrandStore;
import com.rigour.erp.application.port.out.ErpProductBrandStore.BrandSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductBrandEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductBrandMapper;
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

/** MyBatis-Plus 商品品牌仓储；CRUD 内部使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusProductBrandRepository
        extends ServiceImpl<InternalProductBrandMapper, InternalProductBrandEntity>
        implements ErpProductBrandStore {
    private final Clock clock;

    public MybatisPlusProductBrandRepository(InternalProductBrandMapper mapper, Clock erpClock) {
        this.baseMapper = mapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalProductBrandView> brands(
            String tenantId, int begin, int step, BrandSearchCriteria criteria) {
        InternalProductBrandMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProductBrandView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalProductBrandEntity::getUpdatedTime)
                        .orderByDesc(InternalProductBrandEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusProductBrandRepository::view)
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalProductBrandView> brand(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusProductBrandRepository::view);
    }

    @Override
    public boolean existsByCode(String tenantId, String brandCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalProductBrandEntity>lambdaQuery()
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getBrandCode, brandCode)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductBrandView create(String tenantId, String brandCode,
                                           InternalProductBrandCommand command, String actorId) {
        LocalDateTime now = now();
        InternalProductBrandEntity entity = new InternalProductBrandEntity();
        entity.setTenantId(tenantId);
        entity.setBrandCode(brandCode);
        entity.setBrandName(command.brandName());
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
            throw conflict("商品品牌编号已存在");
        }
        return brand(tenantId, entity.getId()).orElseThrow(() -> notFound("商品品牌不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductBrandView update(String tenantId, Long id,
                                           InternalProductBrandCommand command, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductBrandEntity>lambdaUpdate()
                .set(InternalProductBrandEntity::getBrandName, command.brandName())
                .set(InternalProductBrandEntity::getRemark, command.remark())
                .set(InternalProductBrandEntity::getRevision, command.revision() + 1)
                .set(InternalProductBrandEntity::getUpdatedBy, actorId)
                .set(InternalProductBrandEntity::getUpdatedTime, now)
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getId, id)
                .eq(InternalProductBrandEntity::getRevision, command.revision())
                .eq(InternalProductBrandEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品品牌已被其他人修改，请刷新后重试");
        return brand(tenantId, id).orElseThrow(() -> notFound("商品品牌不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductBrandEntity>lambdaUpdate()
                .set(InternalProductBrandEntity::getDeleted, 1)
                .set(InternalProductBrandEntity::getRevision, revision + 1)
                .set(InternalProductBrandEntity::getUpdatedBy, actorId)
                .set(InternalProductBrandEntity::getUpdatedTime, now)
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getId, id)
                .eq(InternalProductBrandEntity::getRevision, revision)
                .eq(InternalProductBrandEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品品牌已被其他人修改，请刷新后重试");
    }

    private Optional<InternalProductBrandEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProductBrandEntity>lambdaQuery()
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getId, id)
                .eq(InternalProductBrandEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("商品品牌不存在"));
    }

    private LambdaQueryWrapper<InternalProductBrandEntity> query(String tenantId, BrandSearchCriteria criteria) {
        LambdaQueryWrapper<InternalProductBrandEntity> query = Wrappers.<InternalProductBrandEntity>lambdaQuery()
                .eq(InternalProductBrandEntity::getTenantId, tenantId)
                .eq(InternalProductBrandEntity::getDeleted, 0);
        if (criteria.brandCode() != null) {
            query.like(InternalProductBrandEntity::getBrandCode, criteria.brandCode());
        }
        if (criteria.brandName() != null) {
            query.like(InternalProductBrandEntity::getBrandName, criteria.brandName());
        }
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalProductBrandView view(InternalProductBrandEntity entity) {
        return new InternalProductBrandView(entity.getId(), entity.getBrandCode(), entity.getBrandName(),
                entity.getRemark(), entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
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

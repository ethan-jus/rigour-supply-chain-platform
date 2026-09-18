package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductCategoryStore;
import com.rigour.erp.application.port.out.ErpProductCategoryStore.CategorySearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductCategoryEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductCategoryMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 商品分类仓储；CRUD 内部使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusProductCategoryRepository
        extends ServiceImpl<InternalProductCategoryMapper, InternalProductCategoryEntity>
        implements ErpProductCategoryStore {
    private final Clock clock;

    public MybatisPlusProductCategoryRepository(InternalProductCategoryMapper mapper, Clock erpClock) {
        this.baseMapper = mapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalProductCategoryView> categories(
            String tenantId, int begin, int step, CategorySearchCriteria criteria) {
        InternalProductCategoryMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProductCategoryView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByAsc(InternalProductCategoryEntity::getCategoryLevel)
                        .orderByAsc(InternalProductCategoryEntity::getOrdinal)
                        .orderByDesc(InternalProductCategoryEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusProductCategoryRepository::view)
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalProductCategoryView> category(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusProductCategoryRepository::view);
    }

    @Override
    public boolean existsByCode(String tenantId, String categoryCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getCategoryCode, categoryCode)) > 0;
    }

    @Override
    public boolean hasChildren(String tenantId, Long id) {
        return getBaseMapper().selectCount(Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getParentId, id)
                .eq(InternalProductCategoryEntity::getDeleted, 0)) > 0;
    }

    @Override
    public boolean hasAncestor(String tenantId, Long categoryId, Long ancestorId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = categoryId;
        while (cursor != null && visited.add(cursor)) {
            Optional<InternalProductCategoryEntity> category = selectActive(tenantId, cursor);
            if (category.isEmpty()) return false;
            Long parentId = category.get().getParentId();
            if (ancestorId.equals(parentId)) return true;
            cursor = parentId;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductCategoryView create(String tenantId, String categoryCode,
                                              InternalProductCategoryCommand command,
                                              int categoryLevel, String actorId) {
        LocalDateTime now = now();
        InternalProductCategoryEntity entity = new InternalProductCategoryEntity();
        entity.setTenantId(tenantId);
        entity.setParentId(command.parentId());
        entity.setCategoryCode(categoryCode);
        entity.setCategoryName(command.categoryName());
        entity.setCategoryLevel(categoryLevel);
        entity.setOrdinal(command.ordinal());
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
            throw conflict("商品分类编号已存在或父级分类无效");
        }
        return category(tenantId, entity.getId()).orElseThrow(() -> notFound("商品分类不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductCategoryView update(String tenantId, Long id,
                                              InternalProductCategoryCommand command,
                                              int categoryLevel, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductCategoryEntity>lambdaUpdate()
                .set(InternalProductCategoryEntity::getParentId, command.parentId())
                .set(InternalProductCategoryEntity::getCategoryName, command.categoryName())
                .set(InternalProductCategoryEntity::getCategoryLevel, categoryLevel)
                .set(InternalProductCategoryEntity::getOrdinal, command.ordinal())
                .set(InternalProductCategoryEntity::getRemark, command.remark())
                .set(InternalProductCategoryEntity::getRevision, command.revision() + 1)
                .set(InternalProductCategoryEntity::getUpdatedBy, actorId)
                .set(InternalProductCategoryEntity::getUpdatedTime, now)
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getId, id)
                .eq(InternalProductCategoryEntity::getRevision, command.revision())
                .eq(InternalProductCategoryEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品分类已被其他人修改，请刷新后重试");
        return category(tenantId, id).orElseThrow(() -> notFound("商品分类不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductCategoryEntity>lambdaUpdate()
                .set(InternalProductCategoryEntity::getDeleted, 1)
                .set(InternalProductCategoryEntity::getRevision, revision + 1)
                .set(InternalProductCategoryEntity::getUpdatedBy, actorId)
                .set(InternalProductCategoryEntity::getUpdatedTime, now)
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getId, id)
                .eq(InternalProductCategoryEntity::getRevision, revision)
                .eq(InternalProductCategoryEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品分类已被其他人修改，请刷新后重试");
    }

    private Optional<InternalProductCategoryEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                .eq(InternalProductCategoryEntity::getId, id)
                .eq(InternalProductCategoryEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("商品分类不存在"));
    }

    private LambdaQueryWrapper<InternalProductCategoryEntity> query(
            String tenantId, CategorySearchCriteria criteria) {
        LambdaQueryWrapper<InternalProductCategoryEntity> query =
                Wrappers.<InternalProductCategoryEntity>lambdaQuery()
                        .eq(InternalProductCategoryEntity::getTenantId, tenantId)
                        .eq(InternalProductCategoryEntity::getDeleted, 0);
        if (criteria.categoryCode() != null) {
            query.like(InternalProductCategoryEntity::getCategoryCode, criteria.categoryCode());
        }
        if (criteria.categoryName() != null) {
            query.like(InternalProductCategoryEntity::getCategoryName, criteria.categoryName());
        }
        if (criteria.parentId() != null) {
            query.eq(InternalProductCategoryEntity::getParentId, criteria.parentId());
        }
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalProductCategoryView view(InternalProductCategoryEntity entity) {
        return new InternalProductCategoryView(entity.getId(), entity.getCategoryCode(),
                entity.getCategoryName(), entity.getParentId(), entity.getCategoryLevel(),
                entity.getOrdinal(), entity.getRemark(), entity.getRevision(), entity.getCreatedBy(),
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

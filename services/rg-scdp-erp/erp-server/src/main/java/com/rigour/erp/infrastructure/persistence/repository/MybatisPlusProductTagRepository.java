package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductTagStore;
import com.rigour.erp.application.port.out.ErpProductTagStore.TagSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductTagEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductTagMapper;
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

/** MyBatis-Plus 商品标签仓储；CRUD 内部使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusProductTagRepository
        extends ServiceImpl<InternalProductTagMapper, InternalProductTagEntity>
        implements ErpProductTagStore {
    private final Clock clock;

    public MybatisPlusProductTagRepository(InternalProductTagMapper mapper, Clock erpClock) {
        this.baseMapper = mapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalProductTagView> tags(
            String tenantId, int begin, int step, TagSearchCriteria criteria) {
        InternalProductTagMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProductTagView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalProductTagEntity::getUpdatedTime)
                        .orderByDesc(InternalProductTagEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusProductTagRepository::view)
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalProductTagView> tag(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusProductTagRepository::view);
    }

    @Override
    public boolean existsByCode(String tenantId, String tagCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalProductTagEntity>lambdaQuery()
                .eq(InternalProductTagEntity::getTenantId, tenantId)
                .eq(InternalProductTagEntity::getTagCode, tagCode)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductTagView create(String tenantId, String tagCode,
                                         InternalProductTagCommand command, String actorId) {
        LocalDateTime now = now();
        InternalProductTagEntity entity = new InternalProductTagEntity();
        entity.setTenantId(tenantId);
        entity.setTagCode(tagCode);
        entity.setTagName(command.tagName());
        entity.setTagTypeCode(command.tagTypeCode());
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
            throw conflict("商品标签编号已存在");
        }
        return tag(tenantId, entity.getId()).orElseThrow(() -> notFound("商品标签不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductTagView update(String tenantId, Long id,
                                         InternalProductTagCommand command, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductTagEntity>lambdaUpdate()
                .set(InternalProductTagEntity::getTagName, command.tagName())
                .set(InternalProductTagEntity::getTagTypeCode, command.tagTypeCode())
                .set(InternalProductTagEntity::getRemark, command.remark())
                .set(InternalProductTagEntity::getRevision, command.revision() + 1)
                .set(InternalProductTagEntity::getUpdatedBy, actorId)
                .set(InternalProductTagEntity::getUpdatedTime, now)
                .eq(InternalProductTagEntity::getTenantId, tenantId)
                .eq(InternalProductTagEntity::getId, id)
                .eq(InternalProductTagEntity::getRevision, command.revision())
                .eq(InternalProductTagEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品标签已被其他人修改，请刷新后重试");
        return tag(tenantId, id).orElseThrow(() -> notFound("商品标签不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductTagEntity>lambdaUpdate()
                .set(InternalProductTagEntity::getDeleted, 1)
                .set(InternalProductTagEntity::getRevision, revision + 1)
                .set(InternalProductTagEntity::getUpdatedBy, actorId)
                .set(InternalProductTagEntity::getUpdatedTime, now)
                .eq(InternalProductTagEntity::getTenantId, tenantId)
                .eq(InternalProductTagEntity::getId, id)
                .eq(InternalProductTagEntity::getRevision, revision)
                .eq(InternalProductTagEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品标签已被其他人修改，请刷新后重试");
    }

    private Optional<InternalProductTagEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProductTagEntity>lambdaQuery()
                .eq(InternalProductTagEntity::getTenantId, tenantId)
                .eq(InternalProductTagEntity::getId, id)
                .eq(InternalProductTagEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("商品标签不存在"));
    }

    private LambdaQueryWrapper<InternalProductTagEntity> query(String tenantId, TagSearchCriteria criteria) {
        LambdaQueryWrapper<InternalProductTagEntity> query = Wrappers.<InternalProductTagEntity>lambdaQuery()
                .eq(InternalProductTagEntity::getTenantId, tenantId)
                .eq(InternalProductTagEntity::getDeleted, 0);
        if (criteria.tagCode() != null) {
            query.like(InternalProductTagEntity::getTagCode, criteria.tagCode());
        }
        if (criteria.tagName() != null) {
            query.like(InternalProductTagEntity::getTagName, criteria.tagName());
        }
        if (criteria.tagTypeCode() != null) {
            query.eq(InternalProductTagEntity::getTagTypeCode, criteria.tagTypeCode());
        }
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalProductTagView view(InternalProductTagEntity entity) {
        return new InternalProductTagView(entity.getId(), entity.getTagCode(), entity.getTagName(),
                entity.getTagTypeCode(), entity.getRemark(), entity.getRevision(), entity.getCreatedBy(),
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

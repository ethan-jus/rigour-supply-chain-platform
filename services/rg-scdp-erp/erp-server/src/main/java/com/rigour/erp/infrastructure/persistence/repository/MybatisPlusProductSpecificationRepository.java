package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationValueCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationValueView;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductSpecificationStore;
import com.rigour.erp.application.port.out.ErpProductSpecificationStore.SpecificationSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductSpecificationEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductSpecificationValueEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductSpecificationMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductSpecificationValueMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 商品规格仓储；CRUD 内部使用 BaseMapper 和 LambdaWrapper。 */
@Repository
public class MybatisPlusProductSpecificationRepository
        extends ServiceImpl<InternalProductSpecificationMapper, InternalProductSpecificationEntity>
        implements ErpProductSpecificationStore {
    private final InternalProductSpecificationValueMapper valueMapper;
    private final Clock clock;

    public MybatisPlusProductSpecificationRepository(
            InternalProductSpecificationMapper mapper,
            InternalProductSpecificationValueMapper valueMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.valueMapper = valueMapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalProductSpecificationView> specifications(
            String tenantId, int begin, int step, SpecificationSearchCriteria criteria) {
        InternalProductSpecificationMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProductSpecificationEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalProductSpecificationEntity::getUpdatedTime)
                .orderByDesc(InternalProductSpecificationEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Map<Long, List<InternalProductSpecificationValueEntity>> values =
                valuesBySpecification(tenantId, page.stream()
                        .map(InternalProductSpecificationEntity::getId)
                        .collect(Collectors.toSet()));
        List<InternalProductSpecificationView> items = page.stream()
                .map(item -> view(item, values.getOrDefault(item.getId(), List.of())))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalProductSpecificationView> specification(String tenantId, Long id) {
        return selectActive(tenantId, id).map(item ->
                view(item, valuesBySpecification(tenantId, Set.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public boolean existsByCode(String tenantId, String specificationCode, Long excludeId) {
        LambdaQueryWrapper<InternalProductSpecificationEntity> query =
                Wrappers.<InternalProductSpecificationEntity>lambdaQuery()
                        .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                        .eq(InternalProductSpecificationEntity::getSpecificationCode, specificationCode);
        if (excludeId != null) {
            query.ne(InternalProductSpecificationEntity::getId, excludeId);
        }
        return getBaseMapper().selectCount(query) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductSpecificationView create(String tenantId,
                                                   InternalProductSpecificationCommand command,
                                                   String actorId) {
        LocalDateTime now = now();
        InternalProductSpecificationEntity entity = new InternalProductSpecificationEntity();
        entity.setTenantId(tenantId);
        entity.setSpecificationCode(command.specificationCode());
        entity.setSpecificationName(command.specificationName());
        entity.setStatusCode(command.statusCode());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        try {
            getBaseMapper().insert(entity);
            insertValues(tenantId, entity.getId(), command.values(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("商品规格编号或规格值编号已存在");
        }
        return specification(tenantId, entity.getId()).orElseThrow(() -> notFound("商品规格不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProductSpecificationView update(String tenantId, Long id,
                                                   InternalProductSpecificationCommand command,
                                                   String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductSpecificationEntity>lambdaUpdate()
                .set(InternalProductSpecificationEntity::getSpecificationCode, command.specificationCode())
                .set(InternalProductSpecificationEntity::getSpecificationName, command.specificationName())
                .set(InternalProductSpecificationEntity::getStatusCode, command.statusCode())
                .set(InternalProductSpecificationEntity::getRevision, command.revision() + 1)
                .set(InternalProductSpecificationEntity::getUpdatedBy, actorId)
                .set(InternalProductSpecificationEntity::getUpdatedTime, now)
                .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationEntity::getId, id)
                .eq(InternalProductSpecificationEntity::getRevision, command.revision())
                .eq(InternalProductSpecificationEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品规格已被其他人修改，请刷新后重试");
        try {
            syncValues(tenantId, id, command.values(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("规格值编号已存在");
        }
        return specification(tenantId, id).orElseThrow(() -> notFound("商品规格不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProductSpecificationEntity>lambdaUpdate()
                .set(InternalProductSpecificationEntity::getDeleted, 1)
                .set(InternalProductSpecificationEntity::getRevision, revision + 1)
                .set(InternalProductSpecificationEntity::getUpdatedBy, actorId)
                .set(InternalProductSpecificationEntity::getUpdatedTime, now)
                .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationEntity::getId, id)
                .eq(InternalProductSpecificationEntity::getRevision, revision)
                .eq(InternalProductSpecificationEntity::getDeleted, 0));
        if (updated != 1) throw conflict("商品规格已被其他人修改，请刷新后重试");
        valueMapper.selectList(valueQuery(tenantId, id)).forEach(value ->
                logicDeleteValue(tenantId, id, value, actorId, now));
    }

    private void syncValues(String tenantId, Long specificationId,
                            List<InternalProductSpecificationValueCommand> commands,
                            String actorId, LocalDateTime now) {
        Map<Long, InternalProductSpecificationValueEntity> current = valueMapper
                .selectList(valueQuery(tenantId, specificationId))
                .stream()
                .collect(Collectors.toMap(InternalProductSpecificationValueEntity::getId,
                        Function.identity(), (a, b) -> a));
        Set<Long> submittedIds = commands.stream()
                .map(InternalProductSpecificationValueCommand::id)
                .filter(item -> item != null)
                .collect(Collectors.toSet());
        for (InternalProductSpecificationValueCommand command : commands) {
            if (command.id() == null) {
                insertValue(tenantId, specificationId, command, actorId, now);
            } else {
                InternalProductSpecificationValueEntity existing = current.get(command.id());
                if (existing == null) throw notFound("规格值不存在");
                updateValue(tenantId, specificationId, existing, command, actorId, now);
            }
        }
        current.values().stream()
                .filter(item -> !submittedIds.contains(item.getId()))
                .forEach(item -> logicDeleteValue(tenantId, specificationId, item, actorId, now));
    }

    private void insertValues(String tenantId, Long specificationId,
                              List<InternalProductSpecificationValueCommand> commands,
                              String actorId, LocalDateTime now) {
        commands.forEach(command -> insertValue(tenantId, specificationId, command, actorId, now));
    }

    private void insertValue(String tenantId, Long specificationId,
                             InternalProductSpecificationValueCommand command,
                             String actorId, LocalDateTime now) {
        InternalProductSpecificationValueEntity entity = new InternalProductSpecificationValueEntity();
        entity.setTenantId(tenantId);
        entity.setSpecificationId(specificationId);
        entity.setValueCode(command.valueCode());
        entity.setValueName(command.valueName());
        entity.setOrdinal(command.ordinal());
        entity.setStatusCode(command.statusCode());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        valueMapper.insert(entity);
    }

    private void updateValue(String tenantId, Long specificationId,
                             InternalProductSpecificationValueEntity existing,
                             InternalProductSpecificationValueCommand command,
                             String actorId, LocalDateTime now) {
        int updated = valueMapper.update(null, Wrappers.<InternalProductSpecificationValueEntity>lambdaUpdate()
                .set(InternalProductSpecificationValueEntity::getValueCode, command.valueCode())
                .set(InternalProductSpecificationValueEntity::getValueName, command.valueName())
                .set(InternalProductSpecificationValueEntity::getOrdinal, command.ordinal())
                .set(InternalProductSpecificationValueEntity::getStatusCode, command.statusCode())
                .set(InternalProductSpecificationValueEntity::getRevision, existing.getRevision() + 1)
                .set(InternalProductSpecificationValueEntity::getUpdatedBy, actorId)
                .set(InternalProductSpecificationValueEntity::getUpdatedTime, now)
                .eq(InternalProductSpecificationValueEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationValueEntity::getSpecificationId, specificationId)
                .eq(InternalProductSpecificationValueEntity::getId, existing.getId())
                .eq(InternalProductSpecificationValueEntity::getDeleted, 0));
        if (updated != 1) throw conflict("规格值已被其他人修改，请刷新后重试");
    }

    private void logicDeleteValue(String tenantId, Long specificationId,
                                  InternalProductSpecificationValueEntity existing,
                                  String actorId, LocalDateTime now) {
        int updated = valueMapper.update(null, Wrappers.<InternalProductSpecificationValueEntity>lambdaUpdate()
                .set(InternalProductSpecificationValueEntity::getDeleted, 1)
                .set(InternalProductSpecificationValueEntity::getRevision, existing.getRevision() + 1)
                .set(InternalProductSpecificationValueEntity::getUpdatedBy, actorId)
                .set(InternalProductSpecificationValueEntity::getUpdatedTime, now)
                .eq(InternalProductSpecificationValueEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationValueEntity::getSpecificationId, specificationId)
                .eq(InternalProductSpecificationValueEntity::getId, existing.getId())
                .eq(InternalProductSpecificationValueEntity::getDeleted, 0));
        if (updated != 1) throw conflict("规格值已被其他人修改，请刷新后重试");
    }

    private Optional<InternalProductSpecificationEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProductSpecificationEntity>lambdaQuery()
                .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationEntity::getId, id)
                .eq(InternalProductSpecificationEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void requireActive(String tenantId, Long id) {
        selectActive(tenantId, id).orElseThrow(() -> notFound("商品规格不存在"));
    }

    private LambdaQueryWrapper<InternalProductSpecificationEntity> query(
            String tenantId, SpecificationSearchCriteria criteria) {
        LambdaQueryWrapper<InternalProductSpecificationEntity> query =
                Wrappers.<InternalProductSpecificationEntity>lambdaQuery()
                        .eq(InternalProductSpecificationEntity::getTenantId, tenantId)
                        .eq(InternalProductSpecificationEntity::getDeleted, 0);
        if (criteria.specificationCode() != null) {
            query.like(InternalProductSpecificationEntity::getSpecificationCode, criteria.specificationCode());
        }
        if (criteria.specificationName() != null) {
            query.like(InternalProductSpecificationEntity::getSpecificationName, criteria.specificationName());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalProductSpecificationEntity::getStatusCode, criteria.statusCode());
        }
        return query;
    }

    private LambdaQueryWrapper<InternalProductSpecificationValueEntity> valueQuery(
            String tenantId, Long specificationId) {
        return Wrappers.<InternalProductSpecificationValueEntity>lambdaQuery()
                .eq(InternalProductSpecificationValueEntity::getTenantId, tenantId)
                .eq(InternalProductSpecificationValueEntity::getSpecificationId, specificationId)
                .eq(InternalProductSpecificationValueEntity::getDeleted, 0)
                .orderByAsc(InternalProductSpecificationValueEntity::getOrdinal)
                .orderByAsc(InternalProductSpecificationValueEntity::getId);
    }

    private Map<Long, List<InternalProductSpecificationValueEntity>> valuesBySpecification(
            String tenantId, Set<Long> specificationIds) {
        if (specificationIds.isEmpty()) return Map.of();
        return valueMapper.selectList(Wrappers.<InternalProductSpecificationValueEntity>lambdaQuery()
                        .eq(InternalProductSpecificationValueEntity::getTenantId, tenantId)
                        .in(InternalProductSpecificationValueEntity::getSpecificationId, specificationIds)
                        .eq(InternalProductSpecificationValueEntity::getDeleted, 0)
                        .orderByAsc(InternalProductSpecificationValueEntity::getOrdinal)
                        .orderByAsc(InternalProductSpecificationValueEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(InternalProductSpecificationValueEntity::getSpecificationId));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalProductSpecificationView view(
            InternalProductSpecificationEntity entity,
            List<InternalProductSpecificationValueEntity> values) {
        List<InternalProductSpecificationValueView> valueViews = values.stream()
                .sorted(Comparator.comparing(InternalProductSpecificationValueEntity::getOrdinal)
                        .thenComparing(InternalProductSpecificationValueEntity::getId))
                .map(MybatisPlusProductSpecificationRepository::valueView)
                .toList();
        return new InternalProductSpecificationView(entity.getId(), entity.getSpecificationCode(),
                entity.getSpecificationName(), entity.getStatusCode(), valueViews.size(), valueViews,
                entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
    }

    private static InternalProductSpecificationValueView valueView(
            InternalProductSpecificationValueEntity entity) {
        return new InternalProductSpecificationValueView(entity.getId(), entity.getValueCode(),
                entity.getValueName(), entity.getOrdinal(), entity.getStatusCode(), entity.getRevision(),
                entity.getCreatedBy(), instant(entity.getCreatedTime()),
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

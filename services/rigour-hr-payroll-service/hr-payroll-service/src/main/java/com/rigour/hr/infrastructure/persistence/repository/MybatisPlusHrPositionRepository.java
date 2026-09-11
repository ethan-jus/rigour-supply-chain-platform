package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import com.rigour.hr.application.port.out.HrPositionStore;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrPositionEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrPositionMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus HR 岗位职位仓储；保持与 ERP 主数据查询风格一致。 */
@Repository
public class MybatisPlusHrPositionRepository
        extends ServiceImpl<HrPositionMapper, HrPositionEntity>
        implements HrPositionStore {
    private final HrEmployeeMapper employeeMapper;
    private final java.time.Clock clock;

    public MybatisPlusHrPositionRepository(HrPositionMapper mapper,
                                           HrEmployeeMapper employeeMapper,
                                           java.time.Clock hrClock) {
        this.baseMapper = mapper;
        this.employeeMapper = employeeMapper;
        this.clock = hrClock;
    }

    @Override
    public HrPageView<HrPositionView> positions(String tenantId, int begin, int step,
                                                PositionSearchCriteria criteria) {
        long total = getBaseMapper().selectCount(positionQuery(tenantId, criteria));
        List<HrPositionView> items = getBaseMapper().selectList(positionQuery(tenantId, criteria)
                        .orderByDesc("updated_time")
                        .orderByDesc("id")
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusHrPositionRepository::view)
                .toList();
        return new HrPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<HrPositionView> position(String tenantId, Long id) {
        HrPositionEntity row = getBaseMapper().selectOne(new QueryWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        return Optional.ofNullable(row).map(MybatisPlusHrPositionRepository::view);
    }

    @Override
    public boolean existsByPositionCode(String tenantId, String positionCode) {
        return getBaseMapper().selectCount(new QueryWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("position_code", positionCode)) > 0;
    }

    @Override
    @Transactional
    public HrPositionView create(String tenantId, String positionCode,
                                 HrPositionCommand command, String actorId) {
        if (existsByName(tenantId, command.positionType(), command.positionName(), null)) {
            throw conflict("相同类型下岗位职位名称已存在");
        }
        LocalDateTime now = now();
        HrPositionEntity row = new HrPositionEntity();
        row.tenantId = tenantId;
        row.positionCode = positionCode;
        row.positionName = command.positionName();
        row.positionType = command.positionType();
        row.statusCode = command.statusCode();
        row.sourceSystem = command.sourceSystem();
        row.remark = command.remark();
        row.revision = 1;
        row.createdBy = actorId;
        row.createdTime = now;
        row.updatedBy = actorId;
        row.updatedTime = now;
        row.deleted = 0;
        try {
            getBaseMapper().insert(row);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("岗位职位编号或名称已存在");
        }
        return position(tenantId, row.id).orElseThrow(() -> notFound("岗位职位不存在"));
    }

    @Override
    @Transactional
    public HrPositionView update(String tenantId, Long id,
                                 HrPositionCommand command, String actorId) {
        HrPositionEntity existing = requireActive(tenantId, id);
        if (existsByName(tenantId, command.positionType(), command.positionName(), existing.id)) {
            throw conflict("相同类型下岗位职位名称已存在");
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, new UpdateWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .eq("revision", command.revision())
                .eq("deleted", 0)
                .set("position_name", command.positionName())
                .set("position_type", command.positionType())
                .set("status_code", command.statusCode())
                .set("source_system", command.sourceSystem())
                .set("remark", command.remark())
                .set("revision", command.revision() + 1)
                .set("updated_by", actorId)
                .set("updated_time", now));
        if (updated != 1) throw conflict("岗位职位已被其他流程修改，请刷新后重试");
        return position(tenantId, id).orElseThrow(() -> notFound("岗位职位不存在"));
    }

    @Override
    @Transactional
    public void delete(String tenantId, Long id, int revision, String actorId) {
        HrPositionEntity existing = requireActive(tenantId, id);
        long referenceCount = referenceCount(tenantId, existing);
        if (referenceCount > 0) {
            throw conflict("岗位职位已被员工主档引用，不能删除");
        }
        int updated = getBaseMapper().update(null, new UpdateWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .eq("revision", revision)
                .eq("deleted", 0)
                .set("status_code", "INACTIVE")
                .set("deleted", 1)
                .set("revision", revision + 1)
                .set("updated_by", actorId)
                .set("updated_time", now()));
        if (updated != 1) throw conflict("岗位职位已被其他流程修改，请刷新后重试");
    }

    private QueryWrapper<HrPositionEntity> positionQuery(String tenantId, PositionSearchCriteria criteria) {
        PositionSearchCriteria c = criteria == null
                ? new PositionSearchCriteria(null, null, null, null, null)
                : criteria;
        QueryWrapper<HrPositionEntity> query = new QueryWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("deleted", 0);
        like(query, "position_code", c.positionCode());
        like(query, "position_name", c.positionName());
        eq(query, "position_type", c.positionType());
        eq(query, "status_code", c.statusCode());
        eq(query, "source_system", c.sourceSystem());
        return query;
    }

    private HrPositionEntity requireActive(String tenantId, Long id) {
        HrPositionEntity row = getBaseMapper().selectOne(new QueryWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (row == null) throw notFound("岗位职位不存在");
        return row;
    }

    private boolean existsByName(String tenantId, String positionType,
                                 String positionName, Long excludedId) {
        QueryWrapper<HrPositionEntity> query = new QueryWrapper<HrPositionEntity>()
                .eq("tenant_id", tenantId)
                .eq("position_type", positionType)
                .eq("position_name", positionName)
                .eq("deleted", 0);
        if (excludedId != null) query.ne("id", excludedId);
        return getBaseMapper().selectCount(query) > 0;
    }

    private long referenceCount(String tenantId, HrPositionEntity position) {
        QueryWrapper<HrEmployeeEntity> query = new QueryWrapper<HrEmployeeEntity>()
                .eq("tenant_id", tenantId)
                .eq("deleted", 0);
        if ("JOB_CATEGORY".equals(position.positionType)) {
            query.eq("job_category", position.positionName);
        } else {
            query.eq("primary_position_code", position.positionCode);
        }
        return employeeMapper.selectCount(query);
    }

    private static void eq(QueryWrapper<HrPositionEntity> query, String column, String value) {
        if (value != null) query.eq(column, value);
    }

    private static void like(QueryWrapper<HrPositionEntity> query, String column, String value) {
        if (value != null) query.like(column, value);
    }

    private static HrPositionView view(HrPositionEntity row) {
        return new HrPositionView(row.id, row.positionCode, row.positionName, row.positionType,
                row.statusCode, row.sourceSystem, row.remark, row.revision, row.createdBy,
                instant(row.createdTime), row.updatedBy, instant(row.updatedTime));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}

package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncRowResult;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrEmployeeProfile;
import com.rigour.hr.api.v1.model.HrDepartmentView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.HrEmployeeStore;
import com.rigour.hr.domain.code.HrBusinessCodeRules;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSourceBindingEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrPositionEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeSourceBindingMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrPositionMapper;
import com.rigour.shared.core.code.BusinessCodeGenerator;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** MyBatis-Plus HR 员工主档仓储；外部来源同步在这里做幂等合并。 */
@Repository
public class MybatisPlusHrEmployeeRepository extends ServiceImpl<HrEmployeeMapper, HrEmployeeEntity>
        implements HrEmployeeStore {
    private final HrPositionMapper positionMapper;
    private final HrEmployeeSourceBindingMapper bindingMapper;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final HrDataScope scopes;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcHrOrganizationStore organization;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public MybatisPlusHrEmployeeRepository(
            HrEmployeeMapper employeeMapper,
            HrPositionMapper positionMapper,
            HrEmployeeSourceBindingMapper bindingMapper,
            PlatformTransactionManager transactionManager,
            Clock hrClock,
            HrDataScope scopes) {
        this.scopes = scopes;
        this.baseMapper = employeeMapper;
        this.positionMapper = positionMapper;
        this.bindingMapper = bindingMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = hrClock;
    }

    @Override
    public HrPageView<HrEmployeeView> employees(
            String tenantId, int begin, int step, EmployeeSearchCriteria criteria) {
        long total =
                getBaseMapper()
                        .selectCount(
                                scopes.apply(
                                        employeeQuery(tenantId, criteria), "hr:employee:read"));
        Map<Long,HrDepartmentView> departments = organization.departments(tenantId).stream().collect(Collectors.toMap(HrDepartmentView::id, d -> d));
        List<HrEmployeeView> items =
                getBaseMapper()
                        .selectList(
                                scopes.apply(employeeQuery(tenantId, criteria), "hr:employee:read")
                                        .orderByDesc("updated_time")
                                        .orderByDesc("id")
                                        .last("LIMIT " + step + " OFFSET " + begin))
                        .stream()
                        .map(row -> view(row, departments.get(row.departmentId), null))
                        .toList();
        return new HrPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<HrEmployeeView> employee(String tenantId, Long id) {
        HrEmployeeEntity row =
                getBaseMapper()
                        .selectOne(
                                scopes.apply(
                                                new QueryWrapper<HrEmployeeEntity>()
                                                        .eq("tenant_id", tenantId)
                                                        .eq("id", id)
                                                        .eq("deleted", 0),
                                                "hr:employee:read")
                                        .last("LIMIT 1"));
        scopes.compareRecord(tenantId, id, "hr:employee:read", row != null);
        return Optional.ofNullable(row).map(e -> view(e,
                organization.departments(tenantId).stream().filter(d -> d.id().equals(e.departmentId)).findFirst().orElse(null), profile(tenantId,id)));
    }

    @Override
    public boolean existsByEmployeeCode(String tenantId, String employeeCode) {
        return getBaseMapper()
                        .selectCount(
                                new QueryWrapper<HrEmployeeEntity>()
                                        .eq("tenant_id", tenantId)
                                        .eq("employee_code", employeeCode))
                > 0;
    }

    @Override
    public ExternalEmployeeSyncResult syncExternalEmployees(
            String tenantId,
            String sourceSystem,
            List<ExternalEmployeeRowCommand> rows,
            String actorId,
            BusinessCodeGenerator codeGenerator) {
        Objects.requireNonNull(codeGenerator, "codeGenerator");
        List<ExternalEmployeeSyncRowResult> rowResults = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (ExternalEmployeeRowCommand row :
                rows == null ? List.<ExternalEmployeeRowCommand>of() : rows) {
            try {
                SyncOutcome outcome =
                        transaction.execute(
                                status ->
                                        syncRow(
                                                tenantId,
                                                sourceSystem,
                                                row,
                                                actorId,
                                                codeGenerator));
                if (outcome == null) {
                    outcome = new SyncOutcome(row.sourceEmployeeId(), null, "FAILED", "员工同步未返回结果");
                }
                rowResults.add(
                        new ExternalEmployeeSyncRowResult(
                                outcome.sourceEmployeeId(),
                                outcome.employeeCode(),
                                outcome.status(),
                                outcome.message()));
                switch (outcome.status()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    case "UNCHANGED" -> unchanged++;
                    default -> {
                        failed++;
                        failureMessages.add(outcome.message());
                    }
                }
            } catch (RuntimeException exception) {
                failed++;
                String message = "员工同步失败: " + clean(exception.getMessage(), 240);
                failureMessages.add(message);
                rowResults.add(
                        new ExternalEmployeeSyncRowResult(
                                row == null ? null : row.sourceEmployeeId(),
                                null,
                                "FAILED",
                                message));
            }
        }
        if (!rowResults.isEmpty()) {
            transaction.executeWithoutResult(
                    status -> backfillLeaderCodes(tenantId, sourceSystem, actorId));
        }
        return new ExternalEmployeeSyncResult(
                rowResults.size(),
                created,
                updated,
                unchanged,
                failed,
                rowResults,
                failureMessages);
    }

    @Override
    public List<ExternalEmployeeResolvedView> resolveExternalEmployees(
            String tenantId,
            String sourceSystem,
            String sourceTenantKey,
            List<String> sourceEmployeeIds,
            List<String> employeeNames) {
        List<String> ids = cleanDistinct(sourceEmployeeIds, 128);
        List<String> names = cleanDistinct(employeeNames, 128);
        if (ids.isEmpty() && names.isEmpty()) return List.of();
        Map<String, ExternalEmployeeResolvedView> byKey = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            List<HrEmployeeSourceBindingEntity> bindings =
                    bindingMapper.selectList(
                            new QueryWrapper<HrEmployeeSourceBindingEntity>()
                                    .eq("tenant_id", tenantId)
                                    .eq("source_system", sourceSystem)
                                    .eq("source_tenant_key", sourceTenantKey)
                                    .in("source_employee_id", ids)
                                    .eq("deleted", 0));
            Set<String> employeeCodes =
                    bindings.stream()
                            .map(binding -> clean(binding.employeeCode, 50))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, HrEmployeeEntity> employees =
                    employeeCodes.isEmpty()
                            ? Map.of()
                            : getBaseMapper()
                                    .selectList(
                                            new QueryWrapper<HrEmployeeEntity>()
                                                    .eq("tenant_id", tenantId)
                                                    .in("employee_code", employeeCodes)
                                                    .eq("deleted", 0))
                                    .stream()
                                    .collect(
                                            Collectors.toMap(
                                                    row -> row.employeeCode,
                                                    row -> row,
                                                    (left, ignored) -> left,
                                                    LinkedHashMap::new));
            for (HrEmployeeSourceBindingEntity binding : bindings) {
                HrEmployeeEntity employee = employees.get(binding.employeeCode);
                if (employee == null) continue;
                String key = "ID:" + binding.sourceEmployeeId;
                byKey.putIfAbsent(
                        key,
                        resolved(
                                sourceSystem, sourceTenantKey, binding.sourceEmployeeId, employee));
            }
        }
        for (String name : names) {
            if (byKey.containsKey("NAME:" + name)) continue;
            HrEmployeeEntity employee = uniqueEmployeeByName(tenantId, name);
            if (employee == null) continue;
            byKey.put("NAME:" + name, resolved(sourceSystem, sourceTenantKey, null, employee));
        }
        return List.copyOf(byKey.values());
    }

    private SyncOutcome syncRow(
            String tenantId,
            String sourceSystem,
            ExternalEmployeeRowCommand row,
            String actorId,
            BusinessCodeGenerator codeGenerator) {
        LocalDateTime now = now();
        String sourceTenantKey = defaultText(row.sourceTenantKey(), "DEFAULT", 128);
        organization.lockForSource(tenantId);
        HrEmployeeSourceBindingEntity binding =
                sourceBinding(tenantId, sourceSystem, sourceTenantKey, row.sourceEmployeeId());
        HrEmployeeEntity employee =
                binding == null ? null : employeeByCode(tenantId, binding.employeeCode);
        if (binding != null && employee == null && getBaseMapper().selectCount(
                new QueryWrapper<HrEmployeeEntity>().eq("tenant_id", tenantId).eq("employee_code", binding.employeeCode).eq("deleted", 1)) > 0) {
            // 删除的员工保留来源映射和历史引用，定时同步不能重新创建同一员工。
            return new SyncOutcome(row.sourceEmployeeId(), binding.employeeCode, "UNCHANGED", "已删除员工不再回写主档");
        }
        // 新来源只能创建待补任职的员工，不能按同名/手机号覆盖已关联账号的真实员工。
        HrPositionEntity position = resolvePosition(tenantId,
                row.positionName() == null || row.positionName().isBlank() ? row.jobCategory() : row.positionName());
        HrPositionEntity primaryPosition = position;
        if (employee == null) {
            String employeeCode =
                    codeGenerator.generateUnique(
                            HrBusinessCodeRules.EMPLOYEE,
                            row.sourceCreatedAt(),
                            candidate -> !existsByEmployeeCode(tenantId, candidate));
            employee = new HrEmployeeEntity();
            employee.tenantId = tenantId;
            employee.employeeCode = employeeCode;
            copyEmployeeFields(employee, sourceSystem, row, primaryPosition);
            employee.revision = 1;
            employee.createdBy = actorId;
            employee.createdTime = now;
            employee.updatedBy = actorId;
            employee.updatedTime = now;
            employee.deleted = 0;
            getBaseMapper().insert(employee);
            organization.sourceEmployeeChanged(
                    tenantId, employee.employeeCode, null, employee.employmentStatus, actorId);
            upsertBinding(
                    tenantId,
                    sourceSystem,
                    sourceTenantKey,
                    row,
                    employee.employeeCode,
                    actorId,
                    now,
                    binding);
            return new SyncOutcome(
                    row.sourceEmployeeId(), employee.employeeCode, "CREATED", "HR员工已创建");
        }
        // 已核定的花名册主档由 HR 维护；来源绑定继续接收原始记录，不能回写姓名、手机号及在职状态。
        if (Boolean.TRUE.equals(employee.localProfileAuthoritative)) {
            upsertBinding(tenantId, sourceSystem, sourceTenantKey, row, employee.employeeCode, actorId, now, binding);
            return new SyncOutcome(row.sourceEmployeeId(), employee.employeeCode, "UNCHANGED", "已保留HR核定资料，来源记录已更新");
        }
        if (sameHash(employee.sourcePayloadHash, row.sourcePayloadHash())) {
            upsertBinding(
                    tenantId,
                    sourceSystem,
                    sourceTenantKey,
                    row,
                    employee.employeeCode,
                    actorId,
                    now,
                    binding);
            return new SyncOutcome(
                    row.sourceEmployeeId(), employee.employeeCode, "UNCHANGED", "HR员工无变化");
        }
        String previousStatus = employee.employmentStatus;
        boolean wasActive = "ACTIVE".equals(previousStatus);
        copyEmployeeFields(employee, sourceSystem, row, primaryPosition);
        boolean accessRevoked = wasActive && !"ACTIVE".equals(employee.employmentStatus);
        employee.updatedBy = actorId;
        employee.updatedTime = now;
        int nextRevision = employee.revision == null ? 2 : employee.revision + 1;
        int updated =
                getBaseMapper()
                        .update(
                                null,
                                new UpdateWrapper<HrEmployeeEntity>()
                                        .eq("tenant_id", tenantId)
                                        .eq("id", employee.id)
                                        .eq("revision", employee.revision)
                                        .eq("deleted", 0)
                                        .setSql(
                                                accessRevoked,
                                                "access_version = access_version + 1")
                                        .set("employee_name", employee.employeeName)
                                        .set("mobile", employee.mobile)
                                        .set("email", employee.email)
                                        .set("employment_status", employee.employmentStatus)
                                        .set("job_category", employee.jobCategory)
                                        .set("primary_position_code", employee.primaryPositionCode)
                                        .set(
                                                "primary_position_name_snapshot",
                                                employee.primaryPositionNameSnapshot)
                                        .set(
                                                "department_name_snapshot",
                                                employee.departmentNameSnapshot)
                                        .set("leader_name_snapshot", employee.leaderNameSnapshot)
                                        .set("region_name", employee.regionName)
                                        .set("city_name", employee.cityName)
                                        .set("entry_date", employee.entryDate)
                                        .set("leave_date", employee.leaveDate)
                                        .set("source_system", employee.sourceSystem)
                                        .set("source_document_no", employee.sourceDocumentNo)
                                        .set("source_created_at", employee.sourceCreatedAt)
                                        .set("source_updated_at", employee.sourceUpdatedAt)
                                        .set("source_payload_hash", employee.sourcePayloadHash)
                                        .set("source_payload_json", employee.sourcePayloadJson)
                                        .set("remark", employee.remark)
                                        .set("revision", nextRevision)
                                        .set("updated_by", actorId)
                                        .set("updated_by_name", null)
                                        .set("updated_time", now));
        if (updated != 1) {
            throw new IllegalStateException("HR员工已被其他流程修改，请重试");
        }
        organization.sourceEmployeeChanged(
                tenantId,
                employee.employeeCode,
                previousStatus,
                employee.employmentStatus,
                actorId);
        upsertBinding(
                tenantId,
                sourceSystem,
                sourceTenantKey,
                row,
                employee.employeeCode,
                actorId,
                now,
                binding);
        return new SyncOutcome(row.sourceEmployeeId(), employee.employeeCode, "UPDATED", "HR员工已更新");
    }

    private void copyEmployeeFields(
            HrEmployeeEntity employee,
            String sourceSystem,
            ExternalEmployeeRowCommand row,
            HrPositionEntity position) {
        employee.employeeName = row.employeeName();
        employee.mobile = clean(row.mobile(), 32);
        employee.email = clean(row.email(), 128);
        employee.employmentStatus = employmentStatus(row.employmentStatus());
        employee.jobCategory =
                clean(row.jobCategory(), 80);
        if (employee.departmentId == null) {
            employee.primaryPositionCode = position == null ? null : position.positionCode;
            employee.primaryPositionNameSnapshot = position == null ? null : position.positionName;
            if (employee.jobGrade == null && position != null && "业务员".equals(position.positionName)) employee.jobGrade = "S1";
            employee.departmentNameSnapshot = clean(row.departmentName(), 128);
        }
        employee.leaderNameSnapshot = clean(row.leaderName(), 128);
        employee.regionName = clean(row.regionName(), 80);
        employee.cityName = clean(row.cityName(), 80);
        employee.entryDate = dateTime(row.entryDate());
        employee.leaveDate = dateTime(row.leaveDate());
        employee.sourceSystem = sourceSystem;
        employee.sourceDocumentNo = clean(row.sourceEmployeeId(), 128);
        employee.sourceCreatedAt = dateTime(row.sourceCreatedAt());
        employee.sourceUpdatedAt = dateTime(row.sourceUpdatedAt());
        employee.sourcePayloadHash = clean(row.sourcePayloadHash(), 64);
        employee.sourcePayloadJson = emptyToNull(row.sourcePayloadJson());
        employee.remark = remark(row);
    }

    private HrPositionEntity resolvePosition(String tenantId, String sourceName) {
        String positionName = clean(sourceName, 120);
        if ("销售".equals(positionName) || "销售员".equals(positionName) || "大客户经理".equals(positionName)) positionName = "业务员";
        if (positionName == null) return null;
        HrPositionEntity existing =
                positionMapper.selectOne(
                        new QueryWrapper<HrPositionEntity>()
                                .eq("tenant_id", tenantId)
                                .eq("position_name", positionName)
                                .eq("deleted", 0)
                                .last("LIMIT 1"));
        if (existing != null) return existing;
        // 外部同步只匹配已维护的岗位，未知来源岗位留待人工维护。
        return null;
    }

    private void upsertBinding(
            String tenantId,
            String sourceSystem,
            String sourceTenantKey,
            ExternalEmployeeRowCommand row,
            String employeeCode,
            String actorId,
            LocalDateTime now,
            HrEmployeeSourceBindingEntity existing) {
        HrEmployeeSourceBindingEntity binding =
                existing == null
                        ? sourceBinding(
                                tenantId, sourceSystem, sourceTenantKey, row.sourceEmployeeId())
                        : existing;
        if (binding == null) {
            HrEmployeeSourceBindingEntity created = new HrEmployeeSourceBindingEntity();
            created.tenantId = tenantId;
            created.employeeCode = employeeCode;
            created.connectorId = bin(row.connectorId());
            created.sourceSystem = sourceSystem;
            created.sourceTenantKey = sourceTenantKey;
            created.sourceEmployeeId = clean(row.sourceEmployeeId(), 128);
            created.sourceAccountName = clean(row.accountName(), 128);
            created.sourcePayloadHash = clean(row.sourcePayloadHash(), 64);
            created.sourcePayloadJson = emptyToNull(row.sourcePayloadJson());
            created.sourceCreatedAt = dateTime(row.sourceCreatedAt());
            created.sourceUpdatedAt = dateTime(row.sourceUpdatedAt());
            created.createdBy = actorId;
            created.createdTime = now;
            created.updatedBy = actorId;
            created.updatedTime = now;
            created.deleted = 0;
            bindingMapper.insert(created);
            return;
        }
        bindingMapper.update(
                null,
                new UpdateWrapper<HrEmployeeSourceBindingEntity>()
                        .eq("tenant_id", tenantId)
                        .eq("source_system", sourceSystem)
                        .eq("source_tenant_key", sourceTenantKey)
                        .eq("source_employee_id", row.sourceEmployeeId())
                        .set("employee_code", employeeCode)
                        .set("connector_id", bin(row.connectorId()))
                        .set("source_account_name", clean(row.accountName(), 128))
                        .set("source_payload_hash", clean(row.sourcePayloadHash(), 64))
                        .set("source_payload_json", emptyToNull(row.sourcePayloadJson()))
                        .set("source_created_at", dateTime(row.sourceCreatedAt()))
                        .set("source_updated_at", dateTime(row.sourceUpdatedAt()))
                        .set("updated_by", actorId)
                        .set("updated_time", now)
                        .set("deleted", 0));
    }

    private void backfillLeaderCodes(String tenantId, String sourceSystem, String actorId) {
        List<HrEmployeeEntity> rows =
                getBaseMapper()
                        .selectList(
                                new QueryWrapper<HrEmployeeEntity>()
                                        .eq("tenant_id", tenantId)
                                        .eq("source_system", sourceSystem)
                                        .eq("deleted", 0)
                                        .isNotNull("leader_name_snapshot"));
        LocalDateTime now = now();
        for (HrEmployeeEntity row : rows) {
            String leaderCode = uniqueEmployeeCodeByName(tenantId, row.leaderNameSnapshot);
            if (leaderCode == null || leaderCode.equals(row.leaderEmployeeCode)) continue;
            getBaseMapper()
                    .update(
                            null,
                            new UpdateWrapper<HrEmployeeEntity>()
                                    .eq("tenant_id", tenantId)
                                    .eq("id", row.id)
                                    .eq("deleted", 0)
                                    .set("leader_employee_code", leaderCode)
                                    .set("updated_by", actorId)
                                    .set("updated_time", now)
                                    .setSql("revision = revision + 1"));
        }
    }

    private HrEmployeeSourceBindingEntity sourceBinding(
            String tenantId, String sourceSystem, String sourceTenantKey, String sourceEmployeeId) {
        return bindingMapper.selectOne(
                new QueryWrapper<HrEmployeeSourceBindingEntity>()
                        .eq("tenant_id", tenantId)
                        .eq("source_system", sourceSystem)
                        .eq("source_tenant_key", sourceTenantKey)
                        .eq("source_employee_id", sourceEmployeeId)
                        .last("LIMIT 1"));
    }

    private HrEmployeeEntity employeeByCode(String tenantId, String employeeCode) {
        if (employeeCode == null || employeeCode.isBlank()) return null;
        return getBaseMapper()
                .selectOne(
                        new QueryWrapper<HrEmployeeEntity>()
                                .eq("tenant_id", tenantId)
                                .eq("employee_code", employeeCode)
                                .eq("deleted", 0)
                                .last("LIMIT 1"));
    }

    private HrEmployeeEntity uniqueEmployeeByName(String tenantId, String employeeName) {
        String value = clean(employeeName, 128);
        if (value == null) return null;
        List<HrEmployeeEntity> rows =
                getBaseMapper()
                        .selectList(
                                new QueryWrapper<HrEmployeeEntity>()
                                        .eq("tenant_id", tenantId)
                                        .eq("employee_name", value)
                                        .eq("deleted", 0)
                                        .last("LIMIT 2"));
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private String uniqueEmployeeCodeByName(String tenantId, String employeeName) {
        HrEmployeeEntity employee = uniqueEmployeeByName(tenantId, employeeName);
        return employee == null ? null : employee.employeeCode;
    }

    private static List<String> cleanDistinct(List<String> values, int max) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .map(value -> clean(value, max))
                .filter(Objects::nonNull)
                .distinct()
                .limit(1_000)
                .toList();
    }

    private QueryWrapper<HrEmployeeEntity> employeeQuery(
            String tenantId, EmployeeSearchCriteria criteria) {
        EmployeeSearchCriteria c =
                criteria == null
                        ? new EmployeeSearchCriteria(
                                null, null, null, null, null, null, null, null, null, null, null, null, null)
                        : criteria;
        QueryWrapper<HrEmployeeEntity> query =
                new QueryWrapper<HrEmployeeEntity>().eq("tenant_id", tenantId).eq("deleted", 0);
        if (c.departmentId() != null) {
            if (c.departmentId() <= 0) throw new IllegalArgumentException("部门 ID 无效");
            query.apply("department_id IN (SELECT descendant_id FROM hr_department_closure WHERE tenant_id={0} AND ancestor_id={1})", tenantId, c.departmentId());
        }
        if (c.keyword() != null) {
            query.and(
                    nested ->
                            nested.like("employee_code", c.keyword())
                                    .or()
                                    .like("employee_name", c.keyword())
                                    .or()
                                    .like("mobile", c.keyword())
                                    .or()
                                    .like("primary_position_name_snapshot", c.keyword())
                                    .or()
                                    .like("leader_name_snapshot", c.keyword())
                                    .or()
                                    .like("region_name", c.keyword())
                                    .or()
                                    .like("city_name", c.keyword()));
        }
        eq(query, "employee_code", c.employeeCode());
        like(query, "employee_name", c.employeeName());
        like(query, "mobile", c.mobile());
        eq(query, "employment_status", c.employmentStatus());
        like(query, "job_category", c.jobCategory());
        like(query, "primary_position_name_snapshot", c.positionName());
        like(query, "region_name", c.regionName());
        like(query, "city_name", c.cityName());
        eq(query, "source_system", c.sourceSystem());
        eq(query, "primary_position_code", c.positionCode());
        eq(query, "job_grade", c.jobGrade());
        return query;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void eq(QueryWrapper<HrEmployeeEntity> query, String column, String value) {
        if (value != null) query.eq(column, value);
    }

    private static void like(QueryWrapper<HrEmployeeEntity> query, String column, String value) {
        if (value != null) query.like(column, value);
    }

    private HrEmployeeProfile profile(String tenant, Long id) {
        return jdbc.query("SELECT * FROM hr_employee_profile WHERE tenant_id=? AND employee_id=?",
                (r,n) -> new HrEmployeeProfile(r.getString("id_number"),r.getObject("contract_end_date",java.time.LocalDate.class),
                        r.getString("education"),r.getString("registered_address"),r.getString("household_type"),r.getString("residential_address"),
                        r.getString("bank_account"),r.getString("bank_name"),r.getString("social_insurance"),r.getString("emergency_contact"),r.getString("emergency_phone"),
                        r.getString("graduation_school"),r.getString("major"),r.getString("regular_salary"),r.getString("probation_salary"),r.getString("probation_period")),
                tenant,id).stream().findFirst().orElse(null);
    }

    private static HrEmployeeView view(HrEmployeeEntity row, HrDepartmentView department, HrEmployeeProfile profile) {
        return new HrEmployeeView(
                row.id,
                row.employeeCode,
                row.employeeName,
                row.mobile,
                row.email,
                row.employmentStatus,
                row.jobCategory,
                row.primaryPositionCode,
                row.primaryPositionNameSnapshot,
                row.departmentNameSnapshot,
                row.leaderEmployeeCode,
                row.leaderNameSnapshot,
                row.regionName,
                row.cityName,
                row.sourceSystem,
                row.sourceDocumentNo,
                instant(row.sourceCreatedAt),
                instant(row.sourceUpdatedAt),
                instant(row.entryDate),
                instant(row.leaveDate),
                row.remark,
                row.revision,
                row.createdBy,
                instant(row.createdTime),
                row.updatedBy,
                instant(row.updatedTime), row.departmentId, department == null ? null : department.leaderName(),
                row.createdByName, row.updatedByName, profile, row.jobGrade);
    }

    private static ExternalEmployeeResolvedView resolved(
            String sourceSystem,
            String sourceTenantKey,
            String sourceEmployeeId,
            HrEmployeeEntity row) {
        return new ExternalEmployeeResolvedView(
                sourceSystem,
                sourceTenantKey,
                sourceEmployeeId,
                row.id,
                row.employeeCode,
                row.employeeName,
                row.employmentStatus,
                row.jobCategory,
                row.primaryPositionCode,
                row.primaryPositionNameSnapshot,
                row.departmentNameSnapshot,
                row.leaderEmployeeCode,
                row.leaderNameSnapshot,
                row.regionName,
                row.cityName,
                instant(row.sourceUpdatedAt));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime dateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String employmentStatus(String value) {
        return com.rigour.hr.domain.model.EmploymentStatus.fromSource(clean(value, 32));
    }

    private static String remark(ExternalEmployeeRowCommand row) {
        List<String> parts = new ArrayList<>();
        append(parts, "账号", row.accountName());
        append(parts, "来源区域", row.regionName());
        append(parts, "来源城市", row.cityName());
        return parts.isEmpty() ? null : clean(String.join("; ", parts), 500);
    }

    private static void append(List<String> parts, String label, String value) {
        String text = clean(value, 120);
        if (text != null) parts.add(label + "=" + text);
    }

    private static String first(String first, String second) {
        String one = clean(first, 120);
        return one == null ? clean(second, 120) : one;
    }

    private static String defaultText(String value, String defaultValue, int max) {
        String text = clean(value, max);
        return text == null ? defaultValue : text;
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean sameHash(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private static byte[] bin(UUID value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }

    private record SyncOutcome(
            String sourceEmployeeId, String employeeCode, String status, String message) {}
}

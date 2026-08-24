package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.tenant.iam.application.port.out.IamStaffManagementStore;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.*;
import com.rigour.tenant.iam.domain.code.IamBusinessCodeRules;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.*;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.*;
import com.rigour.tenant.iam.infrastructure.persistence.projection.StaffListRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 人员中心MyBatis-Plus持久化实现；我方人员/岗位编码由IAM规则生成。 */
@Repository
public class MybatisPlusIamStaffManagementStore implements IamStaffManagementStore {
    private static final Logger log = LoggerFactory.getLogger(MybatisPlusIamStaffManagementStore.class);
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String LEFT = "LEFT";
    private static final String INACTIVE = "INACTIVE";
    private static final String MANUAL = "MANUAL";
    private static final String DINGHUOBAO = "DINGHUOBAO";
    private static final String PRESENT = "PRESENT";
    private static final String PRIMARY = "PRIMARY";
    private static final int SYNC_BATCH_LIMIT = 1_000;

    private static final Set<String> STAFF_STATUSES = Set.of(ACTIVE, DISABLED, LEFT);
    private static final Set<String> POSITION_STATUSES = Set.of(ACTIVE, DISABLED);

    private final PositionMapper positionMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffAssignmentMapper staffAssignmentMapper;
    private final StaffUserBindingMapper staffUserBindingMapper;
    private final ExternalStaffBindingMapper externalStaffBindingMapper;
    private final StaffManagementMapper staffManagementMapper;
    private final IdentifierGenerator ids;
    private final BusinessCodeGenerator codeGenerator;

    public MybatisPlusIamStaffManagementStore(
            PositionMapper positionMapper,
            StaffProfileMapper staffProfileMapper,
            StaffAssignmentMapper staffAssignmentMapper,
            StaffUserBindingMapper staffUserBindingMapper,
            ExternalStaffBindingMapper externalStaffBindingMapper,
            StaffManagementMapper staffManagementMapper,
            IdentifierGenerator ids) {
        this.positionMapper = Objects.requireNonNull(positionMapper, "positionMapper");
        this.staffProfileMapper = Objects.requireNonNull(staffProfileMapper, "staffProfileMapper");
        this.staffAssignmentMapper = Objects.requireNonNull(staffAssignmentMapper, "staffAssignmentMapper");
        this.staffUserBindingMapper = Objects.requireNonNull(staffUserBindingMapper, "staffUserBindingMapper");
        this.externalStaffBindingMapper = Objects.requireNonNull(externalStaffBindingMapper, "externalStaffBindingMapper");
        this.staffManagementMapper = Objects.requireNonNull(staffManagementMapper, "staffManagementMapper");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.codeGenerator = new BusinessCodeGenerator();
    }

    @Override
    public List<PositionView> positions(Actor actor) {
        requireTenantPermission(actor, "iam:position:read");
        return positionMapper.selectList(Wrappers.<PositionDO>lambdaQuery()
                        .eq(PositionDO::getTenantId, actor.tenantId())
                        .isNull(PositionDO::getDeletedAt)
                        .orderByAsc(PositionDO::getSortOrder, PositionDO::getPositionCode))
                .stream()
                .map(MybatisPlusIamStaffManagementStore::positionView)
                .toList();
    }

    @Override
    @Transactional
    public PositionView createPosition(Actor actor, PositionCommand command) {
        requireTenantPermission(actor, "iam:position:write");
        PositionCommand normalized = normalizePosition(command, false);
        LocalDateTime now = now();
        PositionDO record = new PositionDO();
        record.setId(ids.nextId());
        record.setTenantId(actor.tenantId());
        record.setPositionCode(nextPositionCode(actor.tenantId()));
        record.setPositionName(normalized.name());
        record.setDescription(normalized.description());
        record.setSortOrder(normalized.sortOrder());
        record.setStatus(normalized.status());
        record.setVersion(0);
        record.setCreatedAt(now);
        record.setCreatedBy(actor.principalId());
        record.setUpdatedAt(now);
        record.setUpdatedBy(actor.principalId());
        try {
            positionMapper.insert(record);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("岗位编码生成冲突，请重试", exception);
        }
        audit(actor, "POSITION_CREATE", "POSITION", record.getId());
        log.info("IAM岗位创建完成 tenantId={} positionId={} positionCode={} actorId={}",
                actor.tenantId(), record.getId(), record.getPositionCode(), actor.principalId());
        return positionView(record);
    }

    @Override
    @Transactional
    public PositionView updatePosition(Actor actor, UUID id, PositionCommand command) {
        requireTenantPermission(actor, "iam:position:write");
        if (id == null) throw new IllegalArgumentException("position id is required");
        PositionCommand normalized = normalizePosition(command, true);
        PositionDO record = new PositionDO();
        record.setPositionName(normalized.name());
        record.setDescription(normalized.description());
        record.setSortOrder(normalized.sortOrder());
        record.setStatus(normalized.status());
        record.setVersion(normalized.version() + 1);
        record.setUpdatedAt(now());
        record.setUpdatedBy(actor.principalId());
        int updated = positionMapper.update(record, Wrappers.<PositionDO>lambdaUpdate()
                .eq(PositionDO::getTenantId, actor.tenantId())
                .eq(PositionDO::getId, id)
                .eq(PositionDO::getVersion, normalized.version())
                .isNull(PositionDO::getDeletedAt));
        if (updated != 1) throw new IllegalArgumentException("岗位不存在或版本已变化");
        audit(actor, "POSITION_UPDATE", "POSITION", id);
        PositionDO updatedRecord = positionById(actor.tenantId(), id);
        log.info("IAM岗位修改完成 tenantId={} positionId={} positionCode={} version={} actorId={}",
                actor.tenantId(), updatedRecord.getId(), updatedRecord.getPositionCode(),
                updatedRecord.getVersion(), actor.principalId());
        return positionView(updatedRecord);
    }

    @Override
    public List<StaffView> staff(Actor actor, String keyword, String status) {
        requireTenantPermission(actor, "iam:staff:read");
        String normalizedStatus = optionalEnum(status, STAFF_STATUSES, "employmentStatus");
        return staffManagementMapper.selectStaff(
                        actor.tenantId(), text(keyword, 80, "keyword"), normalizedStatus)
                .stream()
                .map(MybatisPlusIamStaffManagementStore::staffView)
                .toList();
    }

    @Override
    @Transactional
    public StaffView createStaff(Actor actor, StaffCommand command) {
        requireTenantPermission(actor, "iam:staff:write");
        StaffCommand normalized = normalizeStaff(command, false, true);
        validateOrganization(actor.tenantId(), normalized.primaryOrganizationId());
        validatePosition(actor.tenantId(), normalized.primaryPositionId());
        validateUser(actor.tenantId(), normalized.userId());

        LocalDateTime now = now();
        StaffProfileDO record = new StaffProfileDO();
        record.setId(ids.nextId());
        record.setTenantId(actor.tenantId());
        record.setStaffCode(nextStaffCode(actor.tenantId()));
        record.setStaffName(normalized.staffName());
        record.setMobile(normalized.mobile());
        record.setEmail(normalized.email());
        record.setEmploymentStatus(normalized.employmentStatus());
        record.setPrimaryOrganizationId(normalized.primaryOrganizationId());
        record.setPrimaryPositionId(normalized.primaryPositionId());
        record.setRecordOrigin(MANUAL);
        record.setRemark(normalized.remark());
        record.setVersion(0);
        record.setCreatedAt(now);
        record.setCreatedBy(actor.principalId());
        record.setUpdatedAt(now);
        record.setUpdatedBy(actor.principalId());
        try {
            staffProfileMapper.insert(record);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("员工编码生成冲突，请重试", exception);
        }
        savePrimaryAssignment(actor, record.getId(), normalized.primaryOrganizationId(), normalized.primaryPositionId(), now);
        saveUserBinding(actor, record.getId(), normalized.userId(), now);
        audit(actor, "STAFF_CREATE", "STAFF", record.getId());
        log.info("IAM人员创建完成 tenantId={} staffId={} staffCode={} organizationId={} positionId={} actorId={}",
                actor.tenantId(), record.getId(), record.getStaffCode(), record.getPrimaryOrganizationId(),
                record.getPrimaryPositionId(), actor.principalId());
        return staffView(staffManagementMapper.selectStaffById(actor.tenantId(), record.getId()));
    }

    @Override
    @Transactional
    public StaffView updateStaff(Actor actor, UUID id, StaffCommand command) {
        requireTenantPermission(actor, "iam:staff:write");
        if (id == null) throw new IllegalArgumentException("staff id is required");
        StaffCommand normalized = normalizeStaff(command, true, true);
        validateOrganization(actor.tenantId(), normalized.primaryOrganizationId());
        validatePosition(actor.tenantId(), normalized.primaryPositionId());
        validateUser(actor.tenantId(), normalized.userId());
        LocalDateTime now = now();
        StaffProfileDO record = new StaffProfileDO();
        record.setStaffName(normalized.staffName());
        record.setMobile(normalized.mobile());
        record.setEmail(normalized.email());
        record.setEmploymentStatus(normalized.employmentStatus());
        record.setPrimaryOrganizationId(normalized.primaryOrganizationId());
        record.setPrimaryPositionId(normalized.primaryPositionId());
        record.setRecordOrigin(MANUAL);
        record.setRemark(normalized.remark());
        record.setVersion(normalized.version() + 1);
        record.setUpdatedAt(now);
        record.setUpdatedBy(actor.principalId());
        int updated = staffProfileMapper.update(record, Wrappers.<StaffProfileDO>lambdaUpdate()
                .eq(StaffProfileDO::getTenantId, actor.tenantId())
                .eq(StaffProfileDO::getId, id)
                .eq(StaffProfileDO::getVersion, normalized.version())
                .isNull(StaffProfileDO::getDeletedAt));
        if (updated != 1) throw new IllegalArgumentException("员工不存在或版本已变化");
        savePrimaryAssignment(actor, id, normalized.primaryOrganizationId(), normalized.primaryPositionId(), now);
        saveUserBinding(actor, id, normalized.userId(), now);
        audit(actor, "STAFF_UPDATE", "STAFF", id);
        StaffListRow updatedRow = staffManagementMapper.selectStaffById(actor.tenantId(), id);
        log.info("IAM人员修改完成 tenantId={} staffId={} staffCode={} organizationId={} positionId={} version={} actorId={}",
                actor.tenantId(), id, updatedRow.getStaffCode(), updatedRow.getPrimaryOrganizationId(),
                updatedRow.getPrimaryPositionId(), updatedRow.getVersion(), actor.principalId());
        return staffView(updatedRow);
    }

    @Override
    @Transactional
    public StaffSyncResultView syncDinghuobaoStaff(Actor actor, DhbStaffSyncRequest request) {
        requireTenantPermission(actor, "iam:staff:sync");
        List<DhbStaffRowCommand> rows = request == null ? List.of() : request.rows();
        if (rows.size() > SYNC_BATCH_LIMIT) {
            throw new IllegalArgumentException("单次同步员工数量不能超过" + SYNC_BATCH_LIMIT);
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        for (DhbStaffRowCommand row : rows) {
            try {
                SyncOutcome outcome = syncOneDhbStaff(actor, row);
                if (outcome == SyncOutcome.CREATED) created++;
                else if (outcome == SyncOutcome.UPDATED) updated++;
                else unchanged++;
            } catch (RuntimeException exception) {
                failed++;
                String sourceStaffId = row == null ? "" : String.valueOf(row.sourceStaffId());
                failures.add("sourceStaffId=" + sourceStaffId + ": " + exception.getMessage());
                log.warn("IAM订货宝员工同步单条失败 tenantId={} sourceStaffId={} reason={}",
                        actor.tenantId(), sourceStaffId, exception.getMessage());
            }
        }
        audit(actor, "STAFF_DHB_SYNC", "STAFF", null);
        log.info("IAM订货宝员工同步完成 tenantId={} received={} created={} updated={} unchanged={} failed={} actorId={}",
                actor.tenantId(), rows.size(), created, updated, unchanged, failed, actor.principalId());
        return new StaffSyncResultView(rows.size(), created, updated, unchanged, failed, failures);
    }

    @Override
    public List<StaffDisplayView> resolveStaffDisplay(Actor actor, StaffDisplayRequest request) {
        requireTenantPermission(actor, "iam:staff:read");
        List<String> staffCodes = request == null ? List.of() : request.staffCodes();
        if (staffCodes.isEmpty()) return List.of();
        return staffManagementMapper.selectStaffByCodes(actor.tenantId(), staffCodes)
                .stream()
                .map(row -> new StaffDisplayView(row.getId(), row.getStaffCode(), row.getStaffName(),
                        row.getEmploymentStatus(), row.getPrimaryOrganizationId(),
                        row.getPrimaryOrganizationName(), row.getPrimaryPositionId(),
                        row.getPrimaryPositionName()))
                .toList();
    }

    @Override
    public List<DhbStaffResolvedView> resolveDinghuobaoStaff(Actor actor, DhbStaffResolveRequest request) {
        requireTenantPermission(actor, "iam:staff:read");
        String sourceTenantKey = defaultText(request == null ? null : request.sourceTenantKey(),
                "DEFAULT", 128, "sourceTenantKey");
        List<String> sourceStaffIds = request == null ? List.of() : request.sourceStaffIds();
        List<String> sourceStaffNames = request == null ? List.of() : request.sourceStaffNames();
        if (sourceStaffIds.isEmpty() && sourceStaffNames.isEmpty()) return List.of();
        return staffManagementMapper.selectDhbStaffBySource(actor.tenantId(), sourceTenantKey,
                        sourceStaffIds, sourceStaffNames)
                .stream()
                .map(row -> new DhbStaffResolvedView(sourceTenantKey, row.getSourceStaffId(),
                        row.getId(), row.getStaffCode(), row.getStaffName(), row.getUserId(),
                        row.getUsername(), row.getUserDisplayName(), row.getPrimaryOrganizationId(),
                        row.getPrimaryOrganizationName(), row.getPrimaryPositionId(),
                        row.getPrimaryPositionName(), row.getEmploymentStatus(), row.getSourceStaffType(),
                        row.getSourceRole(), row.getSourcePresence(), toInstant(row.getLastSeenAt())))
                .toList();
    }

    private SyncOutcome syncOneDhbStaff(Actor actor, DhbStaffRowCommand row) {
        if (row == null) throw new IllegalArgumentException("员工行不能为空");
        String sourceStaffId = required(row.sourceStaffId(), 128, "sourceStaffId");
        String sourceTenantKey = defaultText(row.sourceTenantKey(), "DEFAULT", 128, "sourceTenantKey");
        ExternalStaffBindingDO binding = externalStaffBindingMapper.selectOne(Wrappers.<ExternalStaffBindingDO>lambdaQuery()
                .eq(ExternalStaffBindingDO::getTenantId, actor.tenantId())
                .eq(ExternalStaffBindingDO::getSourceSystem, DINGHUOBAO)
                .eq(ExternalStaffBindingDO::getSourceTenantKey, sourceTenantKey)
                .eq(ExternalStaffBindingDO::getSourceStaffId, sourceStaffId)
                .isNull(ExternalStaffBindingDO::getDeletedAt)
                .last("LIMIT 1"));
        LocalDateTime now = now();
        boolean created = false;
        UUID staffId;
        if (binding == null) {
            StaffProfileDO staff = new StaffProfileDO();
            staff.setId(ids.nextId());
            staff.setTenantId(actor.tenantId());
            staff.setStaffCode(nextStaffCode(actor.tenantId()));
            staff.setStaffName(defaultText(row.staffName(), defaultText(row.accountsName(), "未命名员工", 128, "staffName"),
                    128, "staffName"));
            staff.setMobile(firstText(row.mobile(), row.accountsMobile(), 32));
            staff.setEmail(text(row.email(), 128, "email"));
            staff.setEmploymentStatus(statusFromDhb(row.status()));
            staff.setRecordOrigin(DINGHUOBAO);
            staff.setRemark(text(row.about(), 500, "about"));
            staff.setVersion(0);
            staff.setCreatedAt(now);
            staff.setCreatedBy(actor.principalId());
            staff.setUpdatedAt(now);
            staff.setUpdatedBy(actor.principalId());
            staffProfileMapper.insert(staff);
            staffId = staff.getId();
            binding = new ExternalStaffBindingDO();
            binding.setId(ids.nextId());
            binding.setTenantId(actor.tenantId());
            binding.setStaffId(staffId);
            binding.setSourceSystem(DINGHUOBAO);
            binding.setSourceTenantKey(sourceTenantKey);
            binding.setSourceStaffId(sourceStaffId);
            binding.setVersion(0);
            binding.setCreatedAt(now);
            binding.setCreatedBy(actor.principalId());
            created = true;
        } else {
            staffId = binding.getStaffId();
            StaffProfileDO staff = staffById(actor.tenantId(), staffId);
            if (DINGHUOBAO.equals(staff.getRecordOrigin())) {
                StaffProfileDO update = new StaffProfileDO();
                update.setStaffName(defaultText(row.staffName(), defaultText(row.accountsName(), staff.getStaffName(),
                        128, "staffName"), 128, "staffName"));
                update.setMobile(firstText(row.mobile(), row.accountsMobile(), 32));
                update.setEmail(text(row.email(), 128, "email"));
                update.setEmploymentStatus(statusFromDhb(row.status()));
                update.setRemark(text(row.about(), 500, "about"));
                update.setVersion(staff.getVersion() + 1);
                update.setUpdatedAt(now);
                update.setUpdatedBy(actor.principalId());
                staffProfileMapper.update(update, Wrappers.<StaffProfileDO>lambdaUpdate()
                        .eq(StaffProfileDO::getTenantId, actor.tenantId())
                        .eq(StaffProfileDO::getId, staffId)
                        .eq(StaffProfileDO::getVersion, staff.getVersion())
                        .isNull(StaffProfileDO::getDeletedAt));
            }
        }
        boolean samePayload = row.sourcePayloadHash() != null
                && row.sourcePayloadHash().equals(binding.getSourcePayloadHash());
        fillBinding(binding, row, sourceTenantKey, sourceStaffId, now, actor.principalId());
        if (created) {
            externalStaffBindingMapper.insert(binding);
            return SyncOutcome.CREATED;
        }
        externalStaffBindingMapper.update(binding, Wrappers.<ExternalStaffBindingDO>lambdaUpdate()
                .eq(ExternalStaffBindingDO::getTenantId, actor.tenantId())
                .eq(ExternalStaffBindingDO::getId, binding.getId())
                .isNull(ExternalStaffBindingDO::getDeletedAt));
        return samePayload ? SyncOutcome.UNCHANGED : SyncOutcome.UPDATED;
    }

    private void fillBinding(ExternalStaffBindingDO binding, DhbStaffRowCommand row, String sourceTenantKey,
                             String sourceStaffId, LocalDateTime now, UUID actorId) {
        binding.setConnectorId(row.connectorId());
        binding.setSourceSystem(DINGHUOBAO);
        binding.setSourceTenantKey(sourceTenantKey);
        binding.setSourceStaffId(sourceStaffId);
        binding.setSourceStaffType(text(row.staffType(), 32, "staffType"));
        binding.setSourceAccountName(text(row.accountsName(), 128, "accountsName"));
        binding.setSourceStaffName(text(row.staffName(), 128, "staffName"));
        binding.setSourceTitle(text(row.title(), 128, "title"));
        binding.setSourceBranchName(text(row.branchName(), 128, "branchName"));
        binding.setSourceAccountsMobile(text(row.accountsMobile(), 32, "accountsMobile"));
        binding.setSourceAbout(text(row.about(), 500, "about"));
        binding.setSourceRole(text(row.role(), 128, "role"));
        binding.setSourceInviteCode(text(row.inviteCode(), 64, "inviteCode"));
        binding.setSourceMobile(text(row.mobile(), 32, "mobile"));
        binding.setSourceEmail(text(row.email(), 128, "email"));
        binding.setSourceQq(text(row.qq(), 64, "qq"));
        binding.setSourceStatus(text(row.status(), 32, "status"));
        binding.setSourcePayloadHash(text(row.sourcePayloadHash(), 128, "sourcePayloadHash"));
        binding.setSourcePayloadJson(jsonOrNull(row.sourcePayloadJson()));
        binding.setSourceCreatedAt(toLocal(row.createDate()));
        binding.setSourceUpdatedAt(toLocal(row.updateDate()));
        binding.setSourcePresence(PRESENT);
        binding.setLastSeenAt(now);
        binding.setUpdatedAt(now);
        binding.setUpdatedBy(actorId);
        binding.setVersion(binding.getVersion() + 1);
    }

    private void savePrimaryAssignment(Actor actor, UUID staffId, UUID organizationId,
                                       UUID positionId, LocalDateTime now) {
        staffAssignmentMapper.update(null, Wrappers.<StaffAssignmentDO>lambdaUpdate()
                .set(StaffAssignmentDO::getStatus, INACTIVE)
                .set(StaffAssignmentDO::getEffectiveTo, now)
                .set(StaffAssignmentDO::getUpdatedAt, now)
                .set(StaffAssignmentDO::getUpdatedBy, actor.principalId())
                .eq(StaffAssignmentDO::getTenantId, actor.tenantId())
                .eq(StaffAssignmentDO::getStaffId, staffId)
                .eq(StaffAssignmentDO::getAssignmentType, PRIMARY)
                .eq(StaffAssignmentDO::getStatus, ACTIVE)
                .isNull(StaffAssignmentDO::getDeletedAt));
        StaffAssignmentDO assignment = new StaffAssignmentDO();
        assignment.setId(ids.nextId());
        assignment.setTenantId(actor.tenantId());
        assignment.setStaffId(staffId);
        assignment.setOrganizationId(organizationId);
        assignment.setPositionId(positionId);
        assignment.setAssignmentType(PRIMARY);
        assignment.setStatus(ACTIVE);
        assignment.setEffectiveFrom(now);
        assignment.setVersion(0);
        assignment.setCreatedAt(now);
        assignment.setCreatedBy(actor.principalId());
        assignment.setUpdatedAt(now);
        assignment.setUpdatedBy(actor.principalId());
        staffAssignmentMapper.insert(assignment);
    }

    private void saveUserBinding(Actor actor, UUID staffId, UUID userId, LocalDateTime now) {
        StaffUserBindingDO existing = staffUserBindingMapper.selectOne(Wrappers.<StaffUserBindingDO>lambdaQuery()
                .eq(StaffUserBindingDO::getTenantId, actor.tenantId())
                .eq(StaffUserBindingDO::getStaffId, staffId)
                .isNull(StaffUserBindingDO::getDeletedAt)
                .last("LIMIT 1"));
        if (userId == null) {
            if (existing != null && ACTIVE.equals(existing.getStatus())) {
                StaffUserBindingDO update = new StaffUserBindingDO();
                update.setStatus(INACTIVE);
                update.setUpdatedAt(now);
                update.setUpdatedBy(actor.principalId());
                update.setVersion(existing.getVersion() + 1);
                staffUserBindingMapper.update(update, Wrappers.<StaffUserBindingDO>lambdaUpdate()
                        .eq(StaffUserBindingDO::getTenantId, actor.tenantId())
                        .eq(StaffUserBindingDO::getId, existing.getId()));
            }
            return;
        }
        if (existing == null) {
            StaffUserBindingDO binding = new StaffUserBindingDO();
            binding.setId(ids.nextId());
            binding.setTenantId(actor.tenantId());
            binding.setStaffId(staffId);
            binding.setUserId(userId);
            binding.setStatus(ACTIVE);
            binding.setBoundAt(now);
            binding.setVersion(0);
            binding.setCreatedAt(now);
            binding.setCreatedBy(actor.principalId());
            binding.setUpdatedAt(now);
            binding.setUpdatedBy(actor.principalId());
            staffUserBindingMapper.insert(binding);
            return;
        }
        StaffUserBindingDO update = new StaffUserBindingDO();
        update.setUserId(userId);
        update.setStatus(ACTIVE);
        update.setBoundAt(existing.getBoundAt() == null ? now : existing.getBoundAt());
        update.setUpdatedAt(now);
        update.setUpdatedBy(actor.principalId());
        update.setVersion(existing.getVersion() + 1);
        staffUserBindingMapper.update(update, Wrappers.<StaffUserBindingDO>lambdaUpdate()
                .eq(StaffUserBindingDO::getTenantId, actor.tenantId())
                .eq(StaffUserBindingDO::getId, existing.getId())
                .isNull(StaffUserBindingDO::getDeletedAt));
    }

    private void requireTenantPermission(Actor actor, String permission) {
        if ("SERVICE".equals(actor.scope())) return;
        if (!"TENANT".equals(actor.scope())
                || staffManagementMapper.countTenantPermission(actor.tenantId(), actor.principalId(), permission) < 1) {
            throw new AccessDeniedException("Permission denied: " + permission);
        }
    }

    private void validateOrganization(UUID tenantId, UUID id) {
        if (id == null) throw new IllegalArgumentException("主组织不能为空");
        if (staffManagementMapper.countOrganization(tenantId, id) != 1) {
            throw new AccessDeniedException("组织不属于当前租户或已停用");
        }
    }

    private void validatePosition(UUID tenantId, UUID id) {
        if (id == null) throw new IllegalArgumentException("主岗位不能为空");
        if (staffManagementMapper.countPosition(tenantId, id) != 1) {
            throw new AccessDeniedException("岗位不属于当前租户或已停用");
        }
    }

    private void validateUser(UUID tenantId, UUID id) {
        if (id != null && staffManagementMapper.countUser(tenantId, id) != 1) {
            throw new AccessDeniedException("账号不属于当前租户");
        }
    }

    private String nextPositionCode(UUID tenantId) {
        return codeGenerator.generateUnique(IamBusinessCodeRules.POSITION,
                candidate -> positionMapper.selectCount(Wrappers.<PositionDO>lambdaQuery()
                        .eq(PositionDO::getTenantId, tenantId)
                        .eq(PositionDO::getPositionCode, candidate)) == 0);
    }

    private String nextStaffCode(UUID tenantId) {
        return codeGenerator.generateUnique(IamBusinessCodeRules.STAFF,
                candidate -> staffProfileMapper.selectCount(Wrappers.<StaffProfileDO>lambdaQuery()
                        .eq(StaffProfileDO::getTenantId, tenantId)
                        .eq(StaffProfileDO::getStaffCode, candidate)) == 0);
    }

    private PositionDO positionById(UUID tenantId, UUID id) {
        PositionDO record = positionMapper.selectOne(Wrappers.<PositionDO>lambdaQuery()
                .eq(PositionDO::getTenantId, tenantId)
                .eq(PositionDO::getId, id)
                .isNull(PositionDO::getDeletedAt)
                .last("LIMIT 1"));
        if (record == null) throw new AccessDeniedException("岗位不属于当前租户");
        return record;
    }

    private StaffProfileDO staffById(UUID tenantId, UUID id) {
        StaffProfileDO record = staffProfileMapper.selectOne(Wrappers.<StaffProfileDO>lambdaQuery()
                .eq(StaffProfileDO::getTenantId, tenantId)
                .eq(StaffProfileDO::getId, id)
                .isNull(StaffProfileDO::getDeletedAt)
                .last("LIMIT 1"));
        if (record == null) throw new AccessDeniedException("员工不属于当前租户");
        return record;
    }

    private void audit(Actor actor, String action, String targetType, UUID targetId) {
        staffManagementMapper.insertAudit(ids.nextId(), actor.tenantId(), actor.scope(), actor.principalId(),
                action, targetType, targetId, ids.nextId());
    }

    private static PositionCommand normalizePosition(PositionCommand command, boolean update) {
        if (command == null) throw new IllegalArgumentException("岗位参数不能为空");
        if (update && command.version() < 0) throw new IllegalArgumentException("岗位版本无效");
        return new PositionCommand(
                required(command.name(), 128, "positionName"),
                text(command.description(), 500, "description"),
                Math.max(0, command.sortOrder()),
                enumValue(command.status(), POSITION_STATUSES, ACTIVE, "status"),
                command.version());
    }

    private static StaffCommand normalizeStaff(StaffCommand command, boolean update, boolean manual) {
        if (command == null) throw new IllegalArgumentException("员工参数不能为空");
        if (update && command.version() < 0) throw new IllegalArgumentException("员工版本无效");
        return new StaffCommand(
                required(command.staffName(), 128, "staffName"),
                text(command.mobile(), 32, "mobile"),
                text(command.email(), 128, "email"),
                enumValue(command.employmentStatus(), STAFF_STATUSES, ACTIVE, "employmentStatus"),
                manual ? requireUuid(command.primaryOrganizationId(), "primaryOrganizationId")
                        : command.primaryOrganizationId(),
                manual ? requireUuid(command.primaryPositionId(), "primaryPositionId")
                        : command.primaryPositionId(),
                command.userId(),
                text(command.remark(), 500, "remark"),
                command.version());
    }

    private static UUID requireUuid(UUID value, String field) {
        if (value == null) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }

    private static PositionView positionView(PositionDO record) {
        return new PositionView(record.getId(), record.getPositionCode(), record.getPositionName(),
                record.getDescription(), record.getSortOrder(), record.getStatus(), record.getVersion());
    }

    private static StaffView staffView(StaffListRow row) {
        if (row == null) throw new AccessDeniedException("员工不属于当前租户");
        return new StaffView(row.getId(), row.getStaffCode(), row.getStaffName(), row.getMobile(), row.getEmail(),
                row.getEmploymentStatus(), row.getPrimaryOrganizationId(), row.getPrimaryOrganizationName(),
                row.getPrimaryPositionId(), row.getPrimaryPositionName(), row.getUserId(), row.getUsername(),
                row.getUserDisplayName(), row.getRecordOrigin(), row.getRemark(), row.getSourceSystem(),
                row.getSourceStaffId(), row.getSourceStaffType(), row.getSourceAccountName(), row.getSourceTitle(),
                row.getSourceBranchName(), row.getSourceRole(), row.getSourceStatus(), row.getSourcePresence(),
                toInstant(row.getLastSeenAt()), row.getVersion());
    }

    private static String statusFromDhb(String value) {
        String normalized = text(value, 32, "status");
        if ("F".equalsIgnoreCase(normalized) || DISABLED.equalsIgnoreCase(normalized)) return DISABLED;
        return ACTIVE;
    }

    private static String optionalEnum(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) return null;
        return enumValue(value, allowed, null, field);
    }

    private static String enumValue(String value, Set<String> allowed, String defaultValue, String field) {
        String normalized = value == null || value.isBlank()
                ? defaultValue
                : value.strip().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }

    private static String required(String value, int max, String field) {
        String normalized = text(value, max, field);
        if (normalized == null) throw new IllegalArgumentException(field + "不能为空");
        return normalized;
    }

    private static String defaultText(String value, String defaultValue, int max, String field) {
        String normalized = text(value, max, field);
        return normalized == null ? defaultValue : normalized;
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + "长度不能超过" + max);
        }
        return normalized;
    }

    private static String firstText(String first, String second, int max) {
        String value = text(first, max, "mobile");
        return value == null ? text(second, max, "accountsMobile") : value;
    }

    private static String jsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (!(normalized.startsWith("{") || normalized.startsWith("["))) return null;
        return normalized;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }

    private enum SyncOutcome {
        CREATED, UPDATED, UNCHANGED
    }
}

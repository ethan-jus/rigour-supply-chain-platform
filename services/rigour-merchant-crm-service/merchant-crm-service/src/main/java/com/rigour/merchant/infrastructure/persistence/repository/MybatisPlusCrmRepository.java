package com.rigour.merchant.infrastructure.persistence.repository;

import static com.rigour.merchant.application.service.CrmMasterDataSyncService.IAM_STAFF_BY_SOURCE_ID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.merchant.api.v1.model.AddressView;
import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.merchant.api.v1.model.SalesAssignmentView;
import com.rigour.merchant.api.v1.model.CustomerSourceView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.code.CrmBusinessCodeRules;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import com.rigour.merchant.infrastructure.persistence.entity.AddressEntity;
import com.rigour.merchant.infrastructure.persistence.entity.ContactEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CrmSyncCheckpointEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CrmSyncLockEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CrmSyncRunEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerAreaEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerPolicyEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerProfileEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerTypeEntity;
import com.rigour.merchant.infrastructure.persistence.entity.InternalCustomerEntity;
import com.rigour.merchant.infrastructure.persistence.entity.PartyEntity;
import com.rigour.merchant.infrastructure.persistence.entity.PartyRoleEntity;
import com.rigour.merchant.infrastructure.persistence.entity.SalesAssignmentEntity;
import com.rigour.merchant.infrastructure.persistence.entity.SourceBindingEntity;
import com.rigour.merchant.infrastructure.persistence.entity.SourceIdentityAliasEntity;
import com.rigour.merchant.infrastructure.persistence.mapper.AddressMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.ContactMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmQueryMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncCheckpointMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncLockMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncRunMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerAreaMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerPolicyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerProfileMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerTypeMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.InternalCustomerMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyRoleMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SalesAssignmentMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceBindingMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceIdentityAliasMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于 MyBatis-Plus 的 CRM 仓储。
 *
 * <p>同步写入以来源绑定为幂等入口：哈希未变且投影完整时不更新业务表；哈希变化、目标
 * 不存在或投影不完整时才创建、更新或修复。</p>
 */
public class MybatisPlusCrmRepository implements CrmMasterDataStore, CrmCustomerQueryStore {
    private static final String SOURCE_SYSTEM = ExternalSourceCodes.DOMAIN_DINGHUOBAO;
    private static final String INTEGRATION_SOURCE_SYSTEM = ExternalSourceCodes.INTEGRATION_DHB;
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String LEGACY_SYNC_ACTOR = "DHB_SYNC";
    private static final long RUN_LEASE_MINUTES = 15;
    private static final long RUN_STALE_MINUTES = 2;

    private final CustomerTypeMapper customerTypeMapper;
    private final CustomerAreaMapper customerAreaMapper;
    private final PartyMapper partyMapper;
    private final PartyRoleMapper partyRoleMapper;
    private final CustomerProfileMapper customerProfileMapper;
    private final CustomerPolicyMapper customerPolicyMapper;
    private final ContactMapper contactMapper;
    private final AddressMapper addressMapper;
    private final InternalCustomerMapper internalCustomerMapper;
    private final SalesAssignmentMapper assignmentMapper;
    private final CrmSyncRunMapper syncRunMapper;
    private final CrmSyncCheckpointMapper checkpointMapper;
    private final CrmSyncLockMapper lockMapper;
    private final SourceBindingMapper bindingMapper;
    private final SourceIdentityAliasMapper aliasMapper;
    private final CrmQueryMapper queryMapper;
    private final ObjectMapper objectMapper;
    private final BusinessCodeGenerator codeGenerator = new BusinessCodeGenerator();
    private final Clock clock;

    public MybatisPlusCrmRepository(
            CustomerTypeMapper customerTypeMapper, CustomerAreaMapper customerAreaMapper,
            PartyMapper partyMapper, PartyRoleMapper partyRoleMapper,
            CustomerProfileMapper customerProfileMapper, CustomerPolicyMapper customerPolicyMapper,
            ContactMapper contactMapper, AddressMapper addressMapper,
            InternalCustomerMapper internalCustomerMapper,
            SalesAssignmentMapper assignmentMapper,
            CrmSyncRunMapper syncRunMapper, CrmSyncCheckpointMapper checkpointMapper,
            CrmSyncLockMapper lockMapper, SourceBindingMapper bindingMapper,
            SourceIdentityAliasMapper aliasMapper, CrmQueryMapper queryMapper,
            ObjectMapper objectMapper, Clock clock) {
        this.customerTypeMapper = customerTypeMapper;
        this.customerAreaMapper = customerAreaMapper;
        this.partyMapper = partyMapper;
        this.partyRoleMapper = partyRoleMapper;
        this.customerProfileMapper = customerProfileMapper;
        this.customerPolicyMapper = customerPolicyMapper;
        this.contactMapper = contactMapper;
        this.addressMapper = addressMapper;
        this.internalCustomerMapper = internalCustomerMapper;
        this.assignmentMapper = assignmentMapper;
        this.syncRunMapper = syncRunMapper;
        this.checkpointMapper = checkpointMapper;
        this.lockMapper = lockMapper;
        this.bindingMapper = bindingMapper;
        this.aliasMapper = aliasMapper;
        this.queryMapper = queryMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID startRun(UUID tenantId, UUID connectorId, UUID actorId, UUID sourceTaskId,
                         CrmMasterDataObjectType objectType, int maxPages, String triggerType) {
        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusMinutes(RUN_STALE_MINUTES);
        syncRunMapper.recoverStaleRuns(bytes(tenantId), bytes(connectorId), objectType.name(),
                staleBefore, now);
        releaseRecoverableLocks(tenantId, connectorId, objectType, staleBefore, now);
        UUID runId = CrmUuidCodec.next();
        CrmSyncLockEntity lock = new CrmSyncLockEntity();
        lock.id = bytes(CrmUuidCodec.next()); lock.tenantId = bytes(tenantId);
        lock.connectorId = bytes(connectorId); lock.objectType = objectType.name();
        lock.runId = bytes(runId); lock.lockToken = UUID.randomUUID().toString();
        lock.acquiredAt = now; lock.expiresAt = now.plusMinutes(RUN_LEASE_MINUTES);
        lock.revision = 1; lock.createdBy = SYSTEM_ACTOR; lock.createdTime = now;
        lock.updatedBy = SYSTEM_ACTOR; lock.updatedTime = now; lock.deleted = 0;
        try {
            lockMapper.insert(lock);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "相同租户、连接器和数据类型已有同步任务运行中", List.of());
        }
        CrmSyncRunEntity run = new CrmSyncRunEntity();
        run.id = bytes(runId); run.tenantId = bytes(tenantId); run.connectorId = bytes(connectorId);
        run.sourceSystem = SOURCE_SYSTEM; run.objectType = objectType.name();
        run.triggerType = triggerType; run.sourceTaskId = bytes(sourceTaskId);
        run.syncMode = "FULL"; run.status = "RUNNING";
        run.pageSize = 500; run.maxPages = maxPages; run.fetchedCount = 0L;
        run.createdCount = 0L; run.changedCount = 0L; run.repairedCount = 0L;
        run.duplicateCount = 0L; run.absentCount = 0L; run.rejectedCount = 0L;
        run.startedAt = now; run.revision = 1; run.createdBy = auditActor(actorId);
        run.createdTime = now; run.updatedBy = SYSTEM_ACTOR; run.updatedTime = now; run.deleted = 0;
        syncRunMapper.insert(run);
        return runId;
    }

    @Override
    @Transactional
    public UUID recordSkippedRun(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                 CrmMasterDataObjectType objectType, int maxPages,
                                 String reasonCode, String reasonMessage) {
        return insertSkippedRun(tenantId, connectorId, sourceTaskId, objectType,
                maxPages, reasonCode, reasonMessage);
    }

    @Override
    @Transactional
    public List<UUID> recordSkippedRuns(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                        List<CrmMasterDataObjectType> objectTypes, int maxPages,
                                        String reasonCode, String reasonMessage) {
        if (objectTypes == null || objectTypes.isEmpty()) return List.of();
        return objectTypes.stream()
                .map(type -> insertSkippedRun(tenantId, connectorId, sourceTaskId,
                        Objects.requireNonNull(type, "objectType不能为空"), maxPages,
                        reasonCode, reasonMessage))
                .toList();
    }

    private UUID insertSkippedRun(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                  CrmMasterDataObjectType objectType, int maxPages,
                                  String reasonCode, String reasonMessage) {
        Objects.requireNonNull(sourceTaskId, "sourceTaskId不能为空");
        LocalDateTime now = now();
        UUID runId = CrmUuidCodec.next();
        CrmSyncRunEntity run = new CrmSyncRunEntity();
        run.id = bytes(runId); run.tenantId = bytes(tenantId); run.connectorId = bytes(connectorId);
        run.sourceSystem = SOURCE_SYSTEM; run.objectType = objectType.name();
        run.triggerType = "SCHEDULED"; run.sourceTaskId = bytes(sourceTaskId);
        run.syncMode = "FULL"; run.status = "SKIPPED";
        run.pageSize = 500; run.maxPages = maxPages; run.fetchedCount = 0L;
        run.createdCount = 0L; run.changedCount = 0L; run.repairedCount = 0L;
        run.duplicateCount = 0L; run.absentCount = 0L; run.rejectedCount = 0L;
        run.errorCode = safeCode(reasonCode); run.errorMessage = safeSkipMessage(reasonMessage);
        run.startedAt = now; run.finishedAt = now; run.revision = 1;
        run.createdBy = SYSTEM_ACTOR; run.createdTime = now;
        run.updatedBy = SYSTEM_ACTOR; run.updatedTime = now; run.deleted = 0;
        syncRunMapper.insert(run);
        return runId;
    }

    @Override
    @Transactional
    public ImportResult importRecord(UUID tenantId, UUID connectorId, UUID runId,
                                     CrmMasterDataObjectType type, SourceRecord record) {
        ImportResult result = importRecordInternal(tenantId, connectorId, runId, type, record);
        heartbeatRun(tenantId, connectorId, runId, type);
        return result;
    }

    @Override
    @Transactional
    public List<ImportResult> importRecords(UUID tenantId, UUID connectorId, UUID runId,
                                            CrmMasterDataObjectType type,
                                            List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        Map<String, SourceBindingEntity> existingBindings = bindings(tenantId, connectorId, type,
                records.stream().map(SourceRecord::sourceId).toList(), true);
        CustomerProjectionBatch customerProjectionBatch = type == CrmMasterDataObjectType.CUSTOMER
                ? customerProjectionBatch(tenantId, connectorId, existingBindings, records)
                : null;
        AddressProjectionBatch addressProjectionBatch = type == CrmMasterDataObjectType.ADDRESS
                ? addressProjectionBatch(tenantId, connectorId, existingBindings, records)
                : null;
        List<ImportResult> results = records.stream()
                .map(record -> importRecordInternal(tenantId, connectorId, runId, type,
                        record, existingBindings.get(record.sourceId()),
                        customerProjectionBatch, addressProjectionBatch))
                .toList();
        heartbeatRun(tenantId, connectorId, runId, type);
        return results;
    }

    private void heartbeatRun(UUID tenantId, UUID connectorId, UUID runId,
                              CrmMasterDataObjectType type) {
        LocalDateTime now = now();
        int liveRun = syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("id", bytes(runId)).eq("object_type", type.name())
                .eq("status", "RUNNING").set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now).setSql("revision=revision+1"));
        int liveLock = lockMapper.update(null, Wrappers.<CrmSyncLockEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("object_type", type.name()).eq("run_id", bytes(runId))
                .gt("expires_at", now)
                .set("expires_at", now.plusMinutes(RUN_LEASE_MINUTES))
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        if (liveRun != 1 || liveLock != 1) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "CRM同步运行所有权已失效，本批写入已回滚", List.of());
        }
    }

    private void releaseRecoverableLocks(UUID tenantId, UUID connectorId,
                                         CrmMasterDataObjectType type,
                                         LocalDateTime staleBefore,
                                         LocalDateTime now) {
        List<CrmSyncLockEntity> locks = lockMapper.selectList(Wrappers.<CrmSyncLockEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("object_type", type.name()));
        for (CrmSyncLockEntity lock : locks) {
            CrmSyncRunEntity run = syncRunMapper.selectOne(Wrappers.<CrmSyncRunEntity>query()
                    .eq("tenant_id", bytes(tenantId)).eq("id", lock.runId));
            boolean expired = lock.expiresAt == null || !lock.expiresAt.isAfter(now);
            boolean staleRunning = run != null && "RUNNING".equals(run.status)
                    && run.updatedTime != null && !run.updatedTime.isAfter(staleBefore);
            if (staleRunning) {
                syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", lock.runId)
                        .eq("status", "RUNNING")
                        .set("status", "FAILED")
                        .set("error_code", "STALE_RUN_RECOVERED")
                        .set("error_message", "同步运行超过心跳阈值，已在后续批次启动前终结")
                        .set("finished_at", now)
                        .set("updated_by", SYSTEM_ACTOR)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
            }
            if (expired || staleRunning || run == null || !"RUNNING".equals(run.status)) {
                lockMapper.delete(Wrappers.<CrmSyncLockEntity>query()
                        .eq("tenant_id", bytes(tenantId)).eq("id", lock.id)
                        .eq("run_id", lock.runId));
            }
        }
    }

    private ImportResult importRecordInternal(UUID tenantId, UUID connectorId, UUID runId,
                                              CrmMasterDataObjectType type, SourceRecord record) {
        return importRecordInternal(tenantId, connectorId, runId, type, record, null);
    }

    private ImportResult importRecordInternal(UUID tenantId, UUID connectorId, UUID runId,
                                              CrmMasterDataObjectType type, SourceRecord record,
                                              SourceBindingEntity existingBinding) {
        return importRecordInternal(tenantId, connectorId, runId, type, record, existingBinding, null, null);
    }

    private ImportResult importRecordInternal(UUID tenantId, UUID connectorId, UUID runId,
                                              CrmMasterDataObjectType type, SourceRecord record,
                                              SourceBindingEntity existingBinding,
                                              CustomerProjectionBatch customerProjectionBatch,
                                              AddressProjectionBatch addressProjectionBatch) {
        if (record == null || record.sourceId() == null || record.sourceId().isBlank()) {
            return ImportResult.rejectedOne();
        }
        LocalDateTime now = now();
        SourceBindingEntity binding = existingBinding == null
                ? binding(tenantId, connectorId, type, record.sourceId(), true)
                : existingBinding;
        SourceRecord snapshot = snapshot(binding, record);
        String json = json(storageSourceFields(snapshot.sourceFields()));
        String hash = sha256(json);
        boolean sourceChanged = binding == null || !hash.equals(binding.sourcePayloadHash);
        if (binding != null && hash.equals(binding.sourcePayloadHash)
                && "RESOLVED".equals(binding.bindingStatus) && binding.targetId != null
                && projectionComplete(tenantId, connectorId, type,
                uuid(binding.targetId), snapshot, customerProjectionBatch, addressProjectionBatch)) {
            markSeen(binding, runId, snapshot, now);
            return ImportResult.duplicateOne();
        }

        Target target = switch (type) {
            case CUSTOMER_TYPE -> customerType(tenantId, binding, snapshot, now);
            case CUSTOMER_AREA -> customerArea(tenantId, connectorId, binding, snapshot, now);
            case CUSTOMER -> customer(tenantId, connectorId, binding, snapshot, now, sourceChanged);
            case ADDRESS -> address(tenantId, connectorId, binding, snapshot, now,
                    sourceChanged, addressProjectionBatch);
        };
        boolean created = binding == null;
        if (binding == null) {
            binding = new SourceBindingEntity();
            binding.id = bytes(CrmUuidCodec.next()); binding.tenantId = bytes(tenantId);
            binding.connectorId = bytes(connectorId); binding.sourceSystem = SOURCE_SYSTEM;
            binding.sourceObjectType = type.name(); binding.sourceObjectId = record.sourceId();
            binding.revision = 0L; binding.createdBy = SYSTEM_ACTOR; binding.createdTime = now;
            binding.updatedBy = SYSTEM_ACTOR; binding.updatedTime = now; binding.deleted = 0;
        }
        boolean repaired = !created && (hash.equals(binding.sourcePayloadHash)
                || !"RESOLVED".equals(binding.bindingStatus)) && "RESOLVED".equals(target.status());
        saveBinding(binding, runId, snapshot, target, json, hash, now, created);
        aliases(tenantId, connectorId, binding, type, snapshot, now);
        if (!"RESOLVED".equals(target.status())) return ImportResult.unmappedOne();
        if (created) return ImportResult.createdOne();
        return repaired ? ImportResult.repairedOne() : ImportResult.changedOne();
    }

    private Target customerType(UUID tenantId, SourceBindingEntity binding,
                                SourceRecord record, LocalDateTime now) {
        UUID id = targetId(binding);
        CustomerTypeEntity entity = customerTypeMapper.selectOne(Wrappers.<CustomerTypeEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id)));
        String name = value(record.sourceFields(), "typeName", record.sourceName(), record.sourceId());
        if (entity == null) {
            entity = new CustomerTypeEntity(); entity.id = bytes(id); entity.tenantId = bytes(tenantId);
            entity.typeCode = uniqueCustomerTypeCode(tenantId, record.sourceCreatedAt());
            entity.typeName = name; entity.status = "ACTIVE";
            entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
            entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
            customerTypeMapper.insert(entity);
        } else if (record.sourceFields().containsKey("typeName")
                && name != null && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) {
            customerTypeMapper.update(null, Wrappers.<CustomerTypeEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id))
                    .set("type_name", name).set("status", "ACTIVE")
                    .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                    .setSql("revision=revision+1"));
        }
        return Target.resolved("CUSTOMER_TYPE", id);
    }

    private Target customerArea(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                                SourceRecord record, LocalDateTime now) {
        UUID id = targetId(binding);
        CustomerAreaEntity entity = customerAreaMapper.selectOne(Wrappers.<CustomerAreaEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id)));
        String name = value(record.sourceFields(), "AreaName", record.sourceName(), record.sourceId());
        String parentSourceId = areaParentCode(record.sourceFields());
        String parentAreaCode = customerAreaCode(tenantId,
                sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.CUSTOMER_AREA, parentSourceId));
        boolean parentFieldPresent = hasAreaParentField(record.sourceFields());
        if (entity == null) {
            entity = new CustomerAreaEntity(); entity.id = bytes(id); entity.tenantId = bytes(tenantId);
            entity.areaCode = uniqueCustomerAreaCode(tenantId, record.sourceCreatedAt());
            entity.areaName = name; entity.parentAreaCode = parentAreaCode;
            entity.status = "ACTIVE";
            entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
            entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
            customerAreaMapper.insert(entity);
        } else if ((record.sourceFields().containsKey("AreaName")
                || record.sourceFields().keySet().stream().anyMatch(key ->
                "parentID".equalsIgnoreCase(key) || "parent_id".equalsIgnoreCase(key)))
                && name != null && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) {
            var update = Wrappers.<CustomerAreaEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id))
                    .set("area_name", name)
                    .set("status", "ACTIVE")
                    .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                    .setSql("revision=revision+1");
            // getArea 文档未保证返回 parentID；缺失时保留已知父级，避免一次不完整响应破坏层级。
            if ((parentFieldPresent && parentAreaCode != null) || isInvalidAreaParent(entity.parentAreaCode)) {
                update.set("parent_area_code", parentAreaCode);
            }
            customerAreaMapper.update(null, update);
        }
        return Target.resolved("CUSTOMER_AREA", id);
    }

    private Target customer(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                            SourceRecord record, LocalDateTime now, boolean sourceChanged) {
        UUID partyId = targetId(binding);
        Map<String, Object> f = record.sourceFields();
        PartyEntity party = partyMapper.selectOne(Wrappers.<PartyEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(partyId)));
        if (party == null) {
            party = new PartyEntity(); party.id = bytes(partyId); party.tenantId = bytes(tenantId);
            party.partyCode = value(f, "clientNO", record.sourceCode(), record.sourceId());
            party.displayName = value(f, "clientCompanyName", record.sourceName(), party.partyCode);
            party.partyKind = "ORGANIZATION"; party.internalStatus = active(value(f, "clientStatus"));
            party.ownershipState = "EXTERNAL_PRIMARY"; party.recordOrigin = "IMPORTED";
            party.revision = 0L; party.createdBy = SYSTEM_ACTOR; party.createdTime = now;
            party.updatedBy = SYSTEM_ACTOR; party.updatedTime = now; party.deleted = 0; partyMapper.insert(party);
            PartyRoleEntity role = new PartyRoleEntity(); role.tenantId = bytes(tenantId);
            role.partyId = bytes(partyId); role.roleCode = "CUSTOMER"; role.status = "ACTIVE";
            role.effectiveFrom = now; role.revision = 1; role.createdBy = SYSTEM_ACTOR;
            role.createdTime = now; role.updatedBy = SYSTEM_ACTOR; role.updatedTime = now;
            role.deleted = 0; partyRoleMapper.insert(role);
        } else if (sourceChanged && !"INTERNAL_PRIMARY".equals(party.ownershipState)) {
            partyMapper.update(null, Wrappers.<PartyEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(partyId))
                    .set("party_code", incoming(f, "clientNO", party.partyCode))
                    .set("display_name", incoming(f, "clientCompanyName", party.displayName))
                    .set("internal_status", f.containsKey("clientStatus")
                            ? active(value(f, "clientStatus")) : party.internalStatus)
                    .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                    .setSql("revision=revision+1"));
        }
        UUID typeId = sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.CUSTOMER_TYPE,
                value(f, "clientType"));
        UUID areaId = sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.CUSTOMER_AREA,
                value(f, "clientArea"));
        upsertProfile(tenantId, partyId, typeId, areaId, f, now, sourceChanged);
        upsertPolicy(tenantId, partyId, f, now, sourceChanged);
        upsertPrimaryContact(tenantId, partyId, f, now, sourceChanged);
        upsertContactAddress(tenantId, partyId, f, now, sourceChanged);
        upsertAssignments(tenantId, connectorId, partyId, f, now, sourceChanged);
        upsertInternalCustomer(tenantId, partyId, record, f, typeId, areaId, now, sourceChanged);
        return Target.resolved("PARTY", partyId);
    }

    private void upsertInternalCustomer(UUID tenantId, UUID partyId, SourceRecord record,
                                        Map<String, Object> f, UUID typeId, UUID areaId, LocalDateTime now,
                                        boolean sourceChanged) {
        String tenant = tenantId.toString();
        String legacyCode = legacyInternalCustomerCode(record);
        InternalCustomerEntity existing = internalCustomerByPartyId(tenantId, partyId);
        String resolvedCustomerTypeCode = customerTypeCode(tenantId, typeId);
        String resolvedRegionCode = customerAreaCode(tenantId, areaId);
        if (existing == null) {
            existing = internalCustomerByCode(tenantId, legacyCode);
        }
        String customerTypeCode = first(resolvedCustomerTypeCode,
                existing == null ? null : existing.getCustomerTypeCode());
        String regionCode = first(resolvedRegionCode,
                existing == null ? null : existing.getRegionCode());
        if (existing == null) {
            InternalCustomerEntity entity = new InternalCustomerEntity();
            entity.setTenantId(tenant);
            entity.setCustomerCode(uniqueInternalCustomerCode(tenantId, record.sourceCreatedAt()));
            entity.setPartyId(bytes(partyId));
            applyInternalCustomer(entity, record, f, customerTypeCode, regionCode);
            entity.setRevision(1);
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYSTEM_ACTOR);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            internalCustomerMapper.insert(entity);
            return;
        }
        boolean syncOwned = sourceWritable(existing.getCreatedBy()) || sourceWritable(existing.getUpdatedBy());
        boolean repairCode = syncOwned && sourceCodeNeedsRepair(existing.getCustomerCode(), legacyCode);
        boolean repairParty = existing.getPartyId() == null || !Arrays.equals(existing.getPartyId(), bytes(partyId));
        boolean repairClassification = syncOwned && (
                (resolvedCustomerTypeCode != null
                        && !Objects.equals(existing.getCustomerTypeCode(), resolvedCustomerTypeCode))
                        || (resolvedRegionCode != null
                        && !Objects.equals(existing.getRegionCode(), resolvedRegionCode)));
        if (!sourceChanged && !repairCode && !repairParty && !repairClassification) return;
        if (!syncOwned && !repairParty) return;
        applyInternalCustomer(existing, record, f, customerTypeCode, regionCode);
        var update = Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getCustomerName, existing.getCustomerName())
                .set(InternalCustomerEntity::getPartyId, bytes(partyId))
                .set(InternalCustomerEntity::getContactName, existing.getContactName())
                .set(InternalCustomerEntity::getContactPhone, existing.getContactPhone())
                .set(InternalCustomerEntity::getCustomerTypeCode, existing.getCustomerTypeCode())
                .set(InternalCustomerEntity::getRegionCode, existing.getRegionCode())
                .set(InternalCustomerEntity::getOwnerSalesUserId, existing.getOwnerSalesUserId())
                .set(InternalCustomerEntity::getOwnerSalesName, existing.getOwnerSalesName())
                .set(InternalCustomerEntity::getOwnerStaffCode, existing.getOwnerStaffCode())
                .set(InternalCustomerEntity::getOwnerStaffNameSnapshot, existing.getOwnerStaffNameSnapshot())
                .set(InternalCustomerEntity::getAddress, existing.getAddress())
                .set(InternalCustomerEntity::getStatusCode, existing.getStatusCode())
                .set(InternalCustomerEntity::getRemark, existing.getRemark())
                .set(InternalCustomerEntity::getRevision, existing.getRevision() == null ? 1 : existing.getRevision() + 1)
                .set(InternalCustomerEntity::getUpdatedBy, SYSTEM_ACTOR)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .set(InternalCustomerEntity::getDeleted, 0)
                .eq(InternalCustomerEntity::getTenantId, tenant)
                .eq(InternalCustomerEntity::getId, existing.getId());
        if (repairCode) {
            update.set(InternalCustomerEntity::getCustomerCode,
                    uniqueInternalCustomerCode(tenantId, record.sourceCreatedAt()));
        }
        internalCustomerMapper.update(null, update);
    }

    private void applyInternalCustomer(InternalCustomerEntity entity, SourceRecord record,
                                       Map<String, Object> f, String customerTypeCode, String regionCode) {
        String code = first(entity.getCustomerCode(), legacyInternalCustomerCode(record));
        entity.setCustomerName(first(value(f, "clientCompanyName"), record.sourceName(), code));
        entity.setContactName(value(f, "clientTrueName"));
        entity.setContactPhone(value(f, "clientPhone"));
        entity.setCustomerTypeCode(customerTypeCode);
        entity.setRegionCode(regionCode);
        StaffRef primary = staffRefs(f).primary();
        IamStaffRef iamStaff = iamStaff(f, primary.sourceId(), primary.name());
        String ownerStaffName = first(iamStaff.staffName(), clean(primary.name()));
        entity.setOwnerSalesUserId(null);
        entity.setOwnerSalesName(ownerStaffName);
        entity.setOwnerStaffCode(iamStaff.staffCode());
        entity.setOwnerStaffNameSnapshot(ownerStaffName);
        entity.setAddress(value(f, "clientAdd"));
        entity.setStatusCode(active(value(f, "clientStatus")));
        entity.setRemark(value(f, "clientAbout"));
    }

    private String customerTypeCode(UUID tenantId, UUID typeId) {
        if (typeId == null) return null;
        CustomerTypeEntity entity = customerTypeMapper.selectOne(Wrappers.<CustomerTypeEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("id", bytes(typeId))
                .eq("deleted", 0)
                .last("LIMIT 1"));
        return entity == null ? null : entity.typeCode;
    }

    private String customerAreaCode(UUID tenantId, UUID areaId) {
        if (areaId == null) return null;
        CustomerAreaEntity entity = customerAreaMapper.selectOne(Wrappers.<CustomerAreaEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("id", bytes(areaId))
                .eq("deleted", 0)
                .last("LIMIT 1"));
        return entity == null ? null : entity.areaCode;
    }

    private Map<UUID, String> customerTypeCodes(UUID tenantId, Collection<UUID> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return Map.of();
        List<byte[]> ids = typeIds.stream().filter(Objects::nonNull)
                .distinct().map(MybatisPlusCrmRepository::bytes).toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> result = new LinkedHashMap<>();
        customerTypeMapper.selectList(Wrappers.<CustomerTypeEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .in("id", ids)
                        .eq("deleted", 0))
                .forEach(entity -> result.put(uuid(entity.id), entity.typeCode));
        return result;
    }

    private Map<UUID, String> customerAreaCodes(UUID tenantId, Collection<UUID> areaIds) {
        if (areaIds == null || areaIds.isEmpty()) return Map.of();
        List<byte[]> ids = areaIds.stream().filter(Objects::nonNull)
                .distinct().map(MybatisPlusCrmRepository::bytes).toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> result = new LinkedHashMap<>();
        customerAreaMapper.selectList(Wrappers.<CustomerAreaEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .in("id", ids)
                        .eq("deleted", 0))
                .forEach(entity -> result.put(uuid(entity.id), entity.areaCode));
        return result;
    }

    private static boolean internalCustomerClassificationComplete(InternalCustomerEntity entity,
                                                                  String expectedCustomerTypeCode,
                                                                  String expectedRegionCode,
                                                                  boolean customerTypeExpected,
                                                                  boolean regionExpected) {
        if (entity == null) return false;
        if (customerTypeExpected
                && (expectedCustomerTypeCode == null
                || !Objects.equals(entity.getCustomerTypeCode(), expectedCustomerTypeCode))) {
            return false;
        }
        return !regionExpected
                || (expectedRegionCode != null
                && Objects.equals(entity.getRegionCode(), expectedRegionCode));
    }

    private InternalCustomerEntity internalCustomerByPartyId(UUID tenantId, UUID partyId) {
        if (partyId == null) return null;
        return internalCustomerMapper.selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                .eq(InternalCustomerEntity::getPartyId, bytes(partyId))
                .last("LIMIT 1"));
    }

    private InternalCustomerEntity internalCustomerByCode(UUID tenantId, String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return null;
        return internalCustomerMapper.selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                .eq(InternalCustomerEntity::getCustomerCode, customerCode)
                .last("LIMIT 1"));
    }

    private String uniqueInternalCustomerCode(UUID tenantId) {
        return uniqueInternalCustomerCode(tenantId, null);
    }

    private String uniqueInternalCustomerCode(UUID tenantId, Instant businessTime) {
        return codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER,
                businessTime,
                candidate -> internalCustomerMapper.selectCount(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                        .eq(InternalCustomerEntity::getCustomerCode, candidate)) == 0);
    }

    private String uniqueCustomerTypeCode(UUID tenantId) {
        return uniqueCustomerTypeCode(tenantId, null);
    }

    private String uniqueCustomerTypeCode(UUID tenantId, Instant businessTime) {
        return codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER_TYPE,
                businessTime,
                candidate -> customerTypeMapper.selectCount(Wrappers.<CustomerTypeEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("type_code", candidate)) == 0);
    }

    private String uniqueCustomerAreaCode(UUID tenantId) {
        return uniqueCustomerAreaCode(tenantId, null);
    }

    private String uniqueCustomerAreaCode(UUID tenantId, Instant businessTime) {
        return codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER_AREA,
                businessTime,
                candidate -> customerAreaMapper.selectCount(Wrappers.<CustomerAreaEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("area_code", candidate)) == 0);
    }

    private static String legacyInternalCustomerCode(SourceRecord record) {
        String preferred = first(record.sourceCode(), value(record.sourceFields(), "clientNO"),
                record.sourceId());
        if (preferred == null || preferred.isBlank()) {
            preferred = "DHB-CUST-" + shortHash(record.sourceId());
        }
        String normalized = preferred.strip();
        return normalized.length() <= 50 ? normalized : "DHB-CUST-" + shortHash(normalized);
    }

    private static boolean sourceCodeNeedsRepair(String currentCode, String sourceCode) {
        if (currentCode == null || currentCode.isBlank()) return true;
        if (currentCode.startsWith("DHB-CUST-")) return true;
        return sourceCode != null && Objects.equals(currentCode.strip(), sourceCode.strip());
    }

    private static boolean sourceWritable(String actor) {
        return actor == null || actor.isBlank()
                || SYSTEM_ACTOR.equals(actor) || LEGACY_SYNC_ACTOR.equals(actor);
    }

    private Target address(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                           SourceRecord record, LocalDateTime now, boolean sourceChanged,
                           AddressProjectionBatch addressProjectionBatch) {
        Map<String, Object> f = record.sourceFields();
        UUID addressId = targetId(binding);
        AddressEntity address = addressMapper.selectOne(Wrappers.<AddressEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(addressId)));
        UUID partyId = addressProjectionBatch != null
                ? addressProjectionBatch.target(f)
                : customerTarget(tenantId, connectorId,
                value(f, "clientGuid"), value(f, "clientNum"), value(f, "clientId"));
        if (partyId == null && address != null && address.partyId != null) {
            partyId = uuid(address.partyId);
        }
        if (partyId == null) return Target.unresolved("CUSTOMER_NOT_RESOLVED",
                "收货地址完整来源字段已保存，等待客户GUID或编号完成解析");
        if (address == null) {
            ContactEntity contact = new ContactEntity(); contact.id = bytes(CrmUuidCodec.next());
            contact.tenantId = bytes(tenantId); contact.partyId = bytes(partyId);
            contact.contactType = "SHIPPING"; contact.contactName = value(f, "contact");
            contact.phone = value(f, "phone"); contact.isPrimary = bool(value(f, "isDefault"));
            contact.status = "ACTIVE"; contact.ownershipState = "EXTERNAL_PRIMARY";
            contact.recordOrigin = "IMPORTED"; contact.revision = 0L; contact.createdBy = SYSTEM_ACTOR;
            contact.createdTime = now; contact.updatedBy = SYSTEM_ACTOR;
            contact.updatedTime = now; contact.deleted = 0; contactMapper.insert(contact);
            address = new AddressEntity(); address.id = bytes(addressId); address.tenantId = bytes(tenantId);
            address.partyId = bytes(partyId); address.contactId = contact.id; address.addressType = "SHIPPING";
            address.consignee = value(f, "consignee"); address.regionText = value(f, "address");
            address.areaName = value(f, "areaName"); address.addressDetail = value(f, "addressDetail");
            address.fullAddress = fullAddress(f); address.isDefault = bool(value(f, "isDefault"));
            address.status = "ACTIVE"; address.ownershipState = "EXTERNAL_PRIMARY";
            address.recordOrigin = "IMPORTED"; address.revision = 0L; address.createdBy = SYSTEM_ACTOR;
            address.createdTime = now; address.updatedBy = SYSTEM_ACTOR;
            address.updatedTime = now; address.deleted = 0; addressMapper.insert(address);
        } else if (!"INTERNAL_PRIMARY".equals(address.ownershipState)) {
            boolean relationshipChanged = !Arrays.equals(address.partyId, bytes(partyId));
            ContactEntity contact = address.contactId == null ? null
                    : contactMapper.selectOne(Wrappers.<ContactEntity>query()
                    .eq("tenant_id", bytes(tenantId)).eq("id", address.contactId));
            if (contact == null) {
                contact = new ContactEntity(); contact.id = bytes(CrmUuidCodec.next());
                contact.tenantId = bytes(tenantId); contact.partyId = bytes(partyId);
                contact.contactType = "SHIPPING"; contact.contactName = value(f, "contact");
                contact.phone = value(f, "phone"); contact.isPrimary = bool(value(f, "isDefault"));
                contact.status = "ACTIVE"; contact.ownershipState = "EXTERNAL_PRIMARY";
                contact.recordOrigin = "IMPORTED"; contact.revision = 0L; contact.createdBy = SYSTEM_ACTOR;
                contact.createdTime = now; contact.updatedBy = SYSTEM_ACTOR;
                contact.updatedTime = now; contact.deleted = 0; contactMapper.insert(contact);
                addressMapper.update(null, Wrappers.<AddressEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", bytes(addressId))
                        .set("contact_id", contact.id)
                        .set("updated_by", SYSTEM_ACTOR)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
            } else if ((sourceChanged || relationshipChanged)
                    && !"INTERNAL_PRIMARY".equals(contact.ownershipState)) {
                contactMapper.update(null, Wrappers.<ContactEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", contact.id)
                        .set("party_id", bytes(partyId))
                        .set("contact_name", incoming(f, "contact", contact.contactName))
                        .set("phone", incoming(f, "phone", contact.phone))
                        .set("is_primary", f.containsKey("isDefault")
                                ? bool(value(f, "isDefault")) : contact.isPrimary)
                        .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                        .setSql("revision=revision+1"));
            }
            String region = incoming(f, "address", address.regionText);
            String detail = incoming(f, "addressDetail", address.addressDetail);
            if (sourceChanged || relationshipChanged) addressMapper.update(null, Wrappers.<AddressEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(addressId))
                    .set("party_id", bytes(partyId))
                    .set("consignee", incoming(f, "consignee", address.consignee))
                    .set("region_text", region).set("area_name", incoming(f, "areaName", address.areaName))
                    .set("address_detail", detail).set("full_address", fullAddress(region, detail))
                    .set("is_default", f.containsKey("isDefault")
                            ? bool(value(f, "isDefault")) : address.isDefault)
                    .set("status", "ACTIVE")
                    .set("updated_by", SYSTEM_ACTOR)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
        }
        return Target.resolved("ADDRESS", addressId);
    }

    private void upsertProfile(UUID tenantId, UUID partyId, UUID typeId, UUID areaId,
                               Map<String, Object> f, LocalDateTime now, boolean sourceChanged) {
        CustomerProfileEntity existing = customerProfileMapper.selectOne(Wrappers.<CustomerProfileEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId)));
        if (existing == null) {
            CustomerProfileEntity entity = new CustomerProfileEntity(); entity.partyId = bytes(partyId);
            entity.tenantId = bytes(tenantId); entity.customerTypeId = bytes(typeId);
            entity.customerAreaId = bytes(areaId); entity.loginAccount = value(f, "clientAccount");
            entity.customerTypeNameSnapshot = value(f, "clientTypeName");
            entity.customerAreaNameSnapshot = value(f, "clientAreaName"); entity.cityText = value(f, "clientCity");
            entity.inviterName = value(f, "Inviter"); entity.remark = value(f, "clientAbout");
            entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
            entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
            customerProfileMapper.insert(entity);
        } else if (sourceChanged) {
            customerProfileMapper.update(null, Wrappers.<CustomerProfileEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                    .set("customer_type_id", f.containsKey("clientType")
                            ? bytes(typeId) : existing.customerTypeId)
                    .set("customer_area_id", f.containsKey("clientArea")
                            ? bytes(areaId) : existing.customerAreaId)
                    .set("login_account", incoming(f, "clientAccount", existing.loginAccount))
                    .set("customer_type_name_snapshot", incoming(f, "clientTypeName",
                            existing.customerTypeNameSnapshot))
                    .set("customer_area_name_snapshot", incoming(f, "clientAreaName",
                            existing.customerAreaNameSnapshot))
                    .set("city_text", incoming(f, "clientCity", existing.cityText))
                    .set("inviter_name", incoming(f, "Inviter", existing.inviterName))
                    .set("remark", incoming(f, "clientAbout", existing.remark))
                    .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                    .setSql("revision=revision+1"));
        } else {
            var repair = Wrappers.<CustomerProfileEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId));
            boolean required = false;
            if (typeId != null && !Arrays.equals(existing.customerTypeId, bytes(typeId))) {
                repair.set("customer_type_id", bytes(typeId)); required = true;
            }
            if (areaId != null && !Arrays.equals(existing.customerAreaId, bytes(areaId))) {
                repair.set("customer_area_id", bytes(areaId)); required = true;
            }
            if (required) customerProfileMapper.update(null,
                    repair.set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                            .setSql("revision=revision+1"));
        }
    }

    private void upsertPolicy(UUID tenantId, UUID partyId, Map<String, Object> f,
                              LocalDateTime now, boolean sourceChanged) {
        CustomerPolicyEntity entity = customerPolicyMapper.selectOne(Wrappers.<CustomerPolicyEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId)));
        String sourceSettlement = value(f, "clientClearingForm");
        String settlement = sourceSettlement != null && sourceSettlement.length() <= 24
                ? sourceSettlement : null;
        boolean create = entity == null;
        if (create) {
            entity = new CustomerPolicyEntity(); entity.id = bytes(CrmUuidCodec.next()); entity.tenantId = bytes(tenantId);
            entity.partyId = bytes(partyId); entity.settlementMode = settlement; entity.currency = "CNY";
            entity.status = "ACTIVE"; entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
            entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
            customerPolicyMapper.insert(entity);
        } else if (sourceChanged && f.containsKey("clientClearingForm")
                && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) customerPolicyMapper.update(null,
                Wrappers.<CustomerPolicyEntity>update().eq("tenant_id", bytes(tenantId))
                        .eq("party_id", bytes(partyId))
                        .set("settlement_mode", settlement)
                        .set("updated_by", SYSTEM_ACTOR).set("updated_time", now)
                        .setSql("revision=revision+1"));
    }

    private void upsertPrimaryContact(UUID tenantId, UUID partyId, Map<String, Object> f,
                                      LocalDateTime now, boolean sourceChanged) {
        ContactEntity entity = contactMapper.selectOne(Wrappers.<ContactEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("contact_type", "PRIMARY").eq("record_origin", "IMPORTED").last("LIMIT 1"));
        boolean create = entity == null;
        if (create) {
            entity = new ContactEntity(); entity.id = bytes(CrmUuidCodec.next()); entity.tenantId = bytes(tenantId);
            entity.partyId = bytes(partyId); entity.contactType = "PRIMARY"; entity.isPrimary = true;
            entity.status = "ACTIVE"; entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
            entity.deleted = 0;
        }
        entity.contactName = incoming(f, "clientTrueName", entity.contactName);
        entity.phone = incoming(f, "clientPhone", entity.phone);
        entity.email = incoming(f, "clientEmail", entity.email);
        entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now;
        if (create) contactMapper.insert(entity);
        else if (sourceChanged && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) contactMapper.update(null,
                Wrappers.<ContactEntity>update().eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                        .set("contact_name", entity.contactName).set("phone", entity.phone)
                        .set("email", entity.email)
                        .set("updated_by", SYSTEM_ACTOR)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
    }

    private void upsertContactAddress(UUID tenantId, UUID partyId, Map<String, Object> f,
                                      LocalDateTime now, boolean sourceChanged) {
        AddressEntity entity = addressMapper.selectOne(Wrappers.<AddressEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("address_type", "CONTACT").eq("record_origin", "IMPORTED").last("LIMIT 1"));
        if (entity == null) {
            entity = new AddressEntity(); entity.id = bytes(CrmUuidCodec.next()); entity.tenantId = bytes(tenantId);
            entity.partyId = bytes(partyId); entity.addressType = "CONTACT";
            entity.fullAddress = value(f, "clientAdd");
            entity.isDefault = false; entity.status = "ACTIVE"; entity.ownershipState = "EXTERNAL_PRIMARY";
            entity.recordOrigin = "IMPORTED"; entity.revision = 0L; entity.createdTime = now;
            entity.createdBy = SYSTEM_ACTOR; entity.updatedBy = SYSTEM_ACTOR;
            entity.updatedTime = now; entity.deleted = 0; addressMapper.insert(entity);
        } else if (sourceChanged && f.containsKey("clientAdd")
                && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) addressMapper.update(null,
                Wrappers.<AddressEntity>update().eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                        .set("full_address", value(f, "clientAdd"))
                        .set("updated_by", SYSTEM_ACTOR)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
    }

    private void upsertAssignments(UUID tenantId, UUID connectorId, UUID partyId,
                                   Map<String, Object> fields, LocalDateTime now,
                                   boolean sourceChanged) {
        StaffRefs refs = staffRefs(fields);
        upsertAssignment(tenantId, connectorId, partyId, refs.primary().sourceId(),
                refs.primary().name(), fields, refs.primaryFieldPresent(), now, sourceChanged);
        if (refs.secondaryFieldPresent()) {
            upsertSecondaryAssignments(tenantId, connectorId, partyId, refs.secondary(),
                    fields, now, sourceChanged);
        }
    }

    private void upsertAssignment(UUID tenantId, UUID connectorId, UUID partyId,
                                  String sourceStaffId, String staffName, Map<String, Object> fields,
                                  boolean staffFieldPresent,
                                  LocalDateTime now, boolean sourceChanged) {
        if (!staffFieldPresent) return;
        sourceStaffId = usableStaffId(sourceStaffId);
        staffName = clean(staffName);
        IamStaffRef iamStaff = iamStaff(fields, sourceStaffId, staffName);
        String staffCode = iamStaff.staffCode();
        String effectiveStaffName = first(iamStaff.staffName(), staffName);
        SalesAssignmentEntity current = assignmentMapper.selectOne(Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("assignment_type", "PRIMARY").eq("status", "ACTIVE").last("LIMIT 1"));
        if (sourceStaffId == null && effectiveStaffName == null) {
            if (sourceChanged) deactivate(current, tenantId, now);
            return;
        }
        if (current != null && sameStaff(current, sourceStaffId, staffCode, effectiveStaffName)) {
            boolean resolvedStaffChanged = staffCode != null
                    && !staffCode.equals(current.iamStaffCode);
            if (sourceChanged || resolvedStaffChanged) assignmentMapper.update(null, Wrappers.<SalesAssignmentEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", current.id)
                    .set("assignee_type", staffCode == null ? "SOURCE_STAFF" : "IAM_STAFF")
                    .set("source_staff_id", sourceStaffId)
                    .set("iam_staff_code", staffCode)
                    .set("iam_staff_name_snapshot", effectiveStaffName)
                    .set("source_name_snapshot", staffName == null
                            ? current.sourceNameSnapshot : staffName)
                    .set("updated_by", SYSTEM_ACTOR)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
            return;
        }
        deactivate(current, tenantId, now);
        SalesAssignmentEntity entity = new SalesAssignmentEntity(); entity.id = bytes(CrmUuidCodec.next());
        entity.tenantId = bytes(tenantId); entity.partyId = bytes(partyId); entity.assignmentType = "PRIMARY";
        entity.assigneeType = staffCode == null ? "SOURCE_STAFF" : "IAM_STAFF";
        entity.sourceStaffId = sourceStaffId;
        entity.iamStaffCode = staffCode; entity.iamStaffNameSnapshot = effectiveStaffName;
        entity.source = "DHB_IMPORT"; entity.sourceNameSnapshot = staffName; entity.effectiveFrom = now;
        entity.status = "ACTIVE"; entity.revision = 0L; entity.createdBy = SYSTEM_ACTOR;
        entity.createdTime = now; entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now;
        entity.deleted = 0;
        assignmentMapper.insert(entity);
    }

    private void upsertSecondaryAssignments(UUID tenantId, UUID connectorId, UUID partyId,
                                            List<StaffRef> incoming, Map<String, Object> fields,
                                            LocalDateTime now,
                                            boolean sourceChanged) {
        List<SalesAssignmentEntity> current = assignmentMapper.selectList(Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("assignment_type", "SECONDARY").eq("status", "ACTIVE")
                .eq("source", "DHB_IMPORT"));
        Set<String> matched = new LinkedHashSet<>();
        for (StaffRef ref : incoming) {
            String sourceStaffId = usableStaffId(ref.sourceId());
            String staffName = clean(ref.name());
            IamStaffRef iamStaff = iamStaff(fields, sourceStaffId, staffName);
            String staffCode = iamStaff.staffCode();
            String effectiveStaffName = first(iamStaff.staffName(), staffName);
            if (sourceStaffId == null && effectiveStaffName == null) continue;
            SalesAssignmentEntity existing = current.stream()
                    .filter(item -> sameStaff(item, sourceStaffId, staffCode, effectiveStaffName))
                    .findFirst().orElse(null);
            if (existing == null) {
                SalesAssignmentEntity entity = new SalesAssignmentEntity();
                entity.id = bytes(CrmUuidCodec.next()); entity.tenantId = bytes(tenantId);
                entity.partyId = bytes(partyId); entity.assignmentType = "SECONDARY";
                entity.assigneeType = staffCode == null ? "SOURCE_STAFF" : "IAM_STAFF";
                entity.sourceStaffId = sourceStaffId; entity.source = "DHB_IMPORT";
                entity.iamStaffCode = staffCode; entity.iamStaffNameSnapshot = effectiveStaffName;
                entity.sourceNameSnapshot = staffName; entity.effectiveFrom = now;
                entity.status = "ACTIVE"; entity.revision = 0L;
                entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
                entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
                assignmentMapper.insert(entity);
            } else {
                matched.add(java.util.HexFormat.of().formatHex(existing.id));
                boolean resolvedStaffChanged = staffCode != null
                        && !staffCode.equals(existing.iamStaffCode);
                if (sourceChanged || resolvedStaffChanged) assignmentMapper.update(null, Wrappers.<SalesAssignmentEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", existing.id)
                        .set("assignee_type", staffCode == null ? "SOURCE_STAFF" : "IAM_STAFF")
                        .set("source_staff_id", sourceStaffId)
                        .set("iam_staff_code", staffCode)
                        .set("iam_staff_name_snapshot", effectiveStaffName)
                        .set("source_name_snapshot", staffName == null
                                ? existing.sourceNameSnapshot : staffName)
                        .set("updated_by", SYSTEM_ACTOR)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
            }
        }
        if (sourceChanged) current.stream()
                .filter(item -> !matched.contains(java.util.HexFormat.of().formatHex(item.id)))
                .forEach(item -> deactivate(item, tenantId, now));
    }

    private static boolean sameStaff(SalesAssignmentEntity current, String sourceStaffId,
                                     String staffCode, String staffName) {
        if (sourceStaffId != null && sourceStaffId.equals(current.sourceStaffId)) return true;
        if (staffCode != null && staffCode.equals(current.iamStaffCode)) return true;
        return sourceStaffId == null && staffCode == null && staffName != null
                && (staffName.equals(current.iamStaffNameSnapshot)
                || staffName.equals(current.sourceNameSnapshot));
    }

    @SuppressWarnings("unchecked")
    private static IamStaffRef iamStaff(Map<String, Object> fields,
                                        String sourceStaffId, String fallbackName) {
        String cleanedSourceStaffId = usableStaffId(sourceStaffId);
        if (cleanedSourceStaffId == null || fields == null) {
            return new IamStaffRef(null, clean(fallbackName));
        }
        Object raw = fields.get(IAM_STAFF_BY_SOURCE_ID);
        if (!(raw instanceof Map<?, ?> mappings)) {
            return new IamStaffRef(null, clean(fallbackName));
        }
        Object resolved = mappings.get(cleanedSourceStaffId);
        if (!(resolved instanceof Map<?, ?> map)) {
            return new IamStaffRef(null, clean(fallbackName));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return new IamStaffRef(clean(value(value, "staffCode")),
                first(clean(value(value, "staffName")), clean(fallbackName)));
    }

    private static Map<String, String> iamStaffTargets(List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (SourceRecord record : records) {
            Object raw = record.sourceFields().get(IAM_STAFF_BY_SOURCE_ID);
            if (!(raw instanceof Map<?, ?> mappings)) continue;
            for (Map.Entry<?, ?> entry : mappings.entrySet()) {
                String sourceStaffId = usableStaffId(String.valueOf(entry.getKey()));
                if (sourceStaffId == null || !(entry.getValue() instanceof Map<?, ?> map)) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                map.forEach((key, item) -> value.put(String.valueOf(key), item));
                String staffCode = clean(value(value, "staffCode"));
                if (staffCode != null) result.putIfAbsent(sourceStaffId, staffCode);
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private record IamStaffRef(String staffCode, String staffName) { }

    private static String usableStaffId(String value) {
        String cleaned = clean(value);
        return cleaned == null || "0".equals(cleaned) ? null : cleaned;
    }

    private static StaffRefs staffRefs(Map<String, Object> fields) {
        List<String> primaryIds = staffValues(fields, "staffID", "staffId", "staff_id");
        List<String> primaryNames = staffValues(fields, "staffName", "staff_name");
        List<StaffRef> primaryParts = pairStaff(primaryIds, primaryNames);
        StaffRef primary = primaryParts.isEmpty() ? new StaffRef(null, null) : primaryParts.getFirst();
        List<StaffRef> secondary = new ArrayList<>();
        if (primaryParts.size() > 1) secondary.addAll(primaryParts.subList(1, primaryParts.size()));
        List<String> secondaryIds = staffValues(fields,
                "secondaryStaffID", "secondaryStaffId", "secondary_staff_id",
                "assistantStaffID", "assistantStaffId", "assistant_staff_id",
                "auxiliaryStaffID", "auxiliaryStaffId", "auxiliary_staff_id",
                "staffID2", "staffId2", "staff_id2", "staffID_2", "staff_id_2");
        List<String> secondaryNames = staffValues(fields,
                "secondaryStaffName", "secondary_staff_name",
                "assistantStaffName", "assistant_staff_name",
                "auxiliaryStaffName", "auxiliary_staff_name",
                "staffName2", "staff_name2", "staffName_2", "staff_name_2");
        secondary.addAll(pairStaff(secondaryIds, secondaryNames));
        List<StaffRef> nested = nestedStaffRefs(fields);
        if (primary.sourceId() == null && primary.name() == null && !nested.isEmpty()) {
            primary = nested.getFirst();
            nested = nested.subList(1, nested.size());
        }
        secondary.addAll(nested);
        boolean primaryPresent = containsAny(fields, "staffID", "staffId", "staff_id", "staffName", "staff_name")
                || containsAny(fields, "staffs", "staffList", "salesStaffs", "salesmen",
                "businessStaffs", "businessStaffList", "staff_info_list");
        boolean secondaryPresent = primaryParts.size() > 1
                || containsAny(fields, "secondaryStaffID", "secondaryStaffId", "secondary_staff_id",
                "secondaryStaffName", "secondary_staff_name", "assistantStaffID", "assistantStaffId",
                "assistant_staff_id", "assistantStaffName", "assistant_staff_name", "auxiliaryStaffID",
                "auxiliaryStaffId", "auxiliary_staff_id", "auxiliaryStaffName", "auxiliary_staff_name",
                "staffID2", "staffId2", "staff_id2", "staffID_2", "staff_id_2", "staffName2",
                "staff_name2", "staffName_2", "staff_name_2", "staffs", "staffList",
                "salesStaffs", "salesmen", "businessStaffs", "businessStaffList", "staff_info_list");
        return new StaffRefs(primary, List.copyOf(secondary), primaryPresent, secondaryPresent);
    }

    private static List<StaffRef> nestedStaffRefs(Map<String, Object> fields) {
        for (String key : List.of("staffs", "staffList", "salesStaffs", "salesmen",
                "businessStaffs", "businessStaffList", "staff_info_list")) {
            Object raw = fields.get(key);
            if (!(raw instanceof Collection<?> collection)) continue;
            List<StaffRef> result = new ArrayList<>();
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((name, value) -> values.put(String.valueOf(name), value));
                String id = value(values, "staffID", value(values, "staffId", value(values,
                        "staff_id", value(values, "sourceStaffId", value(values, "source_staff_id")))));
                String name = value(values, "staffName", value(values, "staff_name", value(values,
                        "name", value(values, "sourceName"))));
                if (clean(id) == null && clean(name) == null) continue;
                result.add(new StaffRef(id, name));
            }
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    private static List<StaffRef> pairStaff(List<String> ids, List<String> names) {
        int size = Math.max(ids.size(), names.size());
        List<StaffRef> result = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            result.add(new StaffRef(index < ids.size() ? ids.get(index) : null,
                    index < names.size() ? names.get(index) : null));
        }
        return result;
    }

    private static List<String> staffValues(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            if (!fields.containsKey(key)) continue;
            Object raw = fields.get(key);
            List<String> values = new ArrayList<>();
            if (raw instanceof Collection<?> collection) {
                collection.forEach(value -> addStaffTokens(values, value));
            } else addStaffTokens(values, raw);
            if (!values.isEmpty()) return List.copyOf(values);
        }
        return List.of();
    }

    private static void addStaffTokens(List<String> values, Object raw) {
        if (raw == null) return;
        String text = String.valueOf(raw).strip();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return;
        Arrays.stream(text.split("[,，;；|、\\n]"))
                .map(String::strip).filter(value -> !value.isEmpty()).forEach(values::add);
    }

    private static boolean containsAny(Map<String, Object> fields, String... keys) {
        return Arrays.stream(keys).anyMatch(fields::containsKey);
    }

    private record StaffRef(String sourceId, String name) { }
    private record StaffRefs(StaffRef primary, List<StaffRef> secondary,
                             boolean primaryFieldPresent, boolean secondaryFieldPresent) { }

    private void deactivate(SalesAssignmentEntity entity, UUID tenantId, LocalDateTime now) {
        if (entity == null) return;
        assignmentMapper.update(null, Wrappers.<SalesAssignmentEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                .set("status", "INACTIVE").set("effective_to", now)
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
    }

    private void saveBinding(SourceBindingEntity binding, UUID runId, SourceRecord record,
                             Target target, String json, String hash, LocalDateTime now, boolean create) {
        binding.sourceCode = first(record.sourceCode(), binding.sourceCode);
        binding.sourceName = first(record.sourceName(), binding.sourceName);
        binding.sourceStatus = first(record.sourceStatus(), binding.sourceStatus);
        binding.targetType = target.type();
        binding.targetId = bytes(target.id()); binding.bindingStatus = target.status();
        binding.resolutionErrorCode = target.errorCode(); binding.resolutionErrorMessage = target.errorMessage();
        binding.sourceCreatedAt = first(local(record.sourceCreatedAt()), binding.sourceCreatedAt);
        binding.sourceUpdatedAt = first(local(record.sourceUpdatedAt()), binding.sourceUpdatedAt);
        binding.sourceFieldsJson = json; binding.sourcePayloadHash = hash; binding.sourcePresence = "PRESENT";
        binding.absentConfirmCount = 0; binding.sourceAbsentAt = null; binding.lastSeenRunId = bytes(runId);
        binding.lastSyncRunId = bytes(runId); binding.syncedAt = now;
        binding.updatedBy = SYSTEM_ACTOR; binding.updatedTime = now; binding.deleted = 0;
        if (create) bindingMapper.insert(binding);
        else bindingMapper.update(null, Wrappers.<SourceBindingEntity>update()
                .eq("tenant_id", binding.tenantId).eq("id", binding.id)
                .set("source_code", binding.sourceCode).set("source_name", binding.sourceName)
                .set("source_status", binding.sourceStatus).set("target_type", binding.targetType)
                .set("target_id", binding.targetId).set("binding_status", binding.bindingStatus)
                .set("resolution_error_code", binding.resolutionErrorCode)
                .set("resolution_error_message", binding.resolutionErrorMessage)
                .set("source_created_at", binding.sourceCreatedAt)
                .set("source_updated_at", binding.sourceUpdatedAt)
                .set("source_fields_json", json).set("source_payload_hash", hash)
                .set("source_presence", "PRESENT").set("absent_confirm_count", 0)
                .set("source_absent_at", null).set("last_seen_run_id", bytes(runId))
                .set("last_sync_run_id", bytes(runId)).set("synced_at", now)
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .set("deleted", 0)
                .setSql("revision=revision+1"));
    }

    private Map<String, SourceBindingEntity> bindings(UUID tenantId, UUID connectorId,
                                                      CrmMasterDataObjectType type,
                                                      Collection<String> sourceIds,
                                                      boolean lock) {
        List<String> ids = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var query = Wrappers.<SourceBindingEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM).eq("source_object_type", type.name())
                .in("source_object_id", ids);
        if (lock) query.last("FOR UPDATE");
        Map<String, SourceBindingEntity> result = new LinkedHashMap<>();
        for (SourceBindingEntity binding : bindingMapper.selectList(query)) {
            result.putIfAbsent(binding.sourceObjectId, binding);
        }
        return result;
    }

    private void markSeen(SourceBindingEntity binding, UUID runId, SourceRecord record, LocalDateTime now) {
        bindingMapper.update(null, Wrappers.<SourceBindingEntity>update()
                .eq("tenant_id", binding.tenantId).eq("id", binding.id)
                .set("source_presence", "PRESENT").set("absent_confirm_count", 0)
                .set("source_absent_at", null).set("last_seen_run_id", bytes(runId))
                .set("last_sync_run_id", bytes(runId)).set("synced_at", now)
                .set(record.sourceUpdatedAt() != null,
                        "source_updated_at", local(record.sourceUpdatedAt()))
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
    }

    private void aliases(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                         CrmMasterDataObjectType type, SourceRecord record, LocalDateTime now) {
        Map<String, String> values = switch (type) {
            case CUSTOMER_TYPE -> aliasMap("ID", record.sourceId(), "ERP_ID", value(record.sourceFields(), "erpID"));
            case CUSTOMER_AREA -> aliasMap("ID", record.sourceId(), "ERP_ID", value(record.sourceFields(), "ERPID"));
            case CUSTOMER -> aliasMap("GUID", value(record.sourceFields(), "clientGUID"),
                    "NUM", value(record.sourceFields(), "clientNO"),
                    "ACCOUNT", value(record.sourceFields(), "clientAccount"),
                    "ID", value(record.sourceFields(), "clientID", value(record.sourceFields(), "clientId")));
            case ADDRESS -> aliasMap("GUID", value(record.sourceFields(), "addressGuid"), "ID", value(record.sourceFields(), "addressId"));
        };
        values.forEach((aliasType, aliasValue) -> {
            SourceIdentityAliasEntity entity = aliasMapper.selectOne(Wrappers.<SourceIdentityAliasEntity>query()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("connector_id", bytes(connectorId))
                    .eq("source_system", SOURCE_SYSTEM)
                    .eq("source_object_type", type.name())
                    .eq("alias_type", aliasType)
                    .eq("alias_value", aliasValue).last("LIMIT 1"));
            if (entity == null) {
                entity = new SourceIdentityAliasEntity(); entity.id = bytes(CrmUuidCodec.next());
                entity.tenantId = bytes(tenantId); entity.bindingId = binding.id; entity.connectorId = bytes(connectorId);
                entity.sourceSystem = SOURCE_SYSTEM; entity.sourceObjectType = type.name(); entity.aliasType = aliasType;
                entity.aliasValue = aliasValue; entity.isPrimary = aliasValue.equals(record.sourceId());
                entity.firstSeenAt = now; entity.lastSeenAt = now; entity.revision = 1;
                entity.createdBy = SYSTEM_ACTOR; entity.createdTime = now;
                entity.updatedBy = SYSTEM_ACTOR; entity.updatedTime = now; entity.deleted = 0;
                aliasMapper.insert(entity);
            } else aliasMapper.update(null, Wrappers.<SourceIdentityAliasEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                    .set("last_seen_at", now)
                    .set("updated_by", SYSTEM_ACTOR)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
        });
    }

    private long reconcileSourcePresence(UUID tenantId, UUID connectorId, UUID runId,
                                         CrmMasterDataObjectType type) {
        List<SourceBindingEntity> missing = bindingMapper.selectList(Wrappers.<SourceBindingEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM).eq("source_object_type", type.name())
                .and(q -> q.isNull("last_seen_run_id").or().ne("last_seen_run_id", bytes(runId)))
                .ne("source_presence", "DELETED"));
        long confirmed = 0;
        LocalDateTime now = now();
        for (SourceBindingEntity binding : missing) {
            int count = binding.absentConfirmCount == null ? 1 : binding.absentConfirmCount + 1;
            if (count == 2) confirmed++;
            bindingMapper.update(null, Wrappers.<SourceBindingEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", binding.id)
                    .set("absent_confirm_count", count)
                    .set("source_presence", count >= 2 ? "ABSENT" : "ABSENT_CANDIDATE")
                    .set("source_absent_at", binding.sourceAbsentAt == null ? now : binding.sourceAbsentAt)
                    .set("updated_by", SYSTEM_ACTOR)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
        }
        return confirmed;
    }

    @Override
    @Transactional
    public RunStatistics completeRun(UUID tenantId, UUID connectorId, UUID runId,
                                     CrmMasterDataObjectType type, RunStatistics stats,
                                     boolean reconcileSourcePresence) {
        heartbeatRun(tenantId, connectorId, runId, type);
        LocalDateTime now = now();
        long absent = reconcileSourcePresence
                ? reconcileSourcePresence(tenantId, connectorId, runId, type) : 0;
        RunStatistics finalized = new RunStatistics(stats.fetched(), stats.created(),
                stats.changed(), stats.repaired(), stats.duplicates(), absent,
                stats.rejected(), stats.pages());
        int completed = syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(runId))
                .eq("status", "RUNNING")
                .set("status", "SUCCEEDED").set("fetched_count", finalized.fetched())
                .set("created_count", finalized.created()).set("changed_count", finalized.changed())
                .set("repaired_count", finalized.repaired()).set("duplicate_count", finalized.duplicates())
                .set("absent_count", finalized.absent()).set("rejected_count", finalized.rejected())
                .set("finished_at", now)
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        if (completed != 1) {
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "CRM同步运行所有权已失效，不能确认本批成功", List.of());
        }
        CrmSyncCheckpointEntity checkpoint = checkpointMapper.selectOne(Wrappers.<CrmSyncCheckpointEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM).eq("object_type", type.name()));
        if (checkpoint == null) {
            checkpoint = new CrmSyncCheckpointEntity(); checkpoint.id = bytes(CrmUuidCodec.next());
            checkpoint.tenantId = bytes(tenantId); checkpoint.connectorId = bytes(connectorId);
            checkpoint.sourceSystem = SOURCE_SYSTEM; checkpoint.objectType = type.name(); checkpoint.cursorType = "FULL_ONLY";
            checkpoint.lastSuccessRunId = bytes(runId); checkpoint.revision = 0L;
            checkpoint.createdBy = SYSTEM_ACTOR; checkpoint.createdTime = now;
            checkpoint.updatedBy = SYSTEM_ACTOR; checkpoint.updatedTime = now; checkpoint.deleted = 0;
            checkpointMapper.insert(checkpoint);
        } else checkpointMapper.update(null, Wrappers.<CrmSyncCheckpointEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", checkpoint.id)
                .set("last_success_run_id", bytes(runId))
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        releaseLock(tenantId, connectorId, type, runId);
        return finalized;
    }

    @Override
    public List<ExternalObjectMappingCommand> externalObjectMappings(
            UUID tenantId, UUID connectorId, UUID runId, CrmMasterDataObjectType objectType) {
        if (objectType != CrmMasterDataObjectType.CUSTOMER) return List.of();
        List<ExternalObjectMappingCommand> result = new ArrayList<>();
        List<SourceBindingEntity> bindings = bindingMapper.selectList(Wrappers.<SourceBindingEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", CrmMasterDataObjectType.CUSTOMER.name())
                .eq("binding_status", "RESOLVED")
                .eq("source_presence", "PRESENT"));
        List<ExternalMappingSeed> seeds = new ArrayList<>();
        Set<UUID> partyIds = new LinkedHashSet<>();
        Set<String> customerCodes = new LinkedHashSet<>();
        for (SourceBindingEntity binding : bindings) {
            String sourceObjectNo = first(binding.sourceCode,
                    value(jsonMap(binding.sourceFieldsJson), "clientNO"),
                    binding.sourceObjectId);
            UUID partyId = binding.targetId == null ? null : uuid(binding.targetId);
            seeds.add(new ExternalMappingSeed(binding, partyId, sourceObjectNo));
            if (partyId != null) partyIds.add(partyId);
            if (sourceObjectNo != null && !sourceObjectNo.isBlank()) customerCodes.add(sourceObjectNo);
        }
        Map<UUID, InternalCustomerEntity> customersByPartyId =
                internalCustomersByPartyId(tenantId, partyIds);
        Map<String, InternalCustomerEntity> customersByCode =
                internalCustomersByCode(tenantId, customerCodes);
        for (ExternalMappingSeed seed : seeds) {
            SourceBindingEntity binding = seed.binding();
            InternalCustomerEntity customer = customersByPartyId.get(seed.partyId());
            if (customer == null) {
                customer = customersByCode.get(seed.sourceObjectNo());
            }
            if (customer == null) continue;
            result.add(new ExternalObjectMappingCommand(
                    connectorId,
                    INTEGRATION_SOURCE_SYSTEM,
                    "CUSTOMER",
                    binding.sourceObjectId,
                    seed.sourceObjectNo(),
                    "CRM",
                    "CUSTOMER",
                    customer.getId(),
                    customer.getCustomerCode(),
                    "ACTIVE",
                    runId,
                    instant(binding.syncedAt),
                    null,
                    binding.sourcePayloadHash,
                    null,
                    "CRM订货宝客户同步映射"));
        }
        return result;
    }

    private Map<UUID, InternalCustomerEntity> internalCustomersByPartyId(
            UUID tenantId, Collection<UUID> partyIds) {
        if (partyIds == null || partyIds.isEmpty()) return Map.of();
        List<byte[]> ids = partyIds.stream()
                .filter(Objects::nonNull)
                .map(MybatisPlusCrmRepository::bytes)
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, InternalCustomerEntity> result = new LinkedHashMap<>();
        internalCustomerMapper.selectList(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                        .in(InternalCustomerEntity::getPartyId, ids))
                .forEach(entity -> {
                    UUID partyId = entity.getPartyId() == null ? null : uuid(entity.getPartyId());
                    if (partyId != null) result.putIfAbsent(partyId, entity);
                });
        return result;
    }

    private Map<String, InternalCustomerEntity> internalCustomersByCode(
            UUID tenantId, Collection<String> customerCodes) {
        if (customerCodes == null || customerCodes.isEmpty()) return Map.of();
        List<String> codes = customerCodes.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (codes.isEmpty()) return Map.of();
        Map<String, InternalCustomerEntity> result = new LinkedHashMap<>();
        internalCustomerMapper.selectList(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                        .in(InternalCustomerEntity::getCustomerCode, codes))
                .forEach(entity -> result.putIfAbsent(entity.getCustomerCode(), entity));
        return result;
    }

    @Override
    @Transactional
    public void failRun(UUID tenantId, UUID connectorId, UUID runId,
                        RunStatistics stats, RuntimeException error) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(runId))
                .eq("status", "RUNNING")
                .set("status", "FAILED").set("fetched_count", stats.fetched())
                .set("created_count", stats.created()).set("changed_count", stats.changed())
                .set("repaired_count", stats.repaired()).set("duplicate_count", stats.duplicates())
                .set("absent_count", stats.absent()).set("rejected_count", stats.rejected())
                .set("error_code", error.getClass().getSimpleName())
                .set("error_message", safeMessage(error.getMessage()))
                .set("finished_at", now)
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        CrmSyncRunEntity run = syncRunMapper.selectOne(Wrappers.<CrmSyncRunEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(runId)));
        if (run != null) releaseLock(tenantId, connectorId,
                CrmMasterDataObjectType.valueOf(run.objectType), runId);
    }

    private void releaseLock(UUID tenantId, UUID connectorId, CrmMasterDataObjectType type, UUID runId) {
        lockMapper.delete(Wrappers.<CrmSyncLockEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("object_type", type.name()).eq("run_id", bytes(runId)));
    }

    @Override
    public PageView<CustomerSummaryView> customers(UUID tenantId, int begin, int step, String query, String status) {
        String search = like(query);
        long total = queryMapper.countCustomers(bytes(tenantId), search, clean(status));
        List<Map<String, Object>> rows = queryMapper.customers(bytes(tenantId), begin, step, search, clean(status));
        Map<UUID, List<SalesAssignmentView>> assignments = assignments(tenantId, rows);
        List<CustomerSummaryView> items = rows.stream().map(row -> {
                    UUID id = uuid(row.get("id"));
                    return new CustomerSummaryView(id, text(row, "party_code"),
                        text(row, "display_name"), text(row, "internal_status"), text(row, "login_account"),
                        text(row, "type_name"), text(row, "area_name"), text(row, "contact_name"),
                        text(row, "phone"), text(row, "source_name_snapshot"),
                        assignments.getOrDefault(id, List.of()), instant(row, "source_updated_at"),
                        instant(row, "synced_at"), text(row, "source_presence"),
                        text(row, "source_status"), instant(row, "source_absent_at"));
                }).toList();
        return new PageView<>(total, begin, step, items);
    }

    @Override
    public CustomerDetailView customer(UUID tenantId, UUID id) {
        Map<String, Object> row = queryMapper.customer(bytes(tenantId), bytes(id));
        if (row == null || row.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在", List.of());
        List<SalesAssignmentView> assignments = assignments(tenantId, List.of(row))
                .getOrDefault(id, List.of());
        Map<String, Object> sourceFields = jsonMap(text(row, "source_fields_json"));
        CustomerSourceView source = new CustomerSourceView(
                value(sourceFields, "clientGUID", text(row, "source_object_id")),
                value(sourceFields, "clientType"), value(sourceFields, "clientArea"),
                value(sourceFields, "clientAreaGUID"),
                value(sourceFields, "clientStatus", text(row, "source_status")),
                value(sourceFields, "clientClearingForm"));
        List<AddressView> addresses = queryMapper.shippingAddresses(bytes(tenantId), bytes(id)).stream()
                .map(value -> new AddressView(uuid(value.get("id")), text(value, "consignee"),
                        text(value, "contact_name"), text(value, "phone"), text(value, "region_text"),
                        text(value, "area_name"), text(value, "address_detail"), text(value, "full_address"),
                        bool(value.get("is_default")), instant(value, "source_updated_at"),
                        jsonMap(text(value, "source_fields_json")), text(value, "source_presence"),
                        instant(value, "source_absent_at"))).toList();
        return new CustomerDetailView(uuid(row.get("id")), text(row, "party_code"), text(row, "display_name"),
                text(row, "internal_status"), text(row, "login_account"), text(row, "type_name"),
                text(row, "area_name"), text(row, "city_text"), text(row, "inviter_name"), text(row, "remark"),
                text(row, "contact_name"), text(row, "phone"), text(row, "email"), text(row, "full_address"),
                text(row, "settlement_mode"), text(row, "source_name_snapshot"), assignments,
                text(row, "source_status"),
                instant(row, "source_created_at"), instant(row, "source_updated_at"), instant(row, "synced_at"),
                text(row, "source_presence"), instant(row, "source_absent_at"), addresses,
                sourceFields, source);
    }

    private Map<UUID, List<SalesAssignmentView>> assignments(UUID tenantId,
                                                               List<Map<String, Object>> rows) {
        List<byte[]> partyIds = rows.stream().map(row -> uuid(row.get("id")))
                .filter(java.util.Objects::nonNull).map(MybatisPlusCrmRepository::bytes).toList();
        if (partyIds.isEmpty()) return Map.of();
        Map<UUID, List<SalesAssignmentView>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : queryMapper.salesAssignments(bytes(tenantId), partyIds)) {
            UUID partyId = uuid(row.get("party_id"));
            if (partyId == null) continue;
            result.computeIfAbsent(partyId, ignored -> new ArrayList<>())
                    .add(new SalesAssignmentView(text(row, "assignment_type"),
                            text(row, "source_staff_id"), text(row, "iam_staff_code"),
                            text(row, "staff_name")));
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    @Override
    public PageView<ShippingAddressSummaryView> shippingAddresses(
            UUID tenantId, int begin, int step, String query) {
        String search = like(query);
        long total = queryMapper.countShippingAddressBook(bytes(tenantId), search);
        List<ShippingAddressSummaryView> items = queryMapper.shippingAddressBook(
                        bytes(tenantId), begin, step, search).stream()
                .map(row -> new ShippingAddressSummaryView(
                        uuid(row.get("id")), uuid(row.get("customer_id")),
                        text(row, "party_code"), text(row, "display_name"),
                        text(row, "source_object_id"), text(row, "consignee"),
                        text(row, "contact_name"), text(row, "phone"),
                        text(row, "region_text"), text(row, "area_name"),
                        text(row, "address_detail"), text(row, "full_address"),
                        bool(row.get("is_default")), text(row, "status"),
                        instant(row, "source_updated_at"), instant(row, "synced_at"),
                        text(row, "source_presence"), instant(row, "source_absent_at")))
                .toList();
        return new PageView<>(total, begin, step, items);
    }

    @Override public PageView<DictionaryView> customerTypes(UUID tenantId, int begin, int step, String query) {
        String search = like(query); long total = queryMapper.countCustomerTypes(bytes(tenantId), search);
        return dictionaries(total, begin, step, queryMapper.customerTypes(bytes(tenantId), begin, step, search));
    }
    @Override public PageView<DictionaryView> customerAreas(UUID tenantId, int begin, int step, String query) {
        String search = like(query); long total = queryMapper.countCustomerAreas(bytes(tenantId), search);
        return dictionaries(total, begin, step, queryMapper.customerAreas(bytes(tenantId), begin, step, search));
    }
    private PageView<DictionaryView> dictionaries(long total, int begin, int step, List<Map<String, Object>> rows) {
        return new PageView<>(total, begin, step, rows.stream().map(row -> new DictionaryView(uuid(row.get("id")),
                text(row, "code"), text(row, "name"), text(row, "status"), instant(row, "synced_at"),
                uuid(row.get("parent_id")), text(row, "parent_code"), text(row, "source_presence"),
                instant(row, "source_absent_at"))).toList());
    }

    private SourceBindingEntity binding(UUID tenantId, UUID connectorId, CrmMasterDataObjectType type,
                                        String sourceId, boolean lock) {
        var query = Wrappers.<SourceBindingEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM).eq("source_object_type", type.name())
                .eq("source_object_id", sourceId);
        if (lock) query.last("FOR UPDATE");
        return bindingMapper.selectOne(query);
    }

    private UUID sourceTarget(UUID tenantId, UUID connectorId, CrmMasterDataObjectType type, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return null;
        String cleanSourceId = sourceId.strip();
        SourceBindingEntity binding = binding(tenantId, connectorId, type, cleanSourceId, false);
        if (binding != null && binding.targetId != null && "RESOLVED".equals(binding.bindingStatus)) {
            return uuid(binding.targetId);
        }
        SourceIdentityAliasEntity alias = aliasMapper.selectOne(Wrappers.<SourceIdentityAliasEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", type.name())
                .eq("alias_value", cleanSourceId)
                .last("LIMIT 1"));
        if (alias == null || alias.bindingId == null) return null;
        SourceBindingEntity aliasBinding = bindingMapper.selectOne(Wrappers.<SourceBindingEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", type.name())
                .eq("id", alias.bindingId)
                .last("LIMIT 1"));
        return aliasBinding != null && "RESOLVED".equals(aliasBinding.bindingStatus)
                ? uuid(aliasBinding.targetId) : null;
    }

    private Map<String, UUID> sourceTargets(UUID tenantId, UUID connectorId,
                                            CrmMasterDataObjectType type,
                                            Collection<String> sourceIds) {
        List<String> ids = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, SourceBindingEntity> values = bindings(tenantId, connectorId, type, ids, false);
        Map<String, UUID> result = new LinkedHashMap<>();
        for (Map.Entry<String, SourceBindingEntity> entry : values.entrySet()) {
            SourceBindingEntity binding = entry.getValue();
            if (binding != null && "RESOLVED".equals(binding.bindingStatus)
                    && binding.targetId != null) {
                result.put(entry.getKey(), uuid(binding.targetId));
            }
        }
        Set<String> unresolved = ids.stream()
                .filter(sourceId -> !result.containsKey(sourceId))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (unresolved.isEmpty()) return result;

        Map<String, UUID> bindingByAliasValue = new LinkedHashMap<>();
        Set<UUID> bindingIds = new LinkedHashSet<>();
        aliasMapper.selectList(Wrappers.<SourceIdentityAliasEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("connector_id", bytes(connectorId))
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", type.name())
                        .in("alias_value", unresolved))
                .forEach(alias -> {
                    if (alias.aliasValue == null || alias.bindingId == null) return;
                    String aliasValue = alias.aliasValue.strip();
                    if (!unresolved.contains(aliasValue)) return;
                    UUID bindingId = uuid(alias.bindingId);
                    bindingByAliasValue.putIfAbsent(aliasValue, bindingId);
                    bindingIds.add(bindingId);
                });
        if (bindingIds.isEmpty()) return result;

        Map<UUID, UUID> targetByBinding = new LinkedHashMap<>();
        bindingMapper.selectList(Wrappers.<SourceBindingEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("connector_id", bytes(connectorId))
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", type.name())
                        .in("id", bindingIds.stream().map(MybatisPlusCrmRepository::bytes).toList()))
                .forEach(binding -> {
                    if (binding.id != null && binding.targetId != null
                            && "RESOLVED".equals(binding.bindingStatus)) {
                        targetByBinding.put(uuid(binding.id), uuid(binding.targetId));
                    }
                });
        bindingByAliasValue.forEach((aliasValue, bindingId) -> {
            UUID target = targetByBinding.get(bindingId);
            if (target != null) result.putIfAbsent(aliasValue, target);
        });
        return result;
    }

    private UUID customerTarget(UUID tenantId, UUID connectorId,
                                String guid, String number, String sourceId) {
        for (Map.Entry<String, String> candidate : List.of(
                Map.entry("GUID", guid == null ? "" : guid),
                Map.entry("NUM", number == null ? "" : number),
                Map.entry("ID", sourceId == null ? "" : sourceId))) {
            String value = candidate.getValue();
            if (value.isBlank()) continue;
            SourceIdentityAliasEntity alias = aliasMapper.selectOne(Wrappers.<SourceIdentityAliasEntity>query()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("connector_id", bytes(connectorId))
                    .eq("source_system", SOURCE_SYSTEM)
                    .eq("source_object_type", "CUSTOMER")
                    .eq("alias_type", candidate.getKey())
                    .eq("alias_value", value).last("LIMIT 1"));
            if (alias != null) {
                SourceBindingEntity binding = bindingMapper.selectOne(Wrappers.<SourceBindingEntity>query()
                        .eq("tenant_id", bytes(tenantId)).eq("id", alias.bindingId));
                if (binding != null && "RESOLVED".equals(binding.bindingStatus)) return uuid(binding.targetId);
            }
        }
        return null;
    }

    private AddressProjectionBatch addressProjectionBatch(UUID tenantId, UUID connectorId,
                                                          Map<String, SourceBindingEntity> existingBindings,
                                                          List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return null;
        Map<String, Set<String>> values = new LinkedHashMap<>();
        values.put("GUID", new LinkedHashSet<>());
        values.put("NUM", new LinkedHashSet<>());
        values.put("ID", new LinkedHashSet<>());
        Set<UUID> addressIds = new LinkedHashSet<>();
        for (SourceRecord record : records) {
            SourceBindingEntity binding = existingBindings == null ? null : existingBindings.get(record.sourceId());
            if (binding != null && binding.targetId != null
                    && "RESOLVED".equals(binding.bindingStatus)) {
                addressIds.add(uuid(binding.targetId));
            }
            SourceRecord snapshot = snapshot(binding, record);
            Map<String, Object> fields = snapshot.sourceFields();
            addAliasCandidate(values.get("GUID"), value(fields, "clientGuid"));
            addAliasCandidate(values.get("NUM"), value(fields, "clientNum"));
            addAliasCandidate(values.get("ID"), value(fields, "clientId"));
        }
        Set<String> allValues = new LinkedHashSet<>();
        values.values().forEach(allValues::addAll);
        if (allValues.isEmpty() && addressIds.isEmpty()) return null;

        byte[] tenant = bytes(tenantId);
        byte[] connector = bytes(connectorId);
        Map<String, UUID> bindingByAlias = new LinkedHashMap<>();
        Set<UUID> bindingIds = new LinkedHashSet<>();
        if (!allValues.isEmpty()) {
            aliasMapper.selectList(Wrappers.<SourceIdentityAliasEntity>query()
                            .eq("tenant_id", tenant)
                            .eq("connector_id", connector)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", CrmMasterDataObjectType.CUSTOMER.name())
                            .in("alias_value", allValues))
                    .forEach(alias -> {
                        if (alias.aliasType == null || alias.aliasValue == null) return;
                        Set<String> allowed = values.get(alias.aliasType);
                        if (allowed == null || !allowed.contains(alias.aliasValue) || alias.bindingId == null) return;
                        UUID bindingId = uuid(alias.bindingId);
                        bindingByAlias.putIfAbsent(aliasKey(alias.aliasType, alias.aliasValue), bindingId);
                        bindingIds.add(bindingId);
                    });
        }

        Map<UUID, UUID> targetByBinding = new LinkedHashMap<>();
        if (!bindingIds.isEmpty()) {
            bindingMapper.selectList(Wrappers.<SourceBindingEntity>query()
                            .eq("tenant_id", tenant)
                            .eq("connector_id", connector)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", CrmMasterDataObjectType.CUSTOMER.name())
                            .in("id", bindingIds.stream().map(MybatisPlusCrmRepository::bytes).toList()))
                    .forEach(binding -> {
                        if (binding.id != null && binding.targetId != null
                                && "RESOLVED".equals(binding.bindingStatus)) {
                            targetByBinding.put(uuid(binding.id), uuid(binding.targetId));
                        }
                    });
        }
        Map<String, UUID> targetByAlias = new LinkedHashMap<>();
        bindingByAlias.forEach((key, bindingId) -> {
            UUID target = targetByBinding.get(bindingId);
            if (target != null) targetByAlias.put(key, target);
        });
        Map<UUID, AddressProjection> addressById = new LinkedHashMap<>();
        Set<UUID> contactIds = new LinkedHashSet<>();
        if (!addressIds.isEmpty()) {
            addressMapper.selectList(Wrappers.<AddressEntity>query()
                            .eq("tenant_id", tenant)
                            .in("id", addressIds.stream().map(MybatisPlusCrmRepository::bytes).toList()))
                    .forEach(address -> {
                        if (address.id == null) return;
                        UUID contactId = uuid(address.contactId);
                        addressById.put(uuid(address.id), new AddressProjection(
                                uuid(address.partyId), contactId));
                        if (contactId != null) contactIds.add(contactId);
                    });
        }
        Set<UUID> existingContacts = new LinkedHashSet<>();
        if (!contactIds.isEmpty()) {
            contactMapper.selectList(Wrappers.<ContactEntity>query()
                            .eq("tenant_id", tenant)
                            .in("id", contactIds.stream().map(MybatisPlusCrmRepository::bytes).toList()))
                    .forEach(contact -> {
                        if (contact.id != null) existingContacts.add(uuid(contact.id));
                    });
        }
        return new AddressProjectionBatch(targetByAlias, addressById, existingContacts);
    }

    private static void addAliasCandidate(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private static String aliasKey(String type, String value) {
        return type + '\u0000' + value;
    }

    private CustomerProjectionBatch customerProjectionBatch(UUID tenantId, UUID connectorId,
                                                            Map<String, SourceBindingEntity> existingBindings,
                                                            List<SourceRecord> records) {
        if (existingBindings == null || existingBindings.isEmpty()
                || records == null || records.isEmpty()) return null;
        Set<UUID> partyIds = new LinkedHashSet<>();
        Set<String> typeSourceIds = new LinkedHashSet<>();
        Set<String> areaSourceIds = new LinkedHashSet<>();
        for (SourceRecord record : records) {
            SourceBindingEntity binding = existingBindings.get(record.sourceId());
            if (binding == null || binding.targetId == null
                    || !"RESOLVED".equals(binding.bindingStatus)) continue;
            partyIds.add(uuid(binding.targetId));
            SourceRecord snapshot = snapshot(binding, record);
            String typeId = value(snapshot.sourceFields(), "clientType");
            String areaId = value(snapshot.sourceFields(), "clientArea");
            if (typeId != null) typeSourceIds.add(typeId);
            if (areaId != null) areaSourceIds.add(areaId);
        }
        if (partyIds.isEmpty()) return null;
        List<byte[]> ids = partyIds.stream().map(MybatisPlusCrmRepository::bytes).toList();
        byte[] tenant = bytes(tenantId);

        Set<UUID> parties = new LinkedHashSet<>();
        partyMapper.selectList(Wrappers.<PartyEntity>query()
                        .eq("tenant_id", tenant).in("id", ids))
                .forEach(entity -> parties.add(uuid(entity.id)));

        Map<UUID, CustomerProfileEntity> profiles = new LinkedHashMap<>();
        customerProfileMapper.selectList(Wrappers.<CustomerProfileEntity>query()
                        .eq("tenant_id", tenant).in("party_id", ids))
                .forEach(entity -> profiles.putIfAbsent(uuid(entity.partyId), entity));

        Set<UUID> policies = new LinkedHashSet<>();
        customerPolicyMapper.selectList(Wrappers.<CustomerPolicyEntity>query()
                        .eq("tenant_id", tenant).in("party_id", ids))
                .forEach(entity -> policies.add(uuid(entity.partyId)));

        Set<UUID> primaryContacts = new LinkedHashSet<>();
        contactMapper.selectList(Wrappers.<ContactEntity>query()
                        .eq("tenant_id", tenant).in("party_id", ids)
                        .eq("contact_type", "PRIMARY")
                        .eq("record_origin", "IMPORTED"))
                .forEach(entity -> primaryContacts.add(uuid(entity.partyId)));

        Set<UUID> contactAddresses = new LinkedHashSet<>();
        addressMapper.selectList(Wrappers.<AddressEntity>query()
                        .eq("tenant_id", tenant).in("party_id", ids)
                        .eq("address_type", "CONTACT")
                        .eq("record_origin", "IMPORTED"))
                .forEach(entity -> contactAddresses.add(uuid(entity.partyId)));

        Map<UUID, List<SalesAssignmentEntity>> assignments = new LinkedHashMap<>();
        assignmentMapper.selectList(Wrappers.<SalesAssignmentEntity>query()
                        .eq("tenant_id", tenant).in("party_id", ids)
                        .eq("status", "ACTIVE"))
                .forEach(entity -> assignments.computeIfAbsent(uuid(entity.partyId),
                        ignored -> new ArrayList<>()).add(entity));

        Map<UUID, InternalCustomerEntity> internalCustomers = new LinkedHashMap<>();
        internalCustomerMapper.selectList(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                        .in(InternalCustomerEntity::getPartyId, ids)
                        .eq(InternalCustomerEntity::getDeleted, 0))
                .forEach(entity -> internalCustomers.putIfAbsent(uuid(entity.getPartyId()), entity));

        Map<String, UUID> typeTargets = sourceTargets(tenantId, connectorId,
                CrmMasterDataObjectType.CUSTOMER_TYPE, typeSourceIds);
        Map<String, UUID> areaTargets = sourceTargets(tenantId, connectorId,
                CrmMasterDataObjectType.CUSTOMER_AREA, areaSourceIds);

        return new CustomerProjectionBatch(parties, profiles, policies, primaryContacts,
                contactAddresses, assignments, internalCustomers,
                typeTargets, areaTargets,
                customerTypeCodes(tenantId, typeTargets.values()),
                customerAreaCodes(tenantId, areaTargets.values()),
                iamStaffTargets(records));
    }

    private boolean projectionComplete(UUID tenantId, UUID connectorId,
                                       CrmMasterDataObjectType type, UUID targetId,
                                       SourceRecord record) {
        return projectionComplete(tenantId, connectorId, type, targetId, record, null, null);
    }

    private boolean projectionComplete(UUID tenantId, UUID connectorId,
                                       CrmMasterDataObjectType type, UUID targetId,
                                       SourceRecord record,
                                       CustomerProjectionBatch customerProjectionBatch,
                                       AddressProjectionBatch addressProjectionBatch) {
        if (type == CrmMasterDataObjectType.CUSTOMER && customerProjectionBatch != null) {
            Boolean complete = customerProjectionBatch.complete(targetId, record);
            if (complete != null) return complete;
        }
        byte[] tenant = bytes(tenantId); byte[] id = bytes(targetId);
        return switch (type) {
            case CUSTOMER_TYPE -> customerTypeMapper.selectCount(Wrappers.<CustomerTypeEntity>query()
                    .eq("tenant_id", tenant).eq("id", id)) > 0;
            case CUSTOMER_AREA -> {
                CustomerAreaEntity area = customerAreaMapper.selectOne(Wrappers.<CustomerAreaEntity>query()
                        .eq("tenant_id", tenant).eq("id", id));
                String expectedName = value(record.sourceFields(), "AreaName", record.sourceName(), record.sourceId());
                String expectedParent = areaParentCode(record.sourceFields());
                boolean parentFieldPresent = hasAreaParentField(record.sourceFields());
                yield area != null && Objects.equals(area.areaName, expectedName)
                        && !isInvalidAreaParent(area.parentAreaCode)
                        && (!parentFieldPresent || Objects.equals(area.parentAreaCode, expectedParent));
            }
            case ADDRESS -> {
                if (addressProjectionBatch != null) {
                    yield addressProjectionBatch.complete(targetId, record.sourceFields());
                } else {
                    AddressEntity address = addressMapper.selectOne(Wrappers.<AddressEntity>query()
                            .eq("tenant_id", tenant).eq("id", id));
                    UUID expectedParty = customerTarget(tenantId, connectorId,
                            value(record.sourceFields(), "clientGuid"),
                            value(record.sourceFields(), "clientNum"),
                            value(record.sourceFields(), "clientId"));
                    yield address != null
                            && (expectedParty == null || Arrays.equals(address.partyId, bytes(expectedParty)))
                            && address.contactId != null
                            && contactMapper.selectCount(Wrappers.<ContactEntity>query()
                            .eq("tenant_id", tenant).eq("id", address.contactId)) > 0;
                }
            }
            case CUSTOMER -> customerProjectionComplete(tenantId, connectorId, id, record);
        };
    }

    private boolean customerProjectionComplete(UUID tenantId, UUID connectorId,
                                               byte[] partyId, SourceRecord record) {
        byte[] tenant = bytes(tenantId);
        if (partyMapper.selectCount(Wrappers.<PartyEntity>query()
                .eq("tenant_id", tenant).eq("id", partyId)) == 0) return false;
        CustomerProfileEntity profile = customerProfileMapper.selectOne(
                Wrappers.<CustomerProfileEntity>query()
                        .eq("tenant_id", tenant).eq("party_id", partyId));
        if (profile == null) return false;
        UUID expectedType = sourceTarget(tenantId, connectorId,
                CrmMasterDataObjectType.CUSTOMER_TYPE,
                value(record.sourceFields(), "clientType"));
        UUID expectedArea = sourceTarget(tenantId, connectorId,
                CrmMasterDataObjectType.CUSTOMER_AREA,
                value(record.sourceFields(), "clientArea"));
        if (expectedType != null && !Arrays.equals(profile.customerTypeId, bytes(expectedType))) return false;
        if (expectedArea != null && !Arrays.equals(profile.customerAreaId, bytes(expectedArea))) return false;
        if (customerPolicyMapper.selectCount(Wrappers.<CustomerPolicyEntity>query()
                .eq("tenant_id", tenant).eq("party_id", partyId)) == 0) return false;
        if (contactMapper.selectCount(Wrappers.<ContactEntity>query().eq("tenant_id", tenant)
                .eq("party_id", partyId).eq("contact_type", "PRIMARY")
                .eq("record_origin", "IMPORTED")) == 0) return false;
        if (addressMapper.selectCount(Wrappers.<AddressEntity>query().eq("tenant_id", tenant)
                .eq("party_id", partyId).eq("address_type", "CONTACT")
                .eq("record_origin", "IMPORTED")) == 0) return false;
        InternalCustomerEntity internalCustomer = internalCustomerMapper.selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId.toString())
                .eq(InternalCustomerEntity::getPartyId, partyId)
                .eq(InternalCustomerEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (internalCustomer == null) return false;
        if (!internalCustomerClassificationComplete(internalCustomer,
                expectedType == null ? null : customerTypeCode(tenantId, expectedType),
                expectedArea == null ? null : customerAreaCode(tenantId, expectedArea),
                expectedType != null, expectedArea != null)) {
            return false;
        }
        StaffRefs refs = staffRefs(record.sourceFields());
        String primarySourceStaffId = usableStaffId(refs.primary().sourceId());
        String primaryName = clean(refs.primary().name());
        String primaryStaffCode = iamStaff(record.sourceFields(), primarySourceStaffId,
                primaryName).staffCode();
        boolean primaryComplete = !refs.primaryFieldPresent()
                || assignmentComplete(tenantId, partyId, "PRIMARY", primarySourceStaffId,
                primaryName, primaryStaffCode);
        if (!primaryComplete) return false;
        if (!refs.secondaryFieldPresent()) return true;
        return refs.secondary().stream().filter(ref -> usableStaffId(ref.sourceId()) != null
                        || clean(ref.name()) != null)
                .allMatch(ref -> {
                    String sourceStaffId = usableStaffId(ref.sourceId());
                    String staffCode = iamStaff(record.sourceFields(), sourceStaffId,
                            clean(ref.name())).staffCode();
                    return assignmentComplete(tenantId, partyId, "SECONDARY", sourceStaffId,
                            clean(ref.name()), staffCode);
                });
    }

    private boolean assignmentComplete(UUID tenantId, byte[] partyId, String assignmentType,
                                       String sourceStaffId, String staffName, String staffCode) {
        var query = Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", partyId)
                .eq("assignment_type", assignmentType).eq("status", "ACTIVE");
        if (sourceStaffId != null) query.eq("source_staff_id", sourceStaffId);
        else if (staffName != null) query.eq("source_name_snapshot", staffName);
        else return true;
        if (staffCode != null) query.eq("iam_staff_code", staffCode);
        return assignmentMapper.selectCount(query) > 0;
    }

    private record AddressProjection(UUID partyId, UUID contactId) {
    }

    private record AddressProjectionBatch(Map<String, UUID> targetByAlias,
                                          Map<UUID, AddressProjection> addressById,
                                          Set<UUID> contactIds) {
        private AddressProjectionBatch {
            targetByAlias = targetByAlias == null ? Map.of() : Map.copyOf(targetByAlias);
            addressById = addressById == null ? Map.of() : Map.copyOf(addressById);
            contactIds = contactIds == null ? Set.of() : Set.copyOf(contactIds);
        }

        private UUID target(Map<String, Object> fields) {
            UUID target = target("GUID", value(fields, "clientGuid"));
            if (target != null) return target;
            target = target("NUM", value(fields, "clientNum"));
            return target != null ? target : target("ID", value(fields, "clientId"));
        }

        private UUID target(String type, String value) {
            if (value == null || value.isBlank() || targetByAlias.isEmpty()) return null;
            return targetByAlias.get(aliasKey(type, value.strip()));
        }

        private boolean complete(UUID addressId, Map<String, Object> fields) {
            AddressProjection projection = addressById.get(addressId);
            if (projection == null) return false;
            UUID expectedParty = target(fields);
            return (expectedParty == null || Objects.equals(projection.partyId(), expectedParty))
                    && projection.contactId() != null
                    && contactIds.contains(projection.contactId());
        }
    }

    private final class CustomerProjectionBatch {
        private final Set<UUID> parties;
        private final Map<UUID, CustomerProfileEntity> profiles;
        private final Set<UUID> policies;
        private final Set<UUID> primaryContacts;
        private final Set<UUID> contactAddresses;
        private final Map<UUID, List<SalesAssignmentEntity>> assignments;
        private final Map<UUID, InternalCustomerEntity> internalCustomers;
        private final Map<String, UUID> typeTargets;
        private final Map<String, UUID> areaTargets;
        private final Map<UUID, String> typeCodes;
        private final Map<UUID, String> areaCodes;
        private final Map<String, String> staffTargets;

        private CustomerProjectionBatch(Set<UUID> parties,
                                        Map<UUID, CustomerProfileEntity> profiles,
                                        Set<UUID> policies,
                                        Set<UUID> primaryContacts,
                                        Set<UUID> contactAddresses,
                                        Map<UUID, List<SalesAssignmentEntity>> assignments,
                                        Map<UUID, InternalCustomerEntity> internalCustomers,
                                        Map<String, UUID> typeTargets,
                                        Map<String, UUID> areaTargets,
                                        Map<UUID, String> typeCodes,
                                        Map<UUID, String> areaCodes,
                                        Map<String, String> staffTargets) {
            this.parties = parties == null ? Set.of() : Set.copyOf(parties);
            this.profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
            this.policies = policies == null ? Set.of() : Set.copyOf(policies);
            this.primaryContacts = primaryContacts == null ? Set.of() : Set.copyOf(primaryContacts);
            this.contactAddresses = contactAddresses == null ? Set.of() : Set.copyOf(contactAddresses);
            this.assignments = assignments == null ? Map.of() : Map.copyOf(assignments);
            this.internalCustomers = internalCustomers == null ? Map.of() : Map.copyOf(internalCustomers);
            this.typeTargets = typeTargets == null ? Map.of() : Map.copyOf(typeTargets);
            this.areaTargets = areaTargets == null ? Map.of() : Map.copyOf(areaTargets);
            this.typeCodes = typeCodes == null ? Map.of() : Map.copyOf(typeCodes);
            this.areaCodes = areaCodes == null ? Map.of() : Map.copyOf(areaCodes);
            this.staffTargets = staffTargets == null ? Map.of() : Map.copyOf(staffTargets);
        }

        private Boolean complete(UUID partyId, SourceRecord record) {
            if (partyId == null) return false;
            CustomerProfileEntity profile = profiles.get(partyId);
            InternalCustomerEntity internalCustomer = internalCustomers.get(partyId);
            if (!parties.contains(partyId) || profile == null || !policies.contains(partyId)
                    || !primaryContacts.contains(partyId) || !contactAddresses.contains(partyId)
                    || internalCustomer == null) {
                return false;
            }
            Map<String, Object> fields = record.sourceFields();
            UUID expectedType = target(typeTargets, value(fields, "clientType"));
            UUID expectedArea = target(areaTargets, value(fields, "clientArea"));
            if (expectedType != null && !Arrays.equals(profile.customerTypeId, bytes(expectedType))) return false;
            if (expectedArea != null && !Arrays.equals(profile.customerAreaId, bytes(expectedArea))) return false;
            if (!internalCustomerClassificationComplete(internalCustomer,
                    expectedType == null ? null : typeCodes.get(expectedType),
                    expectedArea == null ? null : areaCodes.get(expectedArea),
                    expectedType != null, expectedArea != null)) {
                return false;
            }

            StaffRefs refs = staffRefs(fields);
            String primarySourceStaffId = usableStaffId(refs.primary().sourceId());
            String primaryName = clean(refs.primary().name());
            if (refs.primaryFieldPresent() && !assignmentComplete(partyId, "PRIMARY",
                    primarySourceStaffId, primaryName, staffTarget(staffTargets, primarySourceStaffId))) {
                return false;
            }
            if (!refs.secondaryFieldPresent()) return true;
            for (StaffRef ref : refs.secondary()) {
                String sourceStaffId = usableStaffId(ref.sourceId());
                String staffName = clean(ref.name());
                if (sourceStaffId == null && staffName == null) continue;
                if (!assignmentComplete(partyId, "SECONDARY", sourceStaffId,
                        staffName, staffTarget(staffTargets, sourceStaffId))) return false;
            }
            return true;
        }

        private UUID target(Map<String, UUID> targets, String sourceId) {
            if (sourceId == null || targets == null || targets.isEmpty()) return null;
            return targets.get(sourceId);
        }

        private String staffTarget(Map<String, String> targets, String sourceId) {
            if (sourceId == null || targets == null || targets.isEmpty()) return null;
            return targets.get(sourceId);
        }

        private boolean assignmentComplete(UUID partyId, String assignmentType,
                                           String sourceStaffId, String staffName,
                                           String staffCode) {
            if (sourceStaffId == null && staffName == null) return true;
            for (SalesAssignmentEntity assignment : assignments.getOrDefault(partyId, List.of())) {
                if (!assignmentType.equals(assignment.assignmentType)) continue;
                if (sourceStaffId != null) {
                    if (!sourceStaffId.equals(assignment.sourceStaffId)) continue;
                } else if (staffName != null && !staffName.equals(assignment.sourceNameSnapshot)) {
                    continue;
                }
                if (staffCode != null && !staffCode.equals(assignment.iamStaffCode)) continue;
                return true;
            }
            return false;
        }
    }

    private UUID targetId(SourceBindingEntity binding) {
        return binding != null && binding.targetId != null ? uuid(binding.targetId) : CrmUuidCodec.next();
    }
    private SourceRecord snapshot(SourceBindingEntity binding, SourceRecord incoming) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (binding != null && binding.sourceFieldsJson != null) {
            fields.putAll(jsonMap(binding.sourceFieldsJson));
        }
        fields.putAll(incoming.sourceFields());
        return new SourceRecord(incoming.sourceId(),
                first(incoming.sourceCode(), binding == null ? null : binding.sourceCode),
                first(incoming.sourceName(), binding == null ? null : binding.sourceName),
                first(incoming.sourceStatus(), binding == null ? null : binding.sourceStatus),
                first(incoming.sourceCreatedAt(), binding == null
                        ? null : instant(binding.sourceCreatedAt)),
                first(incoming.sourceUpdatedAt(), binding == null
                        ? null : instant(binding.sourceUpdatedAt)), fields);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(normalize(value)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("来源字段无法序列化", e); }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return Collections.unmodifiableMap(objectMapper.readValue(value, LinkedHashMap.class)); }
        catch (RuntimeException e) { throw new IllegalStateException("CRM来源字段JSON损坏", e); }
    }
    private static Map<String, Object> storageSourceFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty() || !fields.containsKey(IAM_STAFF_BY_SOURCE_ID)) {
            return fields == null ? Map.of() : fields;
        }
        Map<String, Object> result = new LinkedHashMap<>(fields);
        result.remove(IAM_STAFF_BY_SOURCE_ID);
        return result;
    }
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String,Object> sorted = new TreeMap<>();
            map.forEach((k,v) -> sorted.put(String.valueOf(k), normalize(v))); return sorted; }
        if (value instanceof Iterable<?> values) { List<Object> list = new ArrayList<>();
            values.forEach(v -> list.add(normalize(v))); return list; }
        return value;
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JVM不支持SHA-256", e); }
    }
    private static Map<String,String> aliasMap(String... values) { Map<String,String> result = new LinkedHashMap<>();
        for (int i=0;i+1<values.length;i+=2) if (values[i+1]!=null && !values[i+1].isBlank()) result.put(values[i],values[i+1]);
        return result; }
    private static String value(Map<String,Object> f,String key,String... fallback) { Object raw=f.get(key);
        if(raw!=null){String text=String.valueOf(raw).strip();if(!text.isEmpty()&&!"null".equalsIgnoreCase(text))return text;}
        for(String value:fallback)if(value!=null&&!value.isBlank())return value.strip();return null; }
    @SafeVarargs
    private static <T> T first(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value instanceof String text) {
                if (!text.isBlank()) return value;
            } else if (value != null) return value;
        }
        return null;
    }
    private static String shortHash(String value) {
        return sha256(value == null ? "" : value).substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }
    private static String areaParentCode(Map<String, Object> fields) {
        String value = value(fields, "parentID", "parentId", "ParentID", "parent_id");
        if (value == null) return null;
        String normalized = value.strip();
        return Set.of("parentid", "parent_id", "parent-id", "null", "undefined")
                .contains(normalized.toLowerCase(java.util.Locale.ROOT)) ? null : normalized;
    }
    private static boolean hasAreaParentField(Map<String, Object> fields) {
        return fields.keySet().stream().anyMatch(key -> key != null
                && ("parentid".equalsIgnoreCase(key) || "parent_id".equalsIgnoreCase(key)));
    }
    private static boolean isInvalidAreaParent(String value) {
        return value != null && Set.of("parentid", "parent_id", "parent-id", "null", "undefined")
                .contains(value.strip().toLowerCase(java.util.Locale.ROOT));
    }
    private static String active(String value) { if(value==null)return "ACTIVE"; return Set.of("F","0","DISABLED","INACTIVE","停用","禁用")
            .contains(value.strip().toUpperCase(java.util.Locale.ROOT))?"INACTIVE":"ACTIVE"; }
    private static boolean bool(String value) { return value!=null&&Set.of("T","1","TRUE","Y","YES")
            .contains(value.strip().toUpperCase(java.util.Locale.ROOT)); }
    private static boolean bool(Object value) { return value instanceof Boolean b ? b : bool(value == null ? null : String.valueOf(value)); }
    private static String incoming(Map<String,Object> fields,String key,String current){
        return fields.containsKey(key)?value(fields,key):current;}
    private static <T> T first(T preferred,T fallback){return preferred==null?fallback:preferred;}
    private static String fullAddress(Map<String,Object> f){return fullAddress(value(f,"address"),value(f,"addressDetail"));}
    private static String fullAddress(String a,String d){return a==null?d:d==null?a:a+d;}
    private static byte[] bytes(UUID value){return CrmUuidCodec.encode(value);} private static UUID uuid(byte[] value){return CrmUuidCodec.decode(value);}
    private static UUID uuid(Object value){if(value instanceof byte[] bytes)return uuid(bytes);if(value instanceof UUID id)return id;return value==null?null:UUID.fromString(String.valueOf(value));}
    private static LocalDateTime local(Instant value){return value==null?null:LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static Instant instant(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);if(value instanceof LocalDateTime date)return date.toInstant(ZoneOffset.UTC);
        if(value instanceof Timestamp timestamp)return timestamp.toInstant();if(value instanceof Instant instant)return instant;return null;}
    private static String text(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:String.valueOf(value);}
    private static String clean(String value){return value==null||value.isBlank()?null:value.strip();}
    private static String like(String value){String clean=clean(value);return clean==null?null:"%"+clean+"%";}
    private static String safeCode(String value){String code=value==null?"SCHEDULE_SKIPPED":value.strip()
            .toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]","_");
        if(code.isEmpty())code="SCHEDULE_SKIPPED";return code.length()<=64?code:code.substring(0,64);}
    private static String safeSkipMessage(String value){String message=safeMessage(value);
        return message==null||message.isBlank()?"调度任务按策略跳过":message;}
    private static String safeMessage(String value){if(value==null)return null;String one=value.replace('\r',' ').replace('\n',' ');return one.length()<=2000?one:one.substring(0,2000);}
    private static String auditActor(UUID actorId){return actorId==null?SYSTEM_ACTOR:actorId.toString();}

    private record ExternalMappingSeed(SourceBindingEntity binding, UUID partyId, String sourceObjectNo) {
    }

    private record Target(String type, UUID id, String status, String errorCode, String errorMessage) {
        static Target resolved(String type,UUID id){return new Target(type,id,"RESOLVED",null,null);}
        static Target unresolved(String code,String message){return new Target(null,null,"UNRESOLVED",code,message);}
    }
}

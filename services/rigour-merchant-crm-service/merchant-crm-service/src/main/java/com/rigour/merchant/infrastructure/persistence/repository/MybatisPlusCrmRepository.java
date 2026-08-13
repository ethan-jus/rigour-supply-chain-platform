package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.merchant.api.v1.model.AddressView;
import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.ExternalStaffView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.merchant.api.v1.model.SalesAssignmentView;
import com.rigour.merchant.api.v1.model.CustomerSourceView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
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
import com.rigour.merchant.infrastructure.persistence.entity.ExternalStaffEntity;
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
import com.rigour.merchant.infrastructure.persistence.mapper.ExternalStaffMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyRoleMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SalesAssignmentMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceBindingMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceIdentityAliasMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
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
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";

    private final CustomerTypeMapper customerTypeMapper;
    private final CustomerAreaMapper customerAreaMapper;
    private final PartyMapper partyMapper;
    private final PartyRoleMapper partyRoleMapper;
    private final CustomerProfileMapper customerProfileMapper;
    private final CustomerPolicyMapper customerPolicyMapper;
    private final ContactMapper contactMapper;
    private final AddressMapper addressMapper;
    private final ExternalStaffMapper externalStaffMapper;
    private final SalesAssignmentMapper assignmentMapper;
    private final CrmSyncRunMapper syncRunMapper;
    private final CrmSyncCheckpointMapper checkpointMapper;
    private final CrmSyncLockMapper lockMapper;
    private final SourceBindingMapper bindingMapper;
    private final SourceIdentityAliasMapper aliasMapper;
    private final CrmQueryMapper queryMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MybatisPlusCrmRepository(
            CustomerTypeMapper customerTypeMapper, CustomerAreaMapper customerAreaMapper,
            PartyMapper partyMapper, PartyRoleMapper partyRoleMapper,
            CustomerProfileMapper customerProfileMapper, CustomerPolicyMapper customerPolicyMapper,
            ContactMapper contactMapper, AddressMapper addressMapper,
            ExternalStaffMapper externalStaffMapper, SalesAssignmentMapper assignmentMapper,
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
        this.externalStaffMapper = externalStaffMapper;
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
    public UUID startRun(UUID tenantId, UUID connectorId, UUID actorId,
                         CrmMasterDataObjectType objectType, int maxPages, String triggerType) {
        LocalDateTime now = now();
        lockMapper.delete(Wrappers.<CrmSyncLockEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("connector_id", bytes(connectorId))
                .eq("object_type", objectType.name())
                .le("expires_at", now));
        UUID runId = CrmUuidCodec.next();
        CrmSyncLockEntity lock = new CrmSyncLockEntity();
        lock.id = bytes(CrmUuidCodec.next()); lock.tenantId = bytes(tenantId);
        lock.connectorId = bytes(connectorId); lock.objectType = objectType.name();
        lock.runId = bytes(runId); lock.lockToken = UUID.randomUUID().toString();
        lock.acquiredAt = now; lock.expiresAt = now.plusHours(1);
        try {
            lockMapper.insert(lock);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "相同租户、连接器和数据类型已有同步任务运行中", List.of());
        }
        CrmSyncRunEntity run = new CrmSyncRunEntity();
        run.id = bytes(runId); run.tenantId = bytes(tenantId); run.connectorId = bytes(connectorId);
        run.sourceSystem = SOURCE_SYSTEM; run.objectType = objectType.name();
        run.triggerType = triggerType; run.syncMode = "FULL"; run.status = "RUNNING";
        run.pageSize = 500; run.maxPages = maxPages; run.fetchedCount = 0L;
        run.createdCount = 0L; run.changedCount = 0L; run.repairedCount = 0L;
        run.duplicateCount = 0L; run.absentCount = 0L; run.rejectedCount = 0L;
        run.startedAt = now; run.createdBy = bytes(actorId); run.createdAt = now; run.updatedAt = now;
        syncRunMapper.insert(run);
        return runId;
    }

    @Override
    @Transactional
    public ImportResult importRecord(UUID tenantId, UUID connectorId, UUID runId,
                                     CrmMasterDataObjectType type, SourceRecord record) {
        return importRecordInternal(tenantId, connectorId, runId, type, record);
    }

    @Override
    @Transactional
    public List<ImportResult> importRecords(UUID tenantId, UUID connectorId, UUID runId,
                                            CrmMasterDataObjectType type,
                                            List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        return records.stream()
                .map(record -> importRecordInternal(tenantId, connectorId, runId, type, record))
                .toList();
    }

    private ImportResult importRecordInternal(UUID tenantId, UUID connectorId, UUID runId,
                                              CrmMasterDataObjectType type, SourceRecord record) {
        LocalDateTime now = now();
        SourceBindingEntity binding = binding(tenantId, connectorId, type, record.sourceId(), true);
        SourceRecord snapshot = snapshot(binding, record);
        String json = json(snapshot.sourceFields());
        String hash = sha256(json);
        boolean sourceChanged = binding == null || !hash.equals(binding.sourcePayloadHash);
        if (binding != null && hash.equals(binding.sourcePayloadHash)
                && "RESOLVED".equals(binding.bindingStatus) && binding.targetId != null
                && projectionComplete(tenantId, connectorId, type,
                uuid(binding.targetId), snapshot)) {
            markSeen(binding, runId, snapshot, now);
            aliases(tenantId, connectorId, binding, type, snapshot, now);
            return ImportResult.duplicateOne();
        }

        Target target = switch (type) {
            case CUSTOMER_TYPE -> customerType(tenantId, binding, snapshot, now);
            case CUSTOMER_AREA -> customerArea(tenantId, binding, snapshot, now);
            case STAFF -> externalStaff(tenantId, connectorId, binding, snapshot, now);
            case CUSTOMER -> customer(tenantId, connectorId, binding, snapshot, now, sourceChanged);
            case ADDRESS -> address(tenantId, connectorId, binding, snapshot, now, sourceChanged);
        };
        boolean created = binding == null;
        if (binding == null) {
            binding = new SourceBindingEntity();
            binding.id = bytes(CrmUuidCodec.next()); binding.tenantId = bytes(tenantId);
            binding.connectorId = bytes(connectorId); binding.sourceSystem = SOURCE_SYSTEM;
            binding.sourceObjectType = type.name(); binding.sourceObjectId = record.sourceId();
            binding.createdAt = now; binding.version = 0L;
        }
        boolean repaired = !created && (hash.equals(binding.sourcePayloadHash)
                || !"RESOLVED".equals(binding.bindingStatus)) && "RESOLVED".equals(target.status());
        saveBinding(binding, runId, snapshot, target, json, hash, now, created);
        aliases(tenantId, connectorId, binding, type, snapshot, now);
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
            entity.typeCode = record.sourceId(); entity.typeName = name; entity.status = "ACTIVE";
            entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.version = 0L; entity.createdAt = now; entity.updatedAt = now;
            customerTypeMapper.insert(entity);
        } else if (record.sourceFields().containsKey("typeName")
                && name != null && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) {
            customerTypeMapper.update(null, Wrappers.<CustomerTypeEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id))
                    .set("type_name", name).set("status", "ACTIVE")
                    .setSql("version=version+1").set("updated_at", now));
        }
        return Target.resolved("CUSTOMER_TYPE", id);
    }

    private Target customerArea(UUID tenantId, SourceBindingEntity binding,
                                SourceRecord record, LocalDateTime now) {
        UUID id = targetId(binding);
        CustomerAreaEntity entity = customerAreaMapper.selectOne(Wrappers.<CustomerAreaEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id)));
        String name = value(record.sourceFields(), "AreaName", record.sourceName(), record.sourceId());
        String parentAreaCode = areaParentCode(record.sourceFields());
        boolean parentFieldPresent = hasAreaParentField(record.sourceFields());
        if (entity == null) {
            entity = new CustomerAreaEntity(); entity.id = bytes(id); entity.tenantId = bytes(tenantId);
            entity.areaCode = record.sourceId(); entity.areaName = name; entity.parentAreaCode = parentAreaCode;
            entity.status = "ACTIVE";
            entity.ownershipState = "EXTERNAL_PRIMARY"; entity.recordOrigin = "IMPORTED";
            entity.version = 0L; entity.createdAt = now; entity.updatedAt = now;
            customerAreaMapper.insert(entity);
        } else if ((record.sourceFields().containsKey("AreaName")
                || record.sourceFields().keySet().stream().anyMatch(key ->
                "parentID".equalsIgnoreCase(key) || "parent_id".equalsIgnoreCase(key)))
                && name != null && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) {
            var update = Wrappers.<CustomerAreaEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id))
                    .set("area_name", name)
                    .set("status", "ACTIVE")
                    .setSql("version=version+1").set("updated_at", now);
            // getArea 文档未保证返回 parentID；缺失时保留已知父级，避免一次不完整响应破坏层级。
            if (parentFieldPresent || isInvalidAreaParent(entity.parentAreaCode)) {
                update.set("parent_area_code", parentAreaCode);
            }
            customerAreaMapper.update(null, update);
        }
        return Target.resolved("CUSTOMER_AREA", id);
    }

    private Target externalStaff(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                                 SourceRecord record, LocalDateTime now) {
        UUID id = targetId(binding);
        ExternalStaffEntity entity = externalStaffMapper.selectOne(Wrappers.<ExternalStaffEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id)));
        boolean create = entity == null;
        if (create) {
            entity = new ExternalStaffEntity(); entity.id = bytes(id); entity.tenantId = bytes(tenantId);
            entity.connectorId = bytes(connectorId); entity.sourceSystem = SOURCE_SYSTEM;
            entity.sourceStaffId = record.sourceId(); entity.version = 0L; entity.createdAt = now;
        }
        Map<String, Object> f = record.sourceFields();
        entity.sourceAccountId = incoming(f, "accounts_id", entity.sourceAccountId);
        entity.accountName = incoming(f, "accounts_name", entity.accountName);
        entity.staffType = incoming(f, "staff_type", entity.staffType);
        entity.staffName = incoming(f, "staff_name", first(record.sourceName(), entity.staffName));
        entity.title = incoming(f, "title", entity.title);
        entity.branchName = incoming(f, "branch_name", entity.branchName);
        entity.accountMobile = incoming(f, "accounts_mobile", entity.accountMobile);
        entity.mobile = incoming(f, "mobile", entity.mobile);
        entity.email = incoming(f, "email", entity.email); entity.qq = incoming(f, "qq", entity.qq);
        entity.roleName = incoming(f, "role", entity.roleName);
        entity.inviteCode = incoming(f, "invite_code", entity.inviteCode);
        entity.remark = incoming(f, "about", entity.remark);
        entity.sourceStatus = f.containsKey("status")
                ? value(f, "status") : first(record.sourceStatus(), entity.sourceStatus);
        if (f.containsKey("create_date")) entity.sourceCreatedAt = local(record.sourceCreatedAt());
        if (f.containsKey("update_date")) entity.sourceUpdatedAt = local(record.sourceUpdatedAt());
        entity.updatedAt = now;
        if (create) externalStaffMapper.insert(entity);
        else externalStaffMapper.update(null, Wrappers.<ExternalStaffEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(id))
                .set("source_account_id", entity.sourceAccountId)
                .set("account_name", entity.accountName)
                .set("staff_type", entity.staffType)
                .set("staff_name", entity.staffName)
                .set("title", entity.title)
                .set("branch_name", entity.branchName)
                .set("account_mobile", entity.accountMobile)
                .set("mobile", entity.mobile).set("email", entity.email)
                .set("qq", entity.qq).set("role_name", entity.roleName)
                .set("invite_code", entity.inviteCode)
                .set("remark", entity.remark)
                .set("source_status", entity.sourceStatus)
                .set("source_created_at", entity.sourceCreatedAt)
                .set("source_updated_at", entity.sourceUpdatedAt)
                .setSql("version=version+1").set("updated_at", now));
        return Target.resolved("EXTERNAL_STAFF", id);
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
            party.version = 0L; party.createdAt = now; party.updatedAt = now; partyMapper.insert(party);
            PartyRoleEntity role = new PartyRoleEntity(); role.tenantId = bytes(tenantId);
            role.partyId = bytes(partyId); role.roleCode = "CUSTOMER"; role.status = "ACTIVE";
            role.effectiveFrom = now; role.createdAt = now; role.updatedAt = now; partyRoleMapper.insert(role);
        } else if (sourceChanged && !"INTERNAL_PRIMARY".equals(party.ownershipState)) {
            partyMapper.update(null, Wrappers.<PartyEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", bytes(partyId))
                    .set("party_code", incoming(f, "clientNO", party.partyCode))
                    .set("display_name", incoming(f, "clientCompanyName", party.displayName))
                    .set("internal_status", f.containsKey("clientStatus")
                            ? active(value(f, "clientStatus")) : party.internalStatus)
                    .setSql("version=version+1").set("updated_at", now));
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
        return Target.resolved("PARTY", partyId);
    }

    private Target address(UUID tenantId, UUID connectorId, SourceBindingEntity binding,
                           SourceRecord record, LocalDateTime now, boolean sourceChanged) {
        Map<String, Object> f = record.sourceFields();
        UUID addressId = targetId(binding);
        AddressEntity address = addressMapper.selectOne(Wrappers.<AddressEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(addressId)));
        UUID partyId = customerTarget(tenantId, connectorId,
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
            contact.recordOrigin = "IMPORTED"; contact.version = 0L; contact.createdAt = now;
            contact.updatedAt = now; contactMapper.insert(contact);
            address = new AddressEntity(); address.id = bytes(addressId); address.tenantId = bytes(tenantId);
            address.partyId = bytes(partyId); address.contactId = contact.id; address.addressType = "SHIPPING";
            address.consignee = value(f, "consignee"); address.regionText = value(f, "address");
            address.areaName = value(f, "areaName"); address.addressDetail = value(f, "addressDetail");
            address.fullAddress = fullAddress(f); address.isDefault = bool(value(f, "isDefault"));
            address.status = "ACTIVE"; address.ownershipState = "EXTERNAL_PRIMARY";
            address.recordOrigin = "IMPORTED"; address.version = 0L; address.createdAt = now;
            address.updatedAt = now; addressMapper.insert(address);
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
                contact.recordOrigin = "IMPORTED"; contact.version = 0L; contact.createdAt = now;
                contact.updatedAt = now; contactMapper.insert(contact);
                addressMapper.update(null, Wrappers.<AddressEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", bytes(addressId))
                        .set("contact_id", contact.id).set("updated_at", now));
            } else if ((sourceChanged || relationshipChanged)
                    && !"INTERNAL_PRIMARY".equals(contact.ownershipState)) {
                contactMapper.update(null, Wrappers.<ContactEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", contact.id)
                        .set("party_id", bytes(partyId))
                        .set("contact_name", incoming(f, "contact", contact.contactName))
                        .set("phone", incoming(f, "phone", contact.phone))
                        .set("is_primary", f.containsKey("isDefault")
                                ? bool(value(f, "isDefault")) : contact.isPrimary)
                        .setSql("version=version+1").set("updated_at", now));
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
                    .set("status", "ACTIVE").setSql("version=version+1")
                    .set("updated_at", now));
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
            entity.version = 0L; entity.createdAt = now; entity.updatedAt = now; customerProfileMapper.insert(entity);
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
                    .setSql("version=version+1").set("updated_at", now));
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
                    repair.setSql("version=version+1").set("updated_at", now));
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
            entity.version = 0L; entity.createdAt = now; entity.updatedAt = now; customerPolicyMapper.insert(entity);
        } else if (sourceChanged && f.containsKey("clientClearingForm")
                && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) customerPolicyMapper.update(null,
                Wrappers.<CustomerPolicyEntity>update().eq("tenant_id", bytes(tenantId))
                        .eq("party_id", bytes(partyId))
                        .set("settlement_mode", settlement)
                        .setSql("version=version+1").set("updated_at", now));
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
            entity.version = 0L; entity.createdAt = now;
        }
        entity.contactName = incoming(f, "clientTrueName", entity.contactName);
        entity.phone = incoming(f, "clientPhone", entity.phone);
        entity.email = incoming(f, "clientEmail", entity.email); entity.updatedAt = now;
        if (create) contactMapper.insert(entity);
        else if (sourceChanged && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) contactMapper.update(null,
                Wrappers.<ContactEntity>update().eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                        .set("contact_name", entity.contactName).set("phone", entity.phone)
                        .set("email", entity.email).setSql("version=version+1")
                        .set("updated_at", now));
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
            entity.recordOrigin = "IMPORTED"; entity.version = 0L; entity.createdAt = now;
            entity.updatedAt = now; addressMapper.insert(entity);
        } else if (sourceChanged && f.containsKey("clientAdd")
                && !"INTERNAL_PRIMARY".equals(entity.ownershipState)) addressMapper.update(null,
                Wrappers.<AddressEntity>update().eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                        .set("full_address", value(f, "clientAdd")).setSql("version=version+1")
                        .set("updated_at", now));
    }

    private void upsertAssignments(UUID tenantId, UUID connectorId, UUID partyId,
                                   Map<String, Object> fields, LocalDateTime now,
                                   boolean sourceChanged) {
        StaffRefs refs = staffRefs(fields);
        upsertAssignment(tenantId, connectorId, partyId, refs.primary().sourceId(),
                refs.primary().name(), refs.primaryFieldPresent(), now, sourceChanged);
        if (refs.secondaryFieldPresent()) {
            upsertSecondaryAssignments(tenantId, connectorId, partyId, refs.secondary(), now, sourceChanged);
        }
    }

    private void upsertAssignment(UUID tenantId, UUID connectorId, UUID partyId,
                                  String sourceStaffId, String staffName, boolean staffFieldPresent,
                                  LocalDateTime now, boolean sourceChanged) {
        if (!staffFieldPresent) return;
        sourceStaffId = usableStaffId(sourceStaffId);
        staffName = clean(staffName);
        UUID staffId = sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.STAFF, sourceStaffId);
        SalesAssignmentEntity current = assignmentMapper.selectOne(Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("assignment_type", "PRIMARY").eq("status", "ACTIVE").last("LIMIT 1"));
        if (sourceStaffId == null && staffName == null) {
            if (sourceChanged) deactivate(current, tenantId, now);
            return;
        }
        if (current != null && sameStaff(current, sourceStaffId, staffId, staffName)) {
            boolean resolvedStaffChanged = staffId != null
                    && !staffId.equals(uuid(current.externalStaffId));
            if (sourceChanged || resolvedStaffChanged) assignmentMapper.update(null, Wrappers.<SalesAssignmentEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", current.id)
                    .set("source_staff_id", sourceStaffId)
                    .set("external_staff_id", staffId == null ? null : bytes(staffId))
                    .set("source_name_snapshot", staffName == null
                            ? current.sourceNameSnapshot : staffName)
                    .setSql("version=version+1").set("updated_at", now));
            return;
        }
        deactivate(current, tenantId, now);
        SalesAssignmentEntity entity = new SalesAssignmentEntity(); entity.id = bytes(CrmUuidCodec.next());
        entity.tenantId = bytes(tenantId); entity.partyId = bytes(partyId); entity.assignmentType = "PRIMARY";
        entity.assigneeType = "EXTERNAL_STAFF";
        entity.externalStaffId = staffId == null ? null : bytes(staffId);
        entity.sourceStaffId = sourceStaffId;
        entity.source = "DHB_IMPORT"; entity.sourceNameSnapshot = staffName; entity.effectiveFrom = now;
        entity.status = "ACTIVE"; entity.version = 0L; entity.createdAt = now; entity.updatedAt = now;
        assignmentMapper.insert(entity);
    }

    private void upsertSecondaryAssignments(UUID tenantId, UUID connectorId, UUID partyId,
                                            List<StaffRef> incoming, LocalDateTime now,
                                            boolean sourceChanged) {
        List<SalesAssignmentEntity> current = assignmentMapper.selectList(Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", bytes(partyId))
                .eq("assignment_type", "SECONDARY").eq("status", "ACTIVE")
                .eq("source", "DHB_IMPORT"));
        Set<String> matched = new LinkedHashSet<>();
        for (StaffRef ref : incoming) {
            String sourceStaffId = usableStaffId(ref.sourceId());
            String staffName = clean(ref.name());
            if (sourceStaffId == null && staffName == null) continue;
            UUID staffId = sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.STAFF,
                    sourceStaffId);
            SalesAssignmentEntity existing = current.stream()
                    .filter(item -> sameStaff(item, sourceStaffId, staffId, staffName))
                    .findFirst().orElse(null);
            if (existing == null) {
                SalesAssignmentEntity entity = new SalesAssignmentEntity();
                entity.id = bytes(CrmUuidCodec.next()); entity.tenantId = bytes(tenantId);
                entity.partyId = bytes(partyId); entity.assignmentType = "SECONDARY";
                entity.assigneeType = "EXTERNAL_STAFF";
                entity.externalStaffId = staffId == null ? null : bytes(staffId);
                entity.sourceStaffId = sourceStaffId; entity.source = "DHB_IMPORT";
                entity.sourceNameSnapshot = staffName; entity.effectiveFrom = now;
                entity.status = "ACTIVE"; entity.version = 0L;
                entity.createdAt = now; entity.updatedAt = now;
                assignmentMapper.insert(entity);
            } else {
                matched.add(java.util.HexFormat.of().formatHex(existing.id));
                boolean resolvedStaffChanged = staffId != null
                        && !staffId.equals(uuid(existing.externalStaffId));
                if (sourceChanged || resolvedStaffChanged) assignmentMapper.update(null, Wrappers.<SalesAssignmentEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", existing.id)
                        .set("source_staff_id", sourceStaffId)
                        .set("external_staff_id", staffId == null ? null : bytes(staffId))
                        .set("source_name_snapshot", staffName == null
                                ? existing.sourceNameSnapshot : staffName)
                        .setSql("version=version+1").set("updated_at", now));
            }
        }
        if (sourceChanged) current.stream()
                .filter(item -> !matched.contains(java.util.HexFormat.of().formatHex(item.id)))
                .forEach(item -> deactivate(item, tenantId, now));
    }

    private static boolean sameStaff(SalesAssignmentEntity current, String sourceStaffId,
                                     UUID staffId, String staffName) {
        if (sourceStaffId != null && sourceStaffId.equals(current.sourceStaffId)) return true;
        if (staffId != null && staffId.equals(uuid(current.externalStaffId))) return true;
        return sourceStaffId == null && staffId == null && staffName != null
                && staffName.equals(current.sourceNameSnapshot);
    }

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
                .setSql("version=version+1").set("updated_at", now));
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
        binding.lastSyncRunId = bytes(runId); binding.syncedAt = now; binding.updatedAt = now;
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
                .setSql("version=version+1").set("updated_at", now));
    }

    private void markSeen(SourceBindingEntity binding, UUID runId, SourceRecord record, LocalDateTime now) {
        bindingMapper.update(null, Wrappers.<SourceBindingEntity>update()
                .eq("tenant_id", binding.tenantId).eq("id", binding.id)
                .set("source_presence", "PRESENT").set("absent_confirm_count", 0)
                .set("source_absent_at", null).set("last_seen_run_id", bytes(runId))
                .set("last_sync_run_id", bytes(runId)).set("synced_at", now)
                .set(record.sourceUpdatedAt() != null,
                        "source_updated_at", local(record.sourceUpdatedAt()))
                .set("updated_at", now));
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
            case STAFF -> aliasMap("STAFF_ID", value(record.sourceFields(), "staff_id", record.sourceId()),
                    "ACCOUNT_ID", value(record.sourceFields(), "accounts_id"), "ACCOUNT", value(record.sourceFields(), "accounts_name"));
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
                entity.firstSeenAt = now; entity.lastSeenAt = now; entity.createdAt = now; entity.updatedAt = now;
                aliasMapper.insert(entity);
            } else aliasMapper.update(null, Wrappers.<SourceIdentityAliasEntity>update()
                    .eq("tenant_id", bytes(tenantId)).eq("id", entity.id)
                    .set("last_seen_at", now).set("updated_at", now));
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
                    .setSql("version=version+1").set("updated_at", now));
        }
        return confirmed;
    }

    @Override
    @Transactional
    public RunStatistics completeRun(UUID tenantId, UUID connectorId, UUID runId,
                                     CrmMasterDataObjectType type, RunStatistics stats,
                                     boolean reconcileSourcePresence) {
        LocalDateTime now = now();
        long absent = reconcileSourcePresence
                ? reconcileSourcePresence(tenantId, connectorId, runId, type) : 0;
        RunStatistics finalized = new RunStatistics(stats.fetched(), stats.created(),
                stats.changed(), stats.repaired(), stats.duplicates(), absent,
                stats.rejected(), stats.pages());
        syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(runId))
                .set("status", "SUCCEEDED").set("fetched_count", finalized.fetched())
                .set("created_count", finalized.created()).set("changed_count", finalized.changed())
                .set("repaired_count", finalized.repaired()).set("duplicate_count", finalized.duplicates())
                .set("absent_count", finalized.absent()).set("rejected_count", finalized.rejected())
                .set("finished_at", now).set("updated_at", now));
        CrmSyncCheckpointEntity checkpoint = checkpointMapper.selectOne(Wrappers.<CrmSyncCheckpointEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("connector_id", bytes(connectorId))
                .eq("source_system", SOURCE_SYSTEM).eq("object_type", type.name()));
        if (checkpoint == null) {
            checkpoint = new CrmSyncCheckpointEntity(); checkpoint.id = bytes(CrmUuidCodec.next());
            checkpoint.tenantId = bytes(tenantId); checkpoint.connectorId = bytes(connectorId);
            checkpoint.sourceSystem = SOURCE_SYSTEM; checkpoint.objectType = type.name(); checkpoint.cursorType = "FULL_ONLY";
            checkpoint.lastSuccessRunId = bytes(runId); checkpoint.version = 0L; checkpoint.createdAt = now;
            checkpoint.updatedAt = now; checkpointMapper.insert(checkpoint);
        } else checkpointMapper.update(null, Wrappers.<CrmSyncCheckpointEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", checkpoint.id)
                .set("last_success_run_id", bytes(runId))
                .setSql("version=version+1").set("updated_at", now));
        releaseLock(tenantId, connectorId, type, runId);
        return finalized;
    }

    @Override
    @Transactional
    public void failRun(UUID tenantId, UUID connectorId, UUID runId,
                        RunStatistics stats, RuntimeException error) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<CrmSyncRunEntity>update()
                .eq("tenant_id", bytes(tenantId)).eq("id", bytes(runId))
                .set("status", "FAILED").set("fetched_count", stats.fetched())
                .set("created_count", stats.created()).set("changed_count", stats.changed())
                .set("repaired_count", stats.repaired()).set("duplicate_count", stats.duplicates())
                .set("absent_count", stats.absent()).set("rejected_count", stats.rejected())
                .set("error_code", error.getClass().getSimpleName())
                .set("error_message", safeMessage(error.getMessage()))
                .set("finished_at", now).set("updated_at", now));
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
                        text(row, "source_status"));
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
                        jsonMap(text(value, "source_fields_json")))).toList();
        return new CustomerDetailView(uuid(row.get("id")), text(row, "party_code"), text(row, "display_name"),
                text(row, "internal_status"), text(row, "login_account"), text(row, "type_name"),
                text(row, "area_name"), text(row, "city_text"), text(row, "inviter_name"), text(row, "remark"),
                text(row, "contact_name"), text(row, "phone"), text(row, "email"), text(row, "full_address"),
                text(row, "settlement_mode"), text(row, "source_name_snapshot"), assignments,
                text(row, "source_status"),
                instant(row, "source_created_at"), instant(row, "source_updated_at"), instant(row, "synced_at"),
                text(row, "source_presence"), addresses, sourceFields, source);
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
                            text(row, "source_staff_id"), text(row, "staff_name")));
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
                        text(row, "source_presence")))
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
                uuid(row.get("parent_id")), text(row, "parent_code"))).toList());
    }

    @Override public PageView<ExternalStaffView> externalStaff(UUID tenantId, int begin, int step, String query) {
        String search = like(query); long total = queryMapper.countExternalStaff(bytes(tenantId), search);
        List<ExternalStaffView> items = queryMapper.externalStaff(bytes(tenantId), begin, step, search).stream()
                .map(row -> new ExternalStaffView(uuid(row.get("id")), text(row, "source_staff_id"),
                        text(row, "source_account_id"), text(row, "account_name"), text(row, "staff_type"),
                        text(row, "staff_name"), text(row, "title"), text(row, "branch_name"),
                        text(row, "account_mobile"), text(row, "mobile"), text(row, "email"),
                        text(row, "role_name"), text(row, "source_status"), instant(row, "source_updated_at"),
                        instant(row, "synced_at"))).toList();
        return new PageView<>(total, begin, step, items);
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
        if (sourceId == null) return null;
        SourceBindingEntity binding = binding(tenantId, connectorId, type, sourceId, false);
        return binding != null && "RESOLVED".equals(binding.bindingStatus) ? uuid(binding.targetId) : null;
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

    private boolean projectionComplete(UUID tenantId, UUID connectorId,
                                       CrmMasterDataObjectType type, UUID targetId,
                                       SourceRecord record) {
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
            case STAFF -> externalStaffMapper.selectCount(Wrappers.<ExternalStaffEntity>query()
                    .eq("tenant_id", tenant).eq("id", id)) > 0;
            case ADDRESS -> {
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
        StaffRefs refs = staffRefs(record.sourceFields());
        String primarySourceStaffId = usableStaffId(refs.primary().sourceId());
        String primaryName = clean(refs.primary().name());
        boolean primaryComplete = !refs.primaryFieldPresent()
                || assignmentComplete(tenantId, partyId, "PRIMARY", primarySourceStaffId,
                primaryName, sourceTarget(tenantId, connectorId, CrmMasterDataObjectType.STAFF,
                        primarySourceStaffId));
        if (!primaryComplete) return false;
        if (!refs.secondaryFieldPresent()) return true;
        return refs.secondary().stream().filter(ref -> usableStaffId(ref.sourceId()) != null
                        || clean(ref.name()) != null)
                .allMatch(ref -> {
                    String sourceStaffId = usableStaffId(ref.sourceId());
                    UUID staffId = sourceTarget(tenantId, connectorId,
                            CrmMasterDataObjectType.STAFF, sourceStaffId);
                    return assignmentComplete(tenantId, partyId, "SECONDARY", sourceStaffId,
                            clean(ref.name()), staffId);
                });
    }

    private boolean assignmentComplete(UUID tenantId, byte[] partyId, String assignmentType,
                                       String sourceStaffId, String staffName, UUID externalStaffId) {
        var query = Wrappers.<SalesAssignmentEntity>query()
                .eq("tenant_id", bytes(tenantId)).eq("party_id", partyId)
                .eq("assignment_type", assignmentType).eq("status", "ACTIVE");
        if (sourceStaffId != null) query.eq("source_staff_id", sourceStaffId);
        else if (staffName != null) query.eq("source_name_snapshot", staffName);
        else return true;
        if (externalStaffId != null) query.eq("external_staff_id", bytes(externalStaffId));
        return assignmentMapper.selectCount(query) > 0;
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
    private static String safeMessage(String value){if(value==null)return null;String one=value.replace('\r',' ').replace('\n',' ');return one.length()<=2000?one:one.substring(0,2000);}

    private record Target(String type, UUID id, String status, String errorCode, String errorMessage) {
        static Target resolved(String type,UUID id){return new Target(type,id,"RESOLVED",null,null);}
        static Target unresolved(String code,String message){return new Target(null,null,"UNRESOLVED",code,message);}
    }
}

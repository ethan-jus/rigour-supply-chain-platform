package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncRowResult;
import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore.CustomerSearchCriteria;
import com.rigour.merchant.domain.code.CrmBusinessCodeRules;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import com.rigour.merchant.infrastructure.persistence.entity.AddressEntity;
import com.rigour.merchant.infrastructure.persistence.entity.ContactEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerAreaEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerProfileEntity;
import com.rigour.merchant.infrastructure.persistence.entity.CustomerTypeEntity;
import com.rigour.merchant.infrastructure.persistence.entity.InternalCustomerEntity;
import com.rigour.merchant.infrastructure.persistence.entity.PartyEntity;
import com.rigour.merchant.infrastructure.persistence.entity.PartyRoleEntity;
import com.rigour.merchant.infrastructure.persistence.mapper.AddressMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.ContactMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerAreaMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerProfileMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerTypeMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.InternalCustomerMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyRoleMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus CRM 自研客户仓储；同步维护客户闭环所需的 CRM 主数据投影。 */
@Repository
public class MybatisPlusInternalCustomerRepository
        extends ServiceImpl<InternalCustomerMapper, InternalCustomerEntity>
        implements CrmInternalCustomerStore {
    private final CustomerAreaMapper customerAreaMapper;
    private final CustomerTypeMapper customerTypeMapper;
    private final PartyMapper partyMapper;
    private final PartyRoleMapper partyRoleMapper;
    private final CustomerProfileMapper customerProfileMapper;
    private final ContactMapper contactMapper;
    private final AddressMapper addressMapper;
    private final Clock clock;

    public MybatisPlusInternalCustomerRepository(InternalCustomerMapper mapper,
                                                 CustomerAreaMapper customerAreaMapper,
                                                 CustomerTypeMapper customerTypeMapper,
                                                 PartyMapper partyMapper,
                                                 PartyRoleMapper partyRoleMapper,
                                                 CustomerProfileMapper customerProfileMapper,
                                                 ContactMapper contactMapper,
                                                 AddressMapper addressMapper,
                                                 Clock crmClock) {
        this.baseMapper = mapper;
        this.customerAreaMapper = customerAreaMapper;
        this.customerTypeMapper = customerTypeMapper;
        this.partyMapper = partyMapper;
        this.partyRoleMapper = partyRoleMapper;
        this.customerProfileMapper = customerProfileMapper;
        this.contactMapper = contactMapper;
        this.addressMapper = addressMapper;
        this.clock = crmClock;
    }

    @Override
    public PageView<InternalCustomerSummaryView> customers(String tenantId, int begin, int step,
                                                           CustomerSearchCriteria criteria) {
        InternalCustomerMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalCustomerSummaryView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalCustomerEntity::getUpdatedTime)
                        .orderByDesc(InternalCustomerEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusInternalCustomerRepository::summary)
                .toList();
        return new PageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalCustomerDetailView> customer(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                        .eq(InternalCustomerEntity::getTenantId, tenantId)
                        .eq(InternalCustomerEntity::getId, id)
                        .eq(InternalCustomerEntity::getDeleted, 0)
                        .last("LIMIT 1")))
                .map(MybatisPlusInternalCustomerRepository::detail);
    }

    @Override
    public boolean existsByCode(String tenantId, String customerCode) {
        return getBaseMapper().selectCount(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getCustomerCode, customerCode)) > 0;
    }

    @Override
    @Transactional
    public InternalCustomerDetailView create(String tenantId, String customerCode,
                                             InternalCustomerCommand command, String actorId) {
        LocalDateTime now = now();
        InternalCustomerEntity entity = new InternalCustomerEntity();
        entity.setTenantId(tenantId);
        entity.setCustomerCode(customerCode);
        entity.setCustomerName(command.customerName());
        entity.setContactName(command.contactName());
        entity.setContactPhone(command.contactPhone());
        entity.setCustomerTypeCode(command.customerTypeCode());
        entity.setRegionCode(command.regionCode());
        entity.setOwnerSalesUserId(command.ownerSalesUserId());
        entity.setOwnerSalesName(command.ownerSalesName());
        entity.setOwnerEmployeeCode(command.ownerEmployeeCode());
        entity.setOwnerEmployeeNameSnapshot(command.ownerEmployeeNameSnapshot());
        entity.setSettlementTypeCode(command.settlementTypeCode());
        entity.setAddress(command.address());
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
            throw conflict("客户编号已存在");
        }
        return customer(tenantId, entity.getId()).orElseThrow(() -> notFound("客户不存在"));
    }

    @Override
    @Transactional
    public InternalCustomerDetailView update(String tenantId, Long id,
                                             InternalCustomerCommand command, String actorId) {
        InternalCustomerEntity existing = requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getCustomerName, command.customerName())
                .set(InternalCustomerEntity::getContactName, command.contactName())
                .set(InternalCustomerEntity::getContactPhone, command.contactPhone())
                .set(InternalCustomerEntity::getCustomerTypeCode, command.customerTypeCode())
                .set(InternalCustomerEntity::getRegionCode, command.regionCode())
                .set(InternalCustomerEntity::getOwnerSalesUserId, command.ownerSalesUserId())
                .set(InternalCustomerEntity::getOwnerSalesName, command.ownerSalesName())
                .set(InternalCustomerEntity::getOwnerEmployeeCode, command.ownerEmployeeCode())
                .set(InternalCustomerEntity::getOwnerEmployeeNameSnapshot, command.ownerEmployeeNameSnapshot())
                .set(InternalCustomerEntity::getSettlementTypeCode, command.settlementTypeCode())
                .set(InternalCustomerEntity::getAddress, command.address())
                .set(InternalCustomerEntity::getStatusCode, command.statusCode())
                .set(InternalCustomerEntity::getRemark, command.remark())
                .set(InternalCustomerEntity::getRevision, command.revision() + 1)
                .set(InternalCustomerEntity::getUpdatedBy, actorId)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getRevision, command.revision())
                .eq(InternalCustomerEntity::getDeleted, 0));
        if (updated != 1) {
            throw conflict("客户已被其他人修改，请刷新后重试");
        }
        return customer(tenantId, existing.getId()).orElseThrow(() -> notFound("客户不存在"));
    }

    @Override
    @Transactional
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireActive(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getDeleted, 1)
                .set(InternalCustomerEntity::getRevision, revision + 1)
                .set(InternalCustomerEntity::getUpdatedBy, actorId)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getRevision, revision)
                .eq(InternalCustomerEntity::getDeleted, 0));
        if (updated != 1) {
            throw conflict("客户已被其他人修改，请刷新后重试");
        }
    }

    @Override
    public ExternalCrmCustomerSyncResult syncExternalCustomers(String tenantId, String sourceSystem,
                                                              List<ExternalCrmCustomerRowCommand> rows,
                                                              String actorId,
                                                              BusinessCodeGenerator codeGenerator) {
        Objects.requireNonNull(codeGenerator, "codeGenerator");
        List<ExternalCrmCustomerSyncRowResult> rowResults = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (ExternalCrmCustomerRowCommand row : rows == null
                ? List.<ExternalCrmCustomerRowCommand>of() : rows) {
            try {
                SyncOutcome outcome = syncExternalRow(tenantId, sourceSystem, row, actorId, codeGenerator);
                rowResults.add(new ExternalCrmCustomerSyncRowResult(outcome.sourceCustomerId(),
                        outcome.customerId(), outcome.customerCode(), outcome.status(), outcome.message()));
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
                String message = "CRM客户同步失败: " + clean(exception.getMessage(), 240);
                failureMessages.add(message);
                rowResults.add(new ExternalCrmCustomerSyncRowResult(
                        row == null ? null : row.sourceCustomerId(), null, null, "FAILED", message));
            }
        }
        return new ExternalCrmCustomerSyncResult(rowResults.size(), created, updated, unchanged,
                failed, rowResults, failureMessages);
    }

    private SyncOutcome syncExternalRow(String tenantId, String sourceSystem,
                                        ExternalCrmCustomerRowCommand row,
                                        String actorId,
                                        BusinessCodeGenerator codeGenerator) {
        LocalDateTime now = now();
        UUID tenantUuid = UUID.fromString(tenantId);
        String sourceTenantKey = defaultText(row.sourceTenantKey(), "DEFAULT", 128);
        InternalCustomerEntity existing = customerBySource(
                tenantId, sourceSystem, sourceTenantKey, row.sourceCustomerId());
        CustomerTypeEntity customerType = ensureCustomerType(
                tenantUuid, sourceSystem, row.businessCategoryName(),
                row.sourceCreatedAt(), actorId, codeGenerator, now);
        String customerTypeCode = customerType == null ? null : customerType.typeCode;
        String regionCode = resolveCustomerAreaCode(tenantId, sourceSystem, row, actorId, codeGenerator, now);
        if (existing == null) {
            String customerCode = codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER,
                    row.sourceCreatedAt(), candidate -> !existsByCode(tenantId, candidate));
            PartySync partySync = ensureParty(tenantUuid, sourceSystem, null, customerCode, row, actorId, now);
            InternalCustomerEntity entity = new InternalCustomerEntity();
            entity.setTenantId(tenantId);
            entity.setCustomerCode(customerCode);
            entity.setPartyId(partySync.entity().id);
            copyExternalCustomerFields(entity, sourceSystem, sourceTenantKey, row, customerTypeCode, regionCode);
            entity.setRevision(1);
            entity.setCreatedBy(actorId);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(actorId);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            getBaseMapper().insert(entity);
            upsertCustomerDirectoryProjection(tenantUuid, partySync.entity().id, customerType,
                    regionCode, sourceSystem, row, actorId, now);
            return new SyncOutcome(row.sourceCustomerId(), entity.getId(), entity.getCustomerCode(),
                    "CREATED", "CRM客户已创建");
        }
        PartySync partySync = ensureParty(tenantUuid, sourceSystem, existing.getPartyId(),
                existing.getCustomerCode(), row, actorId, now);
        byte[] previousPartyId = existing.getPartyId();
        String previousCustomerTypeCode = existing.getCustomerTypeCode();
        String previousRegionCode = existing.getRegionCode();
        boolean sourceUnchanged = sameHash(existing.getSourcePayloadHash(), row.sourcePayloadHash());
        copyExternalCustomerFields(existing, sourceSystem, sourceTenantKey, row, customerTypeCode, regionCode);
        existing.setPartyId(partySync.entity().id);
        boolean customerNeedsUpdate = !sourceUnchanged
                || !sameBytes(previousPartyId, existing.getPartyId())
                || !Objects.equals(previousCustomerTypeCode, existing.getCustomerTypeCode())
                || !Objects.equals(previousRegionCode, existing.getRegionCode());
        boolean directoryChanged = upsertCustomerDirectoryProjection(tenantUuid, partySync.entity().id,
                customerType, regionCode, sourceSystem, row, actorId, now);
        if (!customerNeedsUpdate && !partySync.changed() && !directoryChanged) {
            return new SyncOutcome(row.sourceCustomerId(), existing.getId(), existing.getCustomerCode(),
                    "UNCHANGED", "CRM客户无变化");
        }
        if (!customerNeedsUpdate && (partySync.changed() || directoryChanged)) {
            return new SyncOutcome(row.sourceCustomerId(), existing.getId(), existing.getCustomerCode(),
                    "UPDATED", "CRM客户关联主数据已补齐");
        }
        int nextRevision = existing.getRevision() == null ? 2 : existing.getRevision() + 1;
        int updated = getBaseMapper().update(null, Wrappers.<InternalCustomerEntity>lambdaUpdate()
                .set(InternalCustomerEntity::getCustomerName, existing.getCustomerName())
                .set(InternalCustomerEntity::getPartyId, existing.getPartyId())
                .set(InternalCustomerEntity::getContactName, existing.getContactName())
                .set(InternalCustomerEntity::getContactPhone, existing.getContactPhone())
                .set(InternalCustomerEntity::getCustomerTypeCode, existing.getCustomerTypeCode())
                .set(InternalCustomerEntity::getCustomerSourceName, existing.getCustomerSourceName())
                .set(InternalCustomerEntity::getBusinessCategoryName, existing.getBusinessCategoryName())
                .set(InternalCustomerEntity::getRegionCode, existing.getRegionCode())
                .set(InternalCustomerEntity::getRegionName, existing.getRegionName())
                .set(InternalCustomerEntity::getCityName, existing.getCityName())
                .set(InternalCustomerEntity::getOwnerEmployeeCode, existing.getOwnerEmployeeCode())
                .set(InternalCustomerEntity::getOwnerEmployeeNameSnapshot, existing.getOwnerEmployeeNameSnapshot())
                .set(InternalCustomerEntity::getOwnerSalesName, existing.getOwnerSalesName())
                .set(InternalCustomerEntity::getSettlementTypeCode, existing.getSettlementTypeCode())
                .set(InternalCustomerEntity::getAddress, existing.getAddress())
                .set(InternalCustomerEntity::getStatusCode, existing.getStatusCode())
                .set(InternalCustomerEntity::getRemark, existing.getRemark())
                .set(InternalCustomerEntity::getSourceSystemCode, existing.getSourceSystemCode())
                .set(InternalCustomerEntity::getSourceTenantKey, existing.getSourceTenantKey())
                .set(InternalCustomerEntity::getSourceCustomerId, existing.getSourceCustomerId())
                .set(InternalCustomerEntity::getSourceDocumentNo, existing.getSourceDocumentNo())
                .set(InternalCustomerEntity::getSourceCreatedAt, existing.getSourceCreatedAt())
                .set(InternalCustomerEntity::getSourceUpdatedAt, existing.getSourceUpdatedAt())
                .set(InternalCustomerEntity::getSourcePayloadHash, existing.getSourcePayloadHash())
                .set(InternalCustomerEntity::getSourcePayloadJson, existing.getSourcePayloadJson())
                .set(InternalCustomerEntity::getRevision, nextRevision)
                .set(InternalCustomerEntity::getUpdatedBy, actorId)
                .set(InternalCustomerEntity::getUpdatedTime, now)
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, existing.getId())
                .eq(InternalCustomerEntity::getDeleted, 0));
        if (updated != 1) throw conflict("CRM客户已被其他流程修改，请重试");
        return new SyncOutcome(row.sourceCustomerId(), existing.getId(), existing.getCustomerCode(),
                "UPDATED", sourceUnchanged ? "CRM客户关联主数据已补齐" : "CRM客户已更新");
    }

    private InternalCustomerEntity requireActive(String tenantId, Long id) {
        InternalCustomerEntity entity = getBaseMapper().selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getId, id)
                .eq(InternalCustomerEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (entity == null) throw notFound("客户不存在");
        return entity;
    }

    private LambdaQueryWrapper<InternalCustomerEntity> query(String tenantId, CustomerSearchCriteria criteria) {
        LambdaQueryWrapper<InternalCustomerEntity> query = Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getDeleted, 0);
        if (criteria.customerCode() != null) {
            query.like(InternalCustomerEntity::getCustomerCode, criteria.customerCode());
        }
        if (criteria.customerName() != null) {
            query.like(InternalCustomerEntity::getCustomerName, criteria.customerName());
        }
        if (criteria.contactPhone() != null) {
            query.like(InternalCustomerEntity::getContactPhone, criteria.contactPhone());
        }
        if (criteria.customerTypeCode() != null) {
            query.eq(InternalCustomerEntity::getCustomerTypeCode, criteria.customerTypeCode());
        }
        if (criteria.regionCode() != null) {
            query.eq(InternalCustomerEntity::getRegionCode, criteria.regionCode());
        }
        if (criteria.ownerSalesUserId() != null) {
            query.eq(InternalCustomerEntity::getOwnerSalesUserId, criteria.ownerSalesUserId());
        }
        if (criteria.ownerEmployeeCode() != null) {
            query.eq(InternalCustomerEntity::getOwnerEmployeeCode, criteria.ownerEmployeeCode());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalCustomerEntity::getStatusCode, criteria.statusCode());
        }
        return query;
    }

    private InternalCustomerEntity customerBySource(String tenantId, String sourceSystem,
                                                    String sourceTenantKey,
                                                    String sourceCustomerId) {
        return getBaseMapper().selectOne(Wrappers.<InternalCustomerEntity>lambdaQuery()
                .eq(InternalCustomerEntity::getTenantId, tenantId)
                .eq(InternalCustomerEntity::getSourceSystemCode, sourceSystem)
                .eq(InternalCustomerEntity::getSourceTenantKey, sourceTenantKey)
                .eq(InternalCustomerEntity::getSourceCustomerId, sourceCustomerId)
                .eq(InternalCustomerEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private void copyExternalCustomerFields(InternalCustomerEntity entity, String sourceSystem,
                                            String sourceTenantKey,
                                            ExternalCrmCustomerRowCommand row,
                                            String customerTypeCode,
                                            String regionCode) {
        entity.setCustomerName(clean(row.customerName(), 200));
        entity.setContactName(clean(row.contactName(), 100));
        entity.setContactPhone(clean(row.contactPhone(), 50));
        entity.setCustomerTypeCode(customerTypeCode);
        entity.setCustomerSourceName(clean(row.customerSourceName(), 120));
        entity.setBusinessCategoryName(clean(row.businessCategoryName(), 120));
        entity.setRegionCode(regionCode);
        entity.setRegionName(clean(row.regionName(), 80));
        entity.setCityName(clean(row.cityName(), 80));
        entity.setOwnerEmployeeCode(clean(row.ownerEmployeeCode(), 50));
        String ownerName = clean(row.ownerEmployeeNameSnapshot(), 100);
        entity.setOwnerEmployeeNameSnapshot(ownerName);
        entity.setOwnerSalesName(ownerName);
        entity.setSettlementTypeCode(clean(row.settlementTypeCode(), 64));
        entity.setAddress(clean(row.address(), 1000));
        entity.setStatusCode(statusCode(row.statusName()));
        entity.setRemark("外部来源：" + sourceSystem);
        entity.setSourceSystemCode(sourceSystem);
        entity.setSourceTenantKey(sourceTenantKey);
        entity.setSourceCustomerId(clean(row.sourceCustomerId(), 128));
        entity.setSourceDocumentNo(clean(row.sourceDocumentNo(), 128));
        entity.setSourceCreatedAt(time(row.sourceCreatedAt()));
        entity.setSourceUpdatedAt(time(row.sourceUpdatedAt()));
        entity.setSourcePayloadHash(clean(row.sourcePayloadHash(), 64));
        entity.setSourcePayloadJson(clean(row.sourcePayloadJson(), 20_000));
    }

    private CustomerTypeEntity ensureCustomerType(UUID tenantId, String sourceSystem, String sourceTypeName,
                                                  Instant sourceCreatedAt, String actorId,
                                                  BusinessCodeGenerator codeGenerator, LocalDateTime now) {
        String typeName = clean(sourceTypeName, 160);
        if (typeName == null) return null;
        CustomerTypeEntity existing = customerTypeMapper.selectOne(Wrappers.<CustomerTypeEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("type_name", typeName)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!"ACTIVE".equals(existing.status) && !"INTERNAL_PRIMARY".equals(existing.ownershipState)) {
                customerTypeMapper.update(null, Wrappers.<CustomerTypeEntity>update()
                        .eq("tenant_id", bytes(tenantId)).eq("id", existing.id)
                        .set("status", "ACTIVE")
                        .set("record_origin", sourceSystem)
                        .set("updated_by", actorId)
                        .set("updated_time", now)
                        .setSql("revision=revision+1"));
                existing.status = "ACTIVE";
                existing.recordOrigin = sourceSystem;
            }
            return existing;
        }
        CustomerTypeEntity created = new CustomerTypeEntity();
        created.id = bytes(CrmUuidCodec.next());
        created.tenantId = bytes(tenantId);
        created.typeCode = codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER_TYPE,
                sourceCreatedAt, candidate -> customerTypeMapper.selectCount(Wrappers.<CustomerTypeEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("type_code", candidate)) == 0);
        created.typeName = typeName;
        created.status = "ACTIVE";
        created.ownershipState = "EXTERNAL_PRIMARY";
        created.recordOrigin = sourceSystem;
        created.revision = 1L;
        created.createdBy = actorId;
        created.createdTime = now;
        created.updatedBy = actorId;
        created.updatedTime = now;
        created.deleted = 0;
        customerTypeMapper.insert(created);
        return created;
    }

    private PartySync ensureParty(UUID tenantId, String sourceSystem, byte[] currentPartyId,
                                  String customerCode, ExternalCrmCustomerRowCommand row,
                                  String actorId, LocalDateTime now) {
        String partyCode = clean(customerCode, 128);
        String displayName = clean(row.customerName(), 240);
        String status = statusCode(row.statusName());
        PartyEntity existing = currentPartyId == null ? null : partyMapper.selectOne(Wrappers.<PartyEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("id", currentPartyId)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (existing == null && partyCode != null) {
            existing = partyMapper.selectOne(Wrappers.<PartyEntity>query()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("party_code", partyCode)
                    .eq("deleted", 0)
                    .last("LIMIT 1"));
        }
        if (existing != null) {
            boolean changed = false;
            var update = Wrappers.<PartyEntity>update()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("id", existing.id);
            if (displayName != null && !Objects.equals(existing.displayName, displayName)
                    && !"INTERNAL_PRIMARY".equals(existing.ownershipState)) {
                update.set("display_name", displayName);
                existing.displayName = displayName;
                changed = true;
            }
            if (!Objects.equals(existing.internalStatus, status)
                    && !"INTERNAL_PRIMARY".equals(existing.ownershipState)) {
                update.set("internal_status", status);
                existing.internalStatus = status;
                changed = true;
            }
            if (changed) {
                update.set("updated_by", actorId)
                        .set("updated_time", now)
                        .setSql("revision=revision+1");
                partyMapper.update(null, update);
            }
            return new PartySync(existing, changed);
        }
        PartyEntity created = new PartyEntity();
        created.id = currentPartyId == null ? bytes(CrmUuidCodec.next()) : currentPartyId;
        created.tenantId = bytes(tenantId);
        created.partyCode = partyCode;
        created.displayName = displayName == null ? partyCode : displayName;
        created.partyKind = "ORGANIZATION";
        created.internalStatus = status;
        created.ownershipState = "EXTERNAL_PRIMARY";
        created.recordOrigin = sourceSystem;
        created.revision = 1L;
        created.createdBy = actorId;
        created.createdTime = now;
        created.updatedBy = actorId;
        created.updatedTime = now;
        created.deleted = 0;
        partyMapper.insert(created);
        return new PartySync(created, true);
    }

    private boolean upsertCustomerDirectoryProjection(UUID tenantId, byte[] partyId,
                                                      CustomerTypeEntity customerType, String regionCode,
                                                      String sourceSystem, ExternalCrmCustomerRowCommand row,
                                                      String actorId, LocalDateTime now) {
        boolean changed = ensurePartyRole(tenantId, partyId, row.sourceCreatedAt(), actorId, now);
        CustomerAreaEntity area = regionCode == null ? null : customerAreaMapper.selectOne(
                Wrappers.<CustomerAreaEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("area_code", regionCode)
                        .eq("deleted", 0)
                        .last("LIMIT 1"));
        changed |= upsertCustomerProfile(tenantId, partyId, customerType, area, row, actorId, now);
        String contactName = firstText(row.contactName(), row.customerName());
        changed |= upsertContact(tenantId, partyId, sourceSystem, "PRIMARY", true,
                contactName, row.contactPhone(), actorId, now).changed();
        changed |= upsertShippingAddress(tenantId, partyId, sourceSystem, row, actorId, now);
        return changed;
    }

    private boolean ensurePartyRole(UUID tenantId, byte[] partyId, Instant sourceCreatedAt,
                                    String actorId, LocalDateTime now) {
        PartyRoleEntity existing = partyRoleMapper.selectOne(Wrappers.<PartyRoleEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("party_id", partyId)
                .eq("role_code", "CUSTOMER")
                .last("LIMIT 1"));
        if (existing == null) {
            PartyRoleEntity created = new PartyRoleEntity();
            created.tenantId = bytes(tenantId);
            created.partyId = partyId;
            created.roleCode = "CUSTOMER";
            created.status = "ACTIVE";
            created.effectiveFrom = sourceCreatedAt == null ? now : time(sourceCreatedAt);
            created.revision = 1;
            created.createdBy = actorId;
            created.createdTime = now;
            created.updatedBy = actorId;
            created.updatedTime = now;
            created.deleted = 0;
            partyRoleMapper.insert(created);
            return true;
        }
        if (!"ACTIVE".equals(existing.status) || !Objects.equals(existing.deleted, 0)) {
            partyRoleMapper.update(null, Wrappers.<PartyRoleEntity>update()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("party_id", partyId)
                    .eq("role_code", "CUSTOMER")
                    .set("status", "ACTIVE")
                    .set("deleted", 0)
                    .set("updated_by", actorId)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
            return true;
        }
        return false;
    }

    private boolean upsertCustomerProfile(UUID tenantId, byte[] partyId, CustomerTypeEntity customerType,
                                          CustomerAreaEntity area, ExternalCrmCustomerRowCommand row,
                                          String actorId, LocalDateTime now) {
        CustomerProfileEntity existing = customerProfileMapper.selectOne(Wrappers.<CustomerProfileEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("party_id", partyId)
                .last("LIMIT 1"));
        byte[] customerTypeId = customerType == null ? null : customerType.id;
        byte[] customerAreaId = area == null ? null : area.id;
        String typeName = customerType == null ? clean(row.businessCategoryName(), 160) : customerType.typeName;
        String areaName = area == null ? firstText(row.cityName(), row.regionName()) : area.areaName;
        String cityText = clean(row.cityName(), 500);
        if (existing == null) {
            CustomerProfileEntity created = new CustomerProfileEntity();
            created.tenantId = bytes(tenantId);
            created.partyId = partyId;
            created.customerTypeId = customerTypeId;
            created.customerAreaId = customerAreaId;
            created.customerTypeNameSnapshot = typeName;
            created.customerAreaNameSnapshot = clean(areaName, 160);
            created.cityText = cityText;
            created.remark = "外部来源：" + defaultText(row.sourceTenantKey(), "FEISHU", 120);
            created.revision = 1L;
            created.createdBy = actorId;
            created.createdTime = now;
            created.updatedBy = actorId;
            created.updatedTime = now;
            created.deleted = 0;
            customerProfileMapper.insert(created);
            return true;
        }
        boolean changed = !sameBytes(existing.customerTypeId, customerTypeId)
                || !sameBytes(existing.customerAreaId, customerAreaId)
                || !Objects.equals(existing.customerTypeNameSnapshot, typeName)
                || !Objects.equals(existing.customerAreaNameSnapshot, clean(areaName, 160))
                || !Objects.equals(existing.cityText, cityText)
                || !Objects.equals(existing.deleted, 0);
        if (!changed) return false;
        customerProfileMapper.update(null, Wrappers.<CustomerProfileEntity>update()
                .eq("tenant_id", bytes(tenantId))
                .eq("party_id", partyId)
                .set("customer_type_id", customerTypeId)
                .set("customer_area_id", customerAreaId)
                .set("customer_type_name_snapshot", typeName)
                .set("customer_area_name_snapshot", clean(areaName, 160))
                .set("city_text", cityText)
                .set("deleted", 0)
                .set("updated_by", actorId)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        return true;
    }

    private ContactSync upsertContact(UUID tenantId, byte[] partyId, String sourceSystem,
                                      String contactType, boolean primary,
                                      String contactName, String phone,
                                      String actorId, LocalDateTime now) {
        String name = clean(contactName, 160);
        String contactPhone = clean(phone, 128);
        if (name == null && contactPhone == null) return new ContactSync(null, false);
        ContactEntity existing = contactMapper.selectOne(Wrappers.<ContactEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("party_id", partyId)
                .eq("contact_type", contactType)
                .eq("record_origin", sourceSystem)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (existing == null) {
            ContactEntity created = new ContactEntity();
            created.id = bytes(CrmUuidCodec.next());
            created.tenantId = bytes(tenantId);
            created.partyId = partyId;
            created.contactType = contactType;
            created.contactName = name;
            created.phone = contactPhone;
            created.isPrimary = primary;
            created.status = "ACTIVE";
            created.ownershipState = "EXTERNAL_PRIMARY";
            created.recordOrigin = sourceSystem;
            created.revision = 1L;
            created.createdBy = actorId;
            created.createdTime = now;
            created.updatedBy = actorId;
            created.updatedTime = now;
            created.deleted = 0;
            contactMapper.insert(created);
            return new ContactSync(created, true);
        }
        boolean changed = !Objects.equals(existing.contactName, name)
                || !Objects.equals(existing.phone, contactPhone)
                || !Objects.equals(existing.isPrimary, primary)
                || !"ACTIVE".equals(existing.status);
        if (changed && !"INTERNAL_PRIMARY".equals(existing.ownershipState)) {
            contactMapper.update(null, Wrappers.<ContactEntity>update()
                    .eq("tenant_id", bytes(tenantId))
                    .eq("id", existing.id)
                    .set("contact_name", name)
                    .set("phone", contactPhone)
                    .set("is_primary", primary)
                    .set("status", "ACTIVE")
                    .set("updated_by", actorId)
                    .set("updated_time", now)
                    .setSql("revision=revision+1"));
            existing.contactName = name;
            existing.phone = contactPhone;
            existing.isPrimary = primary;
            existing.status = "ACTIVE";
            return new ContactSync(existing, true);
        }
        return new ContactSync(existing, false);
    }

    private boolean upsertShippingAddress(UUID tenantId, byte[] partyId, String sourceSystem,
                                          ExternalCrmCustomerRowCommand row,
                                          String actorId, LocalDateTime now) {
        String addressDetail = meaningfulAddress(row.address());
        if (addressDetail == null) return false;
        ContactSync contact = upsertContact(tenantId, partyId, sourceSystem, "SHIPPING", false,
                firstText(row.contactName(), row.customerName()), row.contactPhone(), actorId, now);
        String consignee = clean(firstText(row.customerName(), row.contactName()), 240);
        String regionText = clean(row.cityName(), 500);
        String areaName = clean(row.regionName(), 500);
        String fullAddress = clean(addressDetail, 1500);
        AddressEntity existing = addressMapper.selectOne(Wrappers.<AddressEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("party_id", partyId)
                .eq("address_type", "SHIPPING")
                .eq("record_origin", sourceSystem)
                .eq("deleted", 0)
                .last("LIMIT 1"));
        if (existing == null) {
            AddressEntity created = new AddressEntity();
            created.id = bytes(CrmUuidCodec.next());
            created.tenantId = bytes(tenantId);
            created.partyId = partyId;
            created.contactId = contact.entity() == null ? null : contact.entity().id;
            created.addressType = "SHIPPING";
            created.consignee = consignee;
            created.regionText = regionText;
            created.areaName = areaName;
            created.addressDetail = addressDetail;
            created.fullAddress = fullAddress;
            created.isDefault = true;
            created.status = "ACTIVE";
            created.ownershipState = "EXTERNAL_PRIMARY";
            created.recordOrigin = sourceSystem;
            created.revision = 1L;
            created.createdBy = actorId;
            created.createdTime = now;
            created.updatedBy = actorId;
            created.updatedTime = now;
            created.deleted = 0;
            addressMapper.insert(created);
            return true;
        }
        boolean changed = contact.changed()
                || !sameBytes(existing.contactId, contact.entity() == null ? null : contact.entity().id)
                || !Objects.equals(existing.consignee, consignee)
                || !Objects.equals(existing.regionText, regionText)
                || !Objects.equals(existing.areaName, areaName)
                || !Objects.equals(existing.addressDetail, addressDetail)
                || !Objects.equals(existing.fullAddress, fullAddress)
                || !Objects.equals(existing.isDefault, true)
                || !"ACTIVE".equals(existing.status);
        if (!changed) return false;
        addressMapper.update(null, Wrappers.<AddressEntity>update()
                .eq("tenant_id", bytes(tenantId))
                .eq("id", existing.id)
                .set("contact_id", contact.entity() == null ? null : contact.entity().id)
                .set("consignee", consignee)
                .set("region_text", regionText)
                .set("area_name", areaName)
                .set("address_detail", addressDetail)
                .set("full_address", fullAddress)
                .set("is_default", true)
                .set("status", "ACTIVE")
                .set("updated_by", actorId)
                .set("updated_time", now)
                .setSql("revision=revision+1"));
        return true;
    }

    private String resolveCustomerAreaCode(String tenantId, String sourceSystem,
                                           ExternalCrmCustomerRowCommand row,
                                           String actorId,
                                           BusinessCodeGenerator codeGenerator,
                                           LocalDateTime now) {
        String regionName = clean(row.regionName(), 80);
        String cityName = clean(row.cityName(), 80);
        if (regionName == null && cityName == null) return null;
        UUID tenantUuid = UUID.fromString(tenantId);
        CustomerAreaEntity region = regionName == null ? null : ensureArea(
                tenantUuid, sourceSystem, null, regionName, row.sourceCreatedAt(), actorId, codeGenerator, now);
        if (cityName == null) return region == null ? null : region.areaCode;
        CustomerAreaEntity city = ensureArea(tenantUuid, sourceSystem,
                region == null ? null : region.areaCode, cityName,
                row.sourceCreatedAt(), actorId, codeGenerator, now);
        return city.areaCode;
    }

    private CustomerAreaEntity ensureArea(UUID tenantId, String sourceSystem, String parentAreaCode,
                                          String areaName, Instant sourceCreatedAt,
                                          String actorId, BusinessCodeGenerator codeGenerator,
                                          LocalDateTime now) {
        var query = Wrappers.<CustomerAreaEntity>query()
                .eq("tenant_id", bytes(tenantId))
                .eq("area_name", areaName)
                .eq("deleted", 0);
        if (parentAreaCode == null) query.isNull("parent_area_code");
        else query.eq("parent_area_code", parentAreaCode);
        CustomerAreaEntity existing = customerAreaMapper.selectOne(query.last("LIMIT 1"));
        if (existing != null) return existing;
        CustomerAreaEntity created = new CustomerAreaEntity();
        created.id = bytes(CrmUuidCodec.next());
        created.tenantId = bytes(tenantId);
        created.areaCode = codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER_AREA,
                sourceCreatedAt, candidate -> customerAreaMapper.selectCount(Wrappers.<CustomerAreaEntity>query()
                        .eq("tenant_id", bytes(tenantId))
                        .eq("area_code", candidate)) == 0);
        created.areaName = areaName;
        created.parentAreaCode = parentAreaCode;
        created.status = "ACTIVE";
        created.ownershipState = "EXTERNAL_PRIMARY";
        created.recordOrigin = sourceSystem;
        created.revision = 1L;
        created.createdBy = actorId;
        created.createdTime = now;
        created.updatedBy = actorId;
        created.updatedTime = now;
        created.deleted = 0;
        customerAreaMapper.insert(created);
        return created;
    }

    private static String meaningfulAddress(String sourceAddress) {
        String address = clean(sourceAddress, 1000);
        if (address == null) return null;
        String compact = address
                .replaceAll("(?m)(收件人|联系人|联系电话|联系方式|电话|地址)[:：]\\s*", "")
                .replaceAll("[\\s\\r\\n]+", "");
        return compact.isBlank() ? null : address;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String cleaned = clean(value, 1000);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private static boolean sameBytes(byte[] current, byte[] incoming) {
        return Arrays.equals(current, incoming);
    }

    private static String statusCode(String sourceStatusName) {
        String value = clean(sourceStatusName, 80);
        if (value == null) return "ACTIVE";
        if (value.contains("停") || value.contains("闭") || value.contains("终止")
                || value.contains("失效") || value.contains("暂未合作")) {
            return "INACTIVE";
        }
        return "ACTIVE";
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static InternalCustomerSummaryView summary(InternalCustomerEntity entity) {
        return new InternalCustomerSummaryView(entity.getId(), entity.getCustomerCode(), entity.getCustomerName(),
                entity.getContactName(), entity.getContactPhone(), entity.getCustomerTypeCode(), entity.getRegionCode(),
                entity.getRegionName(), entity.getCityName(), entity.getCustomerSourceName(),
                entity.getBusinessCategoryName(), entity.getOwnerSalesUserId(), entity.getOwnerSalesName(),
                entity.getOwnerEmployeeCode(), entity.getOwnerEmployeeNameSnapshot(), entity.getSettlementTypeCode(),
                entity.getStatusCode(), entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                instant(entity.getSourceCreatedAt()), instant(entity.getSourceUpdatedAt()),
                entity.getRevision(), instant(entity.getUpdatedTime()));
    }

    private static InternalCustomerDetailView detail(InternalCustomerEntity entity) {
        return new InternalCustomerDetailView(entity.getId(), entity.getCustomerCode(), entity.getCustomerName(),
                entity.getContactName(), entity.getContactPhone(), entity.getCustomerTypeCode(), entity.getRegionCode(),
                entity.getRegionName(), entity.getCityName(), entity.getCustomerSourceName(),
                entity.getBusinessCategoryName(), entity.getOwnerSalesUserId(), entity.getOwnerSalesName(),
                entity.getOwnerEmployeeCode(), entity.getOwnerEmployeeNameSnapshot(), entity.getSettlementTypeCode(),
                entity.getAddress(), entity.getStatusCode(), entity.getRemark(), entity.getSourceSystemCode(),
                entity.getSourceDocumentNo(), instant(entity.getSourceCreatedAt()), instant(entity.getSourceUpdatedAt()),
                entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()), entity.getUpdatedBy(),
                instant(entity.getUpdatedTime()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime time(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static byte[] bytes(UUID value) {
        return CrmUuidCodec.encode(value);
    }

    private static boolean sameHash(String current, String incoming) {
        return current != null && incoming != null && current.equals(incoming);
    }

    private static String defaultText(String value, String defaultValue, int max) {
        String cleaned = clean(value, max);
        return cleaned == null ? defaultValue : cleaned;
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private record SyncOutcome(String sourceCustomerId, Long customerId, String customerCode,
                               String status, String message) {
    }

    private record PartySync(PartyEntity entity, boolean changed) {
    }

    private record ContactSync(ContactEntity entity, boolean changed) {
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}

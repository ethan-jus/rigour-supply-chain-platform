package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.FundDocumentSummaryView;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.application.port.out.OrderFundDocumentStore;
import com.rigour.order.application.port.out.OrderFundDocumentStore.FundDocumentSearchCriteria;
import com.rigour.order.application.port.out.OrderFundDocumentStore.FundDocumentWrite;
import com.rigour.order.infrastructure.persistence.entity.InternalFundDocumentEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalFundDocumentMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis-Plus 自研资金收付款单仓储。 */
@Repository
public class MybatisPlusFundDocumentRepository
        extends ServiceImpl<InternalFundDocumentMapper, InternalFundDocumentEntity>
        implements OrderFundDocumentStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final Clock clock;

    public MybatisPlusFundDocumentRepository(
            InternalFundDocumentMapper mapper,
            Clock orderClock) {
        this.baseMapper = mapper;
        this.clock = orderClock;
    }

    @Override
    public OrderPageView<FundDocumentSummaryView> fundDocuments(
            String tenantId, int begin, int step, FundDocumentSearchCriteria criteria) {
        long total = getBaseMapper().selectCount(query(tenantId, criteria));
        List<FundDocumentSummaryView> items = getBaseMapper().selectList(query(tenantId, criteria)
                        .orderByDesc(InternalFundDocumentEntity::getOccurredTime)
                        .orderByDesc(InternalFundDocumentEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusFundDocumentRepository::summary)
                .toList();
        return new OrderPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<FundDocumentDetailView> fundDocument(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusFundDocumentRepository::detail);
    }

    @Override
    public boolean existsByNo(String tenantId, String documentNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalFundDocumentEntity>lambdaQuery()
                .eq(InternalFundDocumentEntity::getTenantId, tenantId)
                .eq(InternalFundDocumentEntity::getDocumentNo, documentNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundDocumentDetailView create(
            String tenantId, String documentNo, FundDocumentWrite command, String actorId) {
        LocalDateTime now = now();
        InternalFundDocumentEntity entity = entity(tenantId, documentNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("资金单据编号已存在");
        }
        return fundDocument(tenantId, entity.getId()).orElseThrow(() -> notFound("资金单据不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundDocumentDetailView update(
            String tenantId, Long id, FundDocumentWrite command, String actorId) {
        InternalFundDocumentEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("资金单据不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalFundDocumentEntity>lambdaUpdate()
                .set(InternalFundDocumentEntity::getConnectorId, uuidText(command.connectorId()))
                .set(InternalFundDocumentEntity::getSourceSystemCode, command.sourceSystemCode())
                .set(InternalFundDocumentEntity::getDirectionCode, command.directionCode())
                .set(InternalFundDocumentEntity::getRelatedOrderId, command.relatedOrderId())
                .set(InternalFundDocumentEntity::getSalesOrderNoSnapshot, command.salesOrderNoSnapshot())
                .set(InternalFundDocumentEntity::getCustomerId, command.customerId())
                .set(InternalFundDocumentEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalFundDocumentEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalFundDocumentEntity::getCounterpartyTypeCode, command.counterpartyTypeCode())
                .set(InternalFundDocumentEntity::getCounterpartyCodeSnapshot, command.counterpartyCodeSnapshot())
                .set(InternalFundDocumentEntity::getCounterpartyNameSnapshot, command.counterpartyNameSnapshot())
                .set(InternalFundDocumentEntity::getHandlerStaffCode, command.handlerStaffCode())
                .set(InternalFundDocumentEntity::getHandlerStaffNameSnapshot, command.handlerStaffNameSnapshot())
                .set(InternalFundDocumentEntity::getOccurredTime, local(command.occurredTime()))
                .set(InternalFundDocumentEntity::getSettlementMethodCode, command.settlementMethodCode())
                .set(InternalFundDocumentEntity::getBusinessTypeCode, command.businessTypeCode())
                .set(InternalFundDocumentEntity::getDocumentStatusCode, command.documentStatusCode())
                .set(InternalFundDocumentEntity::getAmount, command.amount())
                .set(InternalFundDocumentEntity::getSourceDocumentNo, command.sourceDocumentNo())
                .set(InternalFundDocumentEntity::getSourceOrderNo, command.sourceOrderNo())
                .set(InternalFundDocumentEntity::getPaymentSerialNo, command.paymentSerialNo())
                .set(InternalFundDocumentEntity::getBankAccountName, command.bankAccountName())
                .set(InternalFundDocumentEntity::getBankName, command.bankName())
                .set(InternalFundDocumentEntity::getBankAccountNo, command.bankAccountNo())
                .set(InternalFundDocumentEntity::getSubmittedAt, local(command.submittedAt()))
                .set(InternalFundDocumentEntity::getConfirmedAt, local(command.confirmedAt()))
                .set(InternalFundDocumentEntity::getSourceAttachmentKeysJson, json(command.sourceAttachmentKeys()))
                .set(InternalFundDocumentEntity::getVoucherKeysJson, json(command.voucherKeys()))
                .set(InternalFundDocumentEntity::getRemark, command.remark())
                .set(InternalFundDocumentEntity::getRevision, command.revision() + 1)
                .set(InternalFundDocumentEntity::getUpdatedBy, actorId)
                .set(InternalFundDocumentEntity::getUpdatedTime, now)
                .eq(InternalFundDocumentEntity::getTenantId, tenantId)
                .eq(InternalFundDocumentEntity::getId, existing.getId())
                .eq(InternalFundDocumentEntity::getRevision, command.revision())
                .eq(InternalFundDocumentEntity::getDeleted, 0));
        if (updated != 1) throw conflict("资金单据已被其他人修改，请刷新后重试");
        return fundDocument(tenantId, id).orElseThrow(() -> notFound("资金单据不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        InternalFundDocumentEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("资金单据不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalFundDocumentEntity>lambdaUpdate()
                .set(InternalFundDocumentEntity::getDeleted, 1)
                .set(InternalFundDocumentEntity::getRevision, revision + 1)
                .set(InternalFundDocumentEntity::getUpdatedBy, actorId)
                .set(InternalFundDocumentEntity::getUpdatedTime, now)
                .eq(InternalFundDocumentEntity::getTenantId, tenantId)
                .eq(InternalFundDocumentEntity::getId, id)
                .eq(InternalFundDocumentEntity::getRevision, revision)
                .eq(InternalFundDocumentEntity::getDeleted, 0));
        if (updated != 1) throw conflict("资金单据已被其他人修改，请刷新后重试");
    }

    private Optional<InternalFundDocumentEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalFundDocumentEntity>lambdaQuery()
                .eq(InternalFundDocumentEntity::getTenantId, tenantId)
                .eq(InternalFundDocumentEntity::getId, id)
                .eq(InternalFundDocumentEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalFundDocumentEntity> query(
            String tenantId, FundDocumentSearchCriteria criteria) {
        LambdaQueryWrapper<InternalFundDocumentEntity> query =
                Wrappers.<InternalFundDocumentEntity>lambdaQuery()
                        .eq(InternalFundDocumentEntity::getTenantId, tenantId)
                        .eq(InternalFundDocumentEntity::getDeleted, 0);
        if (criteria.keyword() != null) {
            query.and(wrapper -> wrapper
                    .like(InternalFundDocumentEntity::getDocumentNo, criteria.keyword())
                    .or()
                    .like(InternalFundDocumentEntity::getSourceDocumentNo, criteria.keyword())
                    .or()
                    .like(InternalFundDocumentEntity::getSalesOrderNoSnapshot, criteria.keyword())
                    .or()
                    .like(InternalFundDocumentEntity::getSourceOrderNo, criteria.keyword())
                    .or()
                    .like(InternalFundDocumentEntity::getPaymentSerialNo, criteria.keyword()));
        }
        if (criteria.directionCode() != null) query.eq(InternalFundDocumentEntity::getDirectionCode, criteria.directionCode());
        if (criteria.documentNo() != null) query.like(InternalFundDocumentEntity::getDocumentNo, criteria.documentNo());
        if (criteria.sourceDocumentNo() != null) query.like(InternalFundDocumentEntity::getSourceDocumentNo, criteria.sourceDocumentNo());
        if (criteria.salesOrderNo() != null) query.like(InternalFundDocumentEntity::getSalesOrderNoSnapshot, criteria.salesOrderNo());
        if (criteria.sourceOrderNo() != null) query.like(InternalFundDocumentEntity::getSourceOrderNo, criteria.sourceOrderNo());
        if (criteria.paymentSerialNo() != null) query.like(InternalFundDocumentEntity::getPaymentSerialNo, criteria.paymentSerialNo());
        if (criteria.counterpartyName() != null) query.like(InternalFundDocumentEntity::getCounterpartyNameSnapshot, criteria.counterpartyName());
        if (criteria.handlerStaffCode() != null) query.eq(InternalFundDocumentEntity::getHandlerStaffCode, criteria.handlerStaffCode());
        if (criteria.settlementMethodCode() != null) query.eq(InternalFundDocumentEntity::getSettlementMethodCode, criteria.settlementMethodCode());
        if (criteria.businessTypeCode() != null) query.eq(InternalFundDocumentEntity::getBusinessTypeCode, criteria.businessTypeCode());
        if (criteria.documentStatusCode() != null) query.eq(InternalFundDocumentEntity::getDocumentStatusCode, criteria.documentStatusCode());
        if (criteria.occurredTimeFrom() != null) query.ge(InternalFundDocumentEntity::getOccurredTime, local(criteria.occurredTimeFrom()));
        if (criteria.occurredTimeTo() != null) query.le(InternalFundDocumentEntity::getOccurredTime, local(criteria.occurredTimeTo()));
        return query;
    }

    private InternalFundDocumentEntity entity(
            String tenantId, String documentNo, FundDocumentWrite command, String actorId, LocalDateTime now) {
        InternalFundDocumentEntity entity = new InternalFundDocumentEntity();
        entity.setTenantId(tenantId);
        entity.setDocumentNo(documentNo);
        entity.setConnectorId(uuidText(command.connectorId()));
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setDirectionCode(command.directionCode());
        entity.setRelatedOrderId(command.relatedOrderId());
        entity.setSalesOrderNoSnapshot(command.salesOrderNoSnapshot());
        entity.setCustomerId(command.customerId());
        entity.setCustomerCodeSnapshot(command.customerCodeSnapshot());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setCounterpartyTypeCode(command.counterpartyTypeCode());
        entity.setCounterpartyCodeSnapshot(command.counterpartyCodeSnapshot());
        entity.setCounterpartyNameSnapshot(command.counterpartyNameSnapshot());
        entity.setHandlerStaffCode(command.handlerStaffCode());
        entity.setHandlerStaffNameSnapshot(command.handlerStaffNameSnapshot());
        entity.setOccurredTime(local(command.occurredTime()));
        entity.setSettlementMethodCode(command.settlementMethodCode());
        entity.setBusinessTypeCode(command.businessTypeCode());
        entity.setDocumentStatusCode(command.documentStatusCode());
        entity.setAmount(command.amount());
        entity.setSourceDocumentNo(command.sourceDocumentNo());
        entity.setSourceOrderNo(command.sourceOrderNo());
        entity.setPaymentSerialNo(command.paymentSerialNo());
        entity.setBankAccountName(command.bankAccountName());
        entity.setBankName(command.bankName());
        entity.setBankAccountNo(command.bankAccountNo());
        entity.setSubmittedAt(local(command.submittedAt()));
        entity.setConfirmedAt(local(command.confirmedAt()));
        entity.setSourceAttachmentKeysJson(json(command.sourceAttachmentKeys()));
        entity.setVoucherKeysJson(json(command.voucherKeys()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static FundDocumentSummaryView summary(InternalFundDocumentEntity entity) {
        return new FundDocumentSummaryView(entity.getId(), entity.getDocumentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getDirectionCode(),
                entity.getRelatedOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(),
                entity.getCustomerCodeSnapshot(), entity.getCustomerNameSnapshot(),
                entity.getCounterpartyTypeCode(), entity.getCounterpartyCodeSnapshot(),
                entity.getCounterpartyNameSnapshot(), entity.getHandlerStaffCode(),
                entity.getHandlerStaffNameSnapshot(), instant(entity.getOccurredTime()),
                entity.getSettlementMethodCode(), entity.getBusinessTypeCode(),
                entity.getDocumentStatusCode(), entity.getAmount(), entity.getSourceDocumentNo(),
                entity.getSourceOrderNo(), entity.getPaymentSerialNo(), entity.getBankAccountName(),
                entity.getBankName(), entity.getBankAccountNo(), instant(entity.getSubmittedAt()),
                instant(entity.getConfirmedAt()), parseStrings(entity.getSourceAttachmentKeysJson()), entity.getRevision(),
                instant(entity.getUpdatedTime()));
    }

    private static FundDocumentDetailView detail(InternalFundDocumentEntity entity) {
        return new FundDocumentDetailView(entity.getId(), entity.getDocumentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getDirectionCode(),
                entity.getRelatedOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(),
                entity.getCustomerCodeSnapshot(), entity.getCustomerNameSnapshot(),
                entity.getCounterpartyTypeCode(), entity.getCounterpartyCodeSnapshot(),
                entity.getCounterpartyNameSnapshot(), entity.getHandlerStaffCode(),
                entity.getHandlerStaffNameSnapshot(), instant(entity.getOccurredTime()),
                entity.getSettlementMethodCode(), entity.getBusinessTypeCode(),
                entity.getDocumentStatusCode(), entity.getAmount(), entity.getSourceDocumentNo(),
                entity.getSourceOrderNo(), entity.getPaymentSerialNo(), entity.getBankAccountName(),
                entity.getBankName(), entity.getBankAccountNo(), instant(entity.getSubmittedAt()),
                instant(entity.getConfirmedAt()), parseStrings(entity.getSourceAttachmentKeysJson()),
                parseStrings(entity.getVoucherKeysJson()), List.of(),
                entity.getRemark(), entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Order资金单据凭证JSON序列化失败", exception);
        }
    }

    private static List<String> parseStrings(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON.readValue(json, STRING_LIST_TYPE).stream()
                    .filter(StringUtils::hasText)
                    .map(String::strip)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Order资金单据凭证JSON反序列化失败", exception);
        }
    }

    private static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String uuidText(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}

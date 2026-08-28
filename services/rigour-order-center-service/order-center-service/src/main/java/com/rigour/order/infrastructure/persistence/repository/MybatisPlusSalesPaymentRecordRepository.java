package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordSummaryView;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore.SalesPaymentSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore.SalesPaymentWrite;
import com.rigour.order.domain.enums.SalesOrderPaymentStatus;
import com.rigour.order.domain.enums.SalesOrderStatus;
import com.rigour.order.domain.enums.SalesRefundStatus;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesPaymentRecordEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesRefundRecordEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesPaymentRecordMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesRefundRecordMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis-Plus 自研销售回款记录仓储；写入回款后同步汇总销售订单收款状态。 */
@Repository
public class MybatisPlusSalesPaymentRecordRepository
        extends ServiceImpl<InternalSalesPaymentRecordMapper, InternalSalesPaymentRecordEntity>
        implements OrderSalesPaymentRecordStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalSalesOrderMapper orderMapper;
    private final InternalSalesRefundRecordMapper refundMapper;
    private final Clock clock;

    public MybatisPlusSalesPaymentRecordRepository(
            InternalSalesPaymentRecordMapper mapper,
            InternalSalesOrderMapper orderMapper,
            InternalSalesRefundRecordMapper refundMapper,
            Clock orderClock) {
        this.baseMapper = mapper;
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper");
        this.refundMapper = Objects.requireNonNull(refundMapper, "refundMapper");
        this.clock = orderClock;
    }

    @Override
    public OrderPageView<SalesPaymentRecordSummaryView> payments(
            String tenantId, int begin, int step, SalesPaymentSearchCriteria criteria) {
        long total = getBaseMapper().selectCount(query(tenantId, criteria));
        List<SalesPaymentRecordSummaryView> items = getBaseMapper().selectList(query(tenantId, criteria)
                        .orderByDesc(InternalSalesPaymentRecordEntity::getPaymentTime)
                        .orderByDesc(InternalSalesPaymentRecordEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusSalesPaymentRecordRepository::summary)
                .toList();
        return new OrderPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<SalesPaymentRecordDetailView> payment(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusSalesPaymentRecordRepository::detail);
    }

    @Override
    public boolean existsByNo(String tenantId, String paymentNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalSalesPaymentRecordEntity>lambdaQuery()
                .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesPaymentRecordEntity::getPaymentNo, paymentNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesPaymentRecordDetailView create(
            String tenantId, String paymentNo, SalesPaymentWrite command, String actorId) {
        requireOrder(tenantId, command.orderId());
        LocalDateTime now = now();
        InternalSalesPaymentRecordEntity entity = entity(tenantId, paymentNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            refreshOrderPayment(tenantId, command.orderId(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("销售回款单号已存在或销售订单引用无效");
        }
        return payment(tenantId, entity.getId()).orElseThrow(() -> notFound("销售回款记录不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesPaymentRecordDetailView update(
            String tenantId, Long id, SalesPaymentWrite command, String actorId) {
        InternalSalesPaymentRecordEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售回款记录不存在"));
        requireOrder(tenantId, command.orderId());
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesPaymentRecordEntity>lambdaUpdate()
                .set(InternalSalesPaymentRecordEntity::getConnectorId, uuidText(command.connectorId()))
                .set(InternalSalesPaymentRecordEntity::getSourceSystemCode, command.sourceSystemCode())
                .set(InternalSalesPaymentRecordEntity::getSourceDocumentNo, command.sourceDocumentNo())
                .set(InternalSalesPaymentRecordEntity::getOrderId, command.orderId())
                .set(InternalSalesPaymentRecordEntity::getSalesOrderNoSnapshot, command.salesOrderNoSnapshot())
                .set(InternalSalesPaymentRecordEntity::getCustomerId, command.customerId())
                .set(InternalSalesPaymentRecordEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalSalesPaymentRecordEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalSalesPaymentRecordEntity::getCollectorStaffCode, command.collectorStaffCode())
                .set(InternalSalesPaymentRecordEntity::getCollectorNameSnapshot, command.collectorNameSnapshot())
                .set(InternalSalesPaymentRecordEntity::getPaymentTime, local(command.paymentTime()))
                .set(InternalSalesPaymentRecordEntity::getPaymentMethodCode, command.paymentMethodCode())
                .set(InternalSalesPaymentRecordEntity::getPaidAmount, command.paidAmount())
                .set(InternalSalesPaymentRecordEntity::getVoucherKeysJson, json(command.voucherKeys()))
                .set(InternalSalesPaymentRecordEntity::getRemark, command.remark())
                .set(InternalSalesPaymentRecordEntity::getRevision, command.revision() + 1)
                .set(InternalSalesPaymentRecordEntity::getUpdatedBy, actorId)
                .set(InternalSalesPaymentRecordEntity::getUpdatedTime, now)
                .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesPaymentRecordEntity::getId, id)
                .eq(InternalSalesPaymentRecordEntity::getRevision, command.revision())
                .eq(InternalSalesPaymentRecordEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售回款记录已被其他人修改，请刷新后重试");
        refreshOrderPayment(tenantId, existing.getOrderId(), actorId, now);
        if (!Objects.equals(existing.getOrderId(), command.orderId())) {
            refreshOrderPayment(tenantId, command.orderId(), actorId, now);
        }
        return payment(tenantId, id).orElseThrow(() -> notFound("销售回款记录不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        InternalSalesPaymentRecordEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售回款记录不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesPaymentRecordEntity>lambdaUpdate()
                .set(InternalSalesPaymentRecordEntity::getDeleted, 1)
                .set(InternalSalesPaymentRecordEntity::getRevision, revision + 1)
                .set(InternalSalesPaymentRecordEntity::getUpdatedBy, actorId)
                .set(InternalSalesPaymentRecordEntity::getUpdatedTime, now)
                .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesPaymentRecordEntity::getId, id)
                .eq(InternalSalesPaymentRecordEntity::getRevision, revision)
                .eq(InternalSalesPaymentRecordEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售回款记录已被其他人修改，请刷新后重试");
        refreshOrderPayment(tenantId, existing.getOrderId(), actorId, now);
    }

    private Optional<InternalSalesPaymentRecordEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalSalesPaymentRecordEntity>lambdaQuery()
                .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesPaymentRecordEntity::getId, id)
                .eq(InternalSalesPaymentRecordEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private InternalSalesOrderEntity requireOrder(String tenantId, Long orderId) {
        return Optional.ofNullable(orderMapper.selectOne(Wrappers.<InternalSalesOrderEntity>lambdaQuery()
                        .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                        .eq(InternalSalesOrderEntity::getId, orderId)
                        .eq(InternalSalesOrderEntity::getDeleted, 0)
                        .last("LIMIT 1")))
                .orElseThrow(() -> notFound("销售订单不存在"));
    }

    private LambdaQueryWrapper<InternalSalesPaymentRecordEntity> query(
            String tenantId, SalesPaymentSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSalesPaymentRecordEntity> query =
                Wrappers.<InternalSalesPaymentRecordEntity>lambdaQuery()
                        .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesPaymentRecordEntity::getDeleted, 0);
        if (criteria.paymentNo() != null) query.like(InternalSalesPaymentRecordEntity::getPaymentNo, criteria.paymentNo());
        if (criteria.salesOrderNo() != null) query.like(InternalSalesPaymentRecordEntity::getSalesOrderNoSnapshot, criteria.salesOrderNo());
        if (criteria.customerName() != null) query.like(InternalSalesPaymentRecordEntity::getCustomerNameSnapshot, criteria.customerName());
        if (criteria.collectorStaffCode() != null) query.eq(InternalSalesPaymentRecordEntity::getCollectorStaffCode, criteria.collectorStaffCode());
        if (criteria.paymentMethodCode() != null) query.eq(InternalSalesPaymentRecordEntity::getPaymentMethodCode, criteria.paymentMethodCode());
        if (criteria.paymentTimeFrom() != null) query.ge(InternalSalesPaymentRecordEntity::getPaymentTime, local(criteria.paymentTimeFrom()));
        if (criteria.paymentTimeTo() != null) query.le(InternalSalesPaymentRecordEntity::getPaymentTime, local(criteria.paymentTimeTo()));
        return query;
    }

    private InternalSalesPaymentRecordEntity entity(
            String tenantId, String paymentNo, SalesPaymentWrite command, String actorId, LocalDateTime now) {
        InternalSalesPaymentRecordEntity entity = new InternalSalesPaymentRecordEntity();
        entity.setTenantId(tenantId);
        entity.setPaymentNo(paymentNo);
        entity.setConnectorId(uuidText(command.connectorId()));
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setSourceDocumentNo(command.sourceDocumentNo());
        entity.setOrderId(command.orderId());
        entity.setSalesOrderNoSnapshot(command.salesOrderNoSnapshot());
        entity.setCustomerId(command.customerId());
        entity.setCustomerCodeSnapshot(command.customerCodeSnapshot());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setCollectorStaffCode(command.collectorStaffCode());
        entity.setCollectorNameSnapshot(command.collectorNameSnapshot());
        entity.setPaymentTime(local(command.paymentTime()));
        entity.setPaymentMethodCode(command.paymentMethodCode());
        entity.setPaidAmount(command.paidAmount());
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

    private void refreshOrderPayment(String tenantId, Long orderId, String actorId, LocalDateTime now) {
        InternalSalesOrderEntity order = requireOrder(tenantId, orderId);
        List<InternalSalesPaymentRecordEntity> payments = getBaseMapper().selectList(
                Wrappers.<InternalSalesPaymentRecordEntity>lambdaQuery()
                        .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesPaymentRecordEntity::getOrderId, orderId)
                        .eq(InternalSalesPaymentRecordEntity::getDeleted, 0));
        BigDecimal paidAmount = payments.stream()
                .map(InternalSalesPaymentRecordEntity::getPaidAmount)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
        LocalDateTime paymentTime = payments.stream()
                .map(InternalSalesPaymentRecordEntity::getPaymentTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        BigDecimal refundAmount = refundMapper.selectList(Wrappers.<InternalSalesRefundRecordEntity>lambdaQuery()
                        .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesRefundRecordEntity::getOrderId, orderId)
                        .ne(InternalSalesRefundRecordEntity::getRefundStatusCode, SalesRefundStatus.CANCELLED.code())
                        .eq(InternalSalesRefundRecordEntity::getDeleted, 0))
                .stream()
                .map(InternalSalesRefundRecordEntity::getRefundAmount)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
        paidAmount = paidAmount.subtract(refundAmount);
        if (paidAmount.compareTo(ZERO) < 0) paidAmount = ZERO;
        BigDecimal payableAmount = nz(order.getPayableAmount());
        boolean cancelledOrder = SalesOrderStatus.CANCELLED.code().equals(order.getOrderStatusCode());
        BigDecimal unpaidAmount = cancelledOrder ? ZERO : payableAmount.subtract(paidAmount);
        if (unpaidAmount.compareTo(ZERO) < 0) unpaidAmount = ZERO;
        String status = cancelledOrder
                ? SalesOrderPaymentStatus.CANCELLED.code()
                : paymentStatus(payableAmount, paidAmount);
        orderMapper.update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getPaymentTime, paymentTime)
                .set(InternalSalesOrderEntity::getPaidAmount, paidAmount)
                .set(InternalSalesOrderEntity::getUnpaidAmount, unpaidAmount)
                .set(InternalSalesOrderEntity::getPaymentStatusCode, status)
                .set(InternalSalesOrderEntity::getUpdatedBy, actorId)
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .setSql("revision = revision + 1")
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, orderId)
                .eq(InternalSalesOrderEntity::getDeleted, 0));
    }

    private static String paymentStatus(BigDecimal payableAmount, BigDecimal paidAmount) {
        if (paidAmount.compareTo(ZERO) <= 0) return SalesOrderPaymentStatus.UNPAID.code();
        if (paidAmount.compareTo(payableAmount) >= 0) return SalesOrderPaymentStatus.PAID.code();
        return SalesOrderPaymentStatus.PARTIAL_PAID.code();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static SalesPaymentRecordSummaryView summary(InternalSalesPaymentRecordEntity entity) {
        return new SalesPaymentRecordSummaryView(entity.getId(), entity.getPaymentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                entity.getOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(), entity.getCustomerCodeSnapshot(),
                entity.getCustomerNameSnapshot(), entity.getCollectorStaffCode(),
                entity.getCollectorNameSnapshot(), instant(entity.getPaymentTime()),
                entity.getPaymentMethodCode(), entity.getPaidAmount(), entity.getRevision(),
                instant(entity.getUpdatedTime()));
    }

    private static SalesPaymentRecordDetailView detail(InternalSalesPaymentRecordEntity entity) {
        return new SalesPaymentRecordDetailView(entity.getId(), entity.getPaymentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                entity.getOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(), entity.getCustomerCodeSnapshot(),
                entity.getCustomerNameSnapshot(), entity.getCollectorStaffCode(),
                entity.getCollectorNameSnapshot(), instant(entity.getPaymentTime()),
                entity.getPaymentMethodCode(), entity.getPaidAmount(), parseStrings(entity.getVoucherKeysJson()),
                entity.getRemark(), entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Order回款凭证JSON序列化失败", exception);
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
            throw new IllegalStateException("Order回款凭证JSON反序列化失败", exception);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
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

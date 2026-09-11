package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordSummaryView;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore.SalesRefundSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore.SalesRefundWrite;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis-Plus 自研销售退款记录仓储；写入退款后同步汇总销售订单实收状态。 */
@Repository
public class MybatisPlusSalesRefundRecordRepository
        extends ServiceImpl<InternalSalesRefundRecordMapper, InternalSalesRefundRecordEntity>
        implements OrderSalesRefundRecordStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalSalesOrderMapper orderMapper;
    private final InternalSalesPaymentRecordMapper paymentMapper;
    private final Clock clock;

    public MybatisPlusSalesRefundRecordRepository(
            InternalSalesRefundRecordMapper mapper,
            InternalSalesOrderMapper orderMapper,
            InternalSalesPaymentRecordMapper paymentMapper,
            Clock orderClock) {
        this.baseMapper = mapper;
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper");
        this.paymentMapper = Objects.requireNonNull(paymentMapper, "paymentMapper");
        this.clock = orderClock;
    }

    @Override
    public OrderPageView<SalesRefundRecordSummaryView> refunds(
            String tenantId, int begin, int step, SalesRefundSearchCriteria criteria) {
        long total = getBaseMapper().selectCount(query(tenantId, criteria));
        List<SalesRefundRecordSummaryView> items = getBaseMapper().selectList(query(tenantId, criteria)
                        .orderByDesc(InternalSalesRefundRecordEntity::getRefundTime)
                        .orderByDesc(InternalSalesRefundRecordEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusSalesRefundRecordRepository::summary)
                .toList();
        return new OrderPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<SalesRefundRecordDetailView> refund(String tenantId, Long id) {
        return selectActive(tenantId, id).map(MybatisPlusSalesRefundRecordRepository::detail);
    }

    @Override
    public boolean existsByNo(String tenantId, String refundNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalSalesRefundRecordEntity>lambdaQuery()
                .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesRefundRecordEntity::getRefundNo, refundNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesRefundRecordDetailView create(
            String tenantId, String refundNo, SalesRefundWrite command, String actorId) {
        requireOrder(tenantId, command.orderId());
        LocalDateTime now = now();
        InternalSalesRefundRecordEntity entity = entity(tenantId, refundNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            refreshOrderPayment(tenantId, command.orderId(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("销售退款单号已存在或销售订单引用无效");
        }
        return refund(tenantId, entity.getId()).orElseThrow(() -> notFound("销售退款记录不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesRefundRecordDetailView update(
            String tenantId, Long id, SalesRefundWrite command, String actorId) {
        InternalSalesRefundRecordEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售退款记录不存在"));
        requireOrder(tenantId, command.orderId());
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesRefundRecordEntity>lambdaUpdate()
                .set(InternalSalesRefundRecordEntity::getOrderId, command.orderId())
                .set(InternalSalesRefundRecordEntity::getSalesOrderNoSnapshot, command.salesOrderNoSnapshot())
                .set(InternalSalesRefundRecordEntity::getCustomerId, command.customerId())
                .set(InternalSalesRefundRecordEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalSalesRefundRecordEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalSalesRefundRecordEntity::getRefundStaffCode, command.refundStaffCode())
                .set(InternalSalesRefundRecordEntity::getRefundStaffNameSnapshot, command.refundStaffNameSnapshot())
                .set(InternalSalesRefundRecordEntity::getRefundTime, local(command.refundTime()))
                .set(InternalSalesRefundRecordEntity::getRefundMethodCode, command.refundMethodCode())
                .set(InternalSalesRefundRecordEntity::getRefundStatusCode, command.refundStatusCode())
                .set(InternalSalesRefundRecordEntity::getRefundAmount, command.refundAmount())
                .set(InternalSalesRefundRecordEntity::getVoucherKeysJson, json(command.voucherKeys()))
                .set(InternalSalesRefundRecordEntity::getRemark, command.remark())
                .set(InternalSalesRefundRecordEntity::getRevision, command.revision() + 1)
                .set(InternalSalesRefundRecordEntity::getUpdatedBy, actorId)
                .set(InternalSalesRefundRecordEntity::getUpdatedTime, now)
                .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesRefundRecordEntity::getId, id)
                .eq(InternalSalesRefundRecordEntity::getRevision, command.revision())
                .eq(InternalSalesRefundRecordEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售退款记录已被其他人修改，请刷新后重试");
        refreshOrderPayment(tenantId, existing.getOrderId(), actorId, now);
        if (!Objects.equals(existing.getOrderId(), command.orderId())) {
            refreshOrderPayment(tenantId, command.orderId(), actorId, now);
        }
        return refund(tenantId, id).orElseThrow(() -> notFound("销售退款记录不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        InternalSalesRefundRecordEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售退款记录不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesRefundRecordEntity>lambdaUpdate()
                .set(InternalSalesRefundRecordEntity::getDeleted, 1)
                .set(InternalSalesRefundRecordEntity::getRevision, revision + 1)
                .set(InternalSalesRefundRecordEntity::getUpdatedBy, actorId)
                .set(InternalSalesRefundRecordEntity::getUpdatedTime, now)
                .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesRefundRecordEntity::getId, id)
                .eq(InternalSalesRefundRecordEntity::getRevision, revision)
                .eq(InternalSalesRefundRecordEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售退款记录已被其他人修改，请刷新后重试");
        refreshOrderPayment(tenantId, existing.getOrderId(), actorId, now);
    }

    private Optional<InternalSalesRefundRecordEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalSalesRefundRecordEntity>lambdaQuery()
                .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                .eq(InternalSalesRefundRecordEntity::getId, id)
                .eq(InternalSalesRefundRecordEntity::getDeleted, 0)
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

    private LambdaQueryWrapper<InternalSalesRefundRecordEntity> query(
            String tenantId, SalesRefundSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSalesRefundRecordEntity> query =
                Wrappers.<InternalSalesRefundRecordEntity>lambdaQuery()
                        .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesRefundRecordEntity::getDeleted, 0);
        if (criteria.refundNo() != null) query.like(InternalSalesRefundRecordEntity::getRefundNo, criteria.refundNo());
        if (criteria.salesOrderNo() != null) query.like(InternalSalesRefundRecordEntity::getSalesOrderNoSnapshot, criteria.salesOrderNo());
        if (criteria.customerName() != null) query.like(InternalSalesRefundRecordEntity::getCustomerNameSnapshot, criteria.customerName());
        if (criteria.refundStaffCode() != null) query.eq(InternalSalesRefundRecordEntity::getRefundStaffCode, criteria.refundStaffCode());
        if (criteria.refundMethodCode() != null) query.eq(InternalSalesRefundRecordEntity::getRefundMethodCode, criteria.refundMethodCode());
        if (criteria.refundStatusCode() != null) query.eq(InternalSalesRefundRecordEntity::getRefundStatusCode, criteria.refundStatusCode());
        if (criteria.refundTimeFrom() != null) query.ge(InternalSalesRefundRecordEntity::getRefundTime, local(criteria.refundTimeFrom()));
        if (criteria.refundTimeTo() != null) query.le(InternalSalesRefundRecordEntity::getRefundTime, local(criteria.refundTimeTo()));
        return query;
    }

    private InternalSalesRefundRecordEntity entity(
            String tenantId, String refundNo, SalesRefundWrite command, String actorId, LocalDateTime now) {
        InternalSalesRefundRecordEntity entity = new InternalSalesRefundRecordEntity();
        entity.setTenantId(tenantId);
        entity.setRefundNo(refundNo);
        entity.setOrderId(command.orderId());
        entity.setSalesOrderNoSnapshot(command.salesOrderNoSnapshot());
        entity.setCustomerId(command.customerId());
        entity.setCustomerCodeSnapshot(command.customerCodeSnapshot());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setRefundStaffCode(command.refundStaffCode());
        entity.setRefundStaffNameSnapshot(command.refundStaffNameSnapshot());
        entity.setRefundTime(local(command.refundTime()));
        entity.setRefundMethodCode(command.refundMethodCode());
        entity.setRefundStatusCode(command.refundStatusCode());
        entity.setRefundAmount(command.refundAmount());
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
        BigDecimal paidAmount = paymentMapper.selectList(Wrappers.<InternalSalesPaymentRecordEntity>lambdaQuery()
                        .eq(InternalSalesPaymentRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesPaymentRecordEntity::getOrderId, orderId)
                        .eq(InternalSalesPaymentRecordEntity::getDeleted, 0))
                .stream()
                .map(InternalSalesPaymentRecordEntity::getPaidAmount)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal refundAmount = getBaseMapper().selectList(Wrappers.<InternalSalesRefundRecordEntity>lambdaQuery()
                        .eq(InternalSalesRefundRecordEntity::getTenantId, tenantId)
                        .eq(InternalSalesRefundRecordEntity::getOrderId, orderId)
                        .ne(InternalSalesRefundRecordEntity::getRefundStatusCode, SalesRefundStatus.CANCELLED.code())
                        .eq(InternalSalesRefundRecordEntity::getDeleted, 0))
                .stream()
                .map(InternalSalesRefundRecordEntity::getRefundAmount)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal netPaidAmount = paidAmount.subtract(refundAmount);
        if (netPaidAmount.compareTo(ZERO) < 0) netPaidAmount = ZERO;
        BigDecimal payableAmount = nz(order.getPayableAmount());
        boolean cancelledOrder = SalesOrderStatus.CANCELLED.code().equals(order.getOrderStatusCode());
        BigDecimal unpaidAmount = cancelledOrder ? ZERO : payableAmount.subtract(netPaidAmount);
        if (unpaidAmount.compareTo(ZERO) < 0) unpaidAmount = ZERO;
        String status = cancelledOrder
                ? SalesOrderPaymentStatus.CANCELLED.code()
                : paymentStatus(payableAmount, netPaidAmount);
        orderMapper.update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getPaidAmount, netPaidAmount)
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

    private static SalesRefundRecordSummaryView summary(InternalSalesRefundRecordEntity entity) {
        return new SalesRefundRecordSummaryView(entity.getId(), entity.getRefundNo(), entity.getOrderId(),
                entity.getSalesOrderNoSnapshot(), entity.getCustomerId(), entity.getCustomerCodeSnapshot(),
                entity.getCustomerNameSnapshot(), entity.getRefundStaffCode(),
                entity.getRefundStaffNameSnapshot(), instant(entity.getRefundTime()),
                entity.getRefundMethodCode(), entity.getRefundStatusCode(), entity.getRefundAmount(),
                entity.getRevision(), instant(entity.getUpdatedTime()));
    }

    private static SalesRefundRecordDetailView detail(InternalSalesRefundRecordEntity entity) {
        return new SalesRefundRecordDetailView(entity.getId(), entity.getRefundNo(), entity.getOrderId(),
                entity.getSalesOrderNoSnapshot(), entity.getCustomerId(), entity.getCustomerCodeSnapshot(),
                entity.getCustomerNameSnapshot(), entity.getRefundStaffCode(),
                entity.getRefundStaffNameSnapshot(), instant(entity.getRefundTime()),
                entity.getRefundMethodCode(), entity.getRefundStatusCode(), entity.getRefundAmount(),
                parseStrings(entity.getVoucherKeysJson()), entity.getRemark(), entity.getRevision(),
                entity.getCreatedBy(), instant(entity.getCreatedTime()), entity.getUpdatedBy(),
                instant(entity.getUpdatedTime()));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Order退款凭证JSON序列化失败", exception);
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
            throw new IllegalStateException("Order退款凭证JSON反序列化失败", exception);
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}

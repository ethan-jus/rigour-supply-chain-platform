package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.order.api.v1.model.SalesOrderTotalsView;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderLineWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSourceProjectionWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderWrite;
import com.rigour.order.domain.enums.SalesOrderOutboundStatus;
import com.rigour.order.domain.enums.SalesOrderPaymentStatus;
import com.rigour.order.domain.enums.SalesOrderStatus;
import com.rigour.order.domain.enums.SalesShipmentStatus;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderLineEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesShipmentEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesShipmentMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** MyBatis-Plus 自研销售订单仓储；订单头和明细只走 BaseMapper 与 LambdaWrapper。 */
@Repository
public class MybatisPlusSalesOrderRepository
        extends ServiceImpl<InternalSalesOrderMapper, InternalSalesOrderEntity>
        implements OrderSalesOrderStore {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String SOURCE_SYSTEM_FEISHU = "FEISHU";
    private static final String DATA_QUALITY_NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalSalesOrderLineMapper lineMapper;
    private final InternalSalesShipmentMapper shipmentMapper;
    private final Clock clock;

    public MybatisPlusSalesOrderRepository(
            InternalSalesOrderMapper mapper,
            InternalSalesOrderLineMapper lineMapper,
            InternalSalesShipmentMapper shipmentMapper,
            Clock orderClock) {
        this.baseMapper = mapper;
        this.lineMapper = lineMapper;
        this.shipmentMapper = shipmentMapper;
        this.clock = orderClock;
    }

    @Override
    public OrderPageView<SalesOrderSummaryView> salesOrders(
            String tenantId, int begin, int step, SalesOrderSearchCriteria criteria) {
        InternalSalesOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalSalesOrderEntity> rows = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalSalesOrderEntity::getOrderDate)
                        .orderByDesc(InternalSalesOrderEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin));
        Map<Long, String> shipmentStatuses = shipmentStatuses(tenantId, rows);
        List<SalesOrderSummaryView> items = rows
                .stream()
                .map(row -> summary(row, shipmentStatuses.get(row.getId())))
                .toList();
        return new OrderPageView<>(total, begin, step, items);
    }

    @Override
    public SalesOrderTotalsView salesOrderTotals(String tenantId, SalesOrderSearchCriteria criteria) {
        List<InternalSalesOrderEntity> rows = getBaseMapper().selectList(query(tenantId, criteria)
                .select(InternalSalesOrderEntity::getTotalQuantity,
                        InternalSalesOrderEntity::getOriginalAmount,
                        InternalSalesOrderEntity::getDiscountAmount,
                        InternalSalesOrderEntity::getPayableAmount,
                        InternalSalesOrderEntity::getPaidAmount,
                        InternalSalesOrderEntity::getUnpaidAmount));
        BigDecimal totalQuantity = ZERO;
        BigDecimal originalAmount = ZERO;
        BigDecimal discountAmount = ZERO;
        BigDecimal payableAmount = ZERO;
        BigDecimal paidAmount = ZERO;
        BigDecimal unpaidAmount = ZERO;
        for (InternalSalesOrderEntity row : rows) {
            totalQuantity = totalQuantity.add(amount(row.getTotalQuantity()));
            originalAmount = originalAmount.add(amount(row.getOriginalAmount()));
            discountAmount = discountAmount.add(totalDiscountAmount(row));
            payableAmount = payableAmount.add(amount(row.getPayableAmount()));
            paidAmount = paidAmount.add(amount(row.getPaidAmount()));
            unpaidAmount = unpaidAmount.add(amount(row.getUnpaidAmount()));
        }
        return new SalesOrderTotalsView(rows.size(), totalQuantity, originalAmount, discountAmount,
                payableAmount, paidAmount, unpaidAmount);
    }

    @Override
    public Optional<SalesOrderDetailView> salesOrder(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(order, lines(tenantId, id),
                shipmentStatus(tenantId, id)));
    }

    @Override
    public boolean existsByNo(String tenantId, String orderNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalSalesOrderEntity>lambdaQuery()
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getOrderNo, orderNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView create(
            String tenantId, String orderNo, SalesOrderWrite command, String actorId) {
        LocalDateTime now = now();
        InternalSalesOrderEntity entity = orderEntity(tenantId, orderNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            insertLines(tenantId, entity.getId(), command.lines(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("销售订单号已存在或销售订单引用数据无效");
        }
        return salesOrder(tenantId, entity.getId()).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView update(String tenantId, Long id, SalesOrderWrite command, String actorId) {
        InternalSalesOrderEntity existing = requireDraft(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getSourceSystemCode, command.sourceSystemCode())
                .set(InternalSalesOrderEntity::getSourceOrderNo, command.sourceOrderNo())
                .set(InternalSalesOrderEntity::getSourceStatusCode, command.sourceStatusCode())
                .set(InternalSalesOrderEntity::getSourceCreatorId, command.sourceCreatorId())
                .set(InternalSalesOrderEntity::getSourceCreatorStaffCode, command.sourceCreatorStaffCode())
                .set(InternalSalesOrderEntity::getSourceCreatorName, command.sourceCreatorName())
                .set(InternalSalesOrderEntity::getDataQualityStatusCode, command.dataQualityStatusCode())
                .set(InternalSalesOrderEntity::getDataQualityMessage, command.dataQualityMessage())
                .set(InternalSalesOrderEntity::getCustomerId, command.customerId())
                .set(InternalSalesOrderEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalSalesOrderEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalSalesOrderEntity::getContactNameSnapshot, command.contactNameSnapshot())
                .set(InternalSalesOrderEntity::getContactPhoneSnapshot, command.contactPhoneSnapshot())
                .set(InternalSalesOrderEntity::getRegionCode, command.regionCode())
                .set(InternalSalesOrderEntity::getOwnerSalesUserId, command.ownerSalesUserId())
                .set(InternalSalesOrderEntity::getOwnerSalesName, command.ownerSalesName())
                .set(InternalSalesOrderEntity::getOwnerEmployeeCode, command.ownerEmployeeCode())
                .set(InternalSalesOrderEntity::getOwnerEmployeeNameSnapshot, command.ownerEmployeeNameSnapshot())
                .set(InternalSalesOrderEntity::getOrderDate, local(command.orderDate()))
                .set(InternalSalesOrderEntity::getOrderStatusCode, command.orderStatusCode())
                .set(InternalSalesOrderEntity::getOrderTypeCode, command.orderTypeCode())
                .set(InternalSalesOrderEntity::getPaymentMethodCode, command.paymentMethodCode())
                .set(InternalSalesOrderEntity::getPaymentVoucherKeysJson, json(command.paymentVoucherKeys()))
                .set(InternalSalesOrderEntity::getTotalQuantity, command.totalQuantity())
                .set(InternalSalesOrderEntity::getOriginalAmount, command.originalAmount())
                .set(InternalSalesOrderEntity::getDiscountRate, command.discountRate())
                .set(InternalSalesOrderEntity::getDiscountAmount, command.discountAmount())
                .set(InternalSalesOrderEntity::getPayableAmount, command.payableAmount())
                .set(InternalSalesOrderEntity::getUnpaidAmount, initialUnpaidAmount(command))
                .set(InternalSalesOrderEntity::getSourceUnpaidAmount, command.sourceUnpaidAmount())
                .set(InternalSalesOrderEntity::getRemark, command.remark())
                .set(InternalSalesOrderEntity::getRevision, command.revision() + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, command.revision())
                .eq(InternalSalesOrderEntity::getOrderStatusCode, existing.getOrderStatusCode())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
        insertLines(tenantId, id, command.lines(), actorId, now);
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView updateExternalProjection(
            String tenantId, Long id, SalesOrderWrite command, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售订单不存在"));
        LocalDateTime now = now();
        BigDecimal payableAmount = amount(command.payableAmount());
        BigDecimal paidAmount = amount(existing.getPaidAmount());
        BigDecimal sourceUnpaidAmount = command.sourceUnpaidAmount() == null
                ? existing.getSourceUnpaidAmount()
                : command.sourceUnpaidAmount();
        boolean cancelled = SalesOrderStatus.CANCELLED.code().equals(existing.getOrderStatusCode());
        BigDecimal unpaidAmount = unpaidAmount(existing.getSourceSystemCode(), cancelled,
                payableAmount, paidAmount, sourceUnpaidAmount);
        String orderStatusCode = orderStatusAfterExternalProjection(existing, command);
        String paymentStatusCode = cancelled
                ? SalesOrderPaymentStatus.CANCELLED.code()
                : paymentStatus(payableAmount, paidAmount);
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getSourceStatusCode, command.sourceStatusCode())
                .set(InternalSalesOrderEntity::getSourceCreatorId, command.sourceCreatorId())
                .set(InternalSalesOrderEntity::getSourceCreatorStaffCode, command.sourceCreatorStaffCode())
                .set(InternalSalesOrderEntity::getSourceCreatorName, command.sourceCreatorName())
                .set(InternalSalesOrderEntity::getDataQualityStatusCode, command.dataQualityStatusCode())
                .set(InternalSalesOrderEntity::getDataQualityMessage, command.dataQualityMessage())
                .set(InternalSalesOrderEntity::getCustomerId, command.customerId())
                .set(InternalSalesOrderEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalSalesOrderEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalSalesOrderEntity::getContactNameSnapshot, command.contactNameSnapshot())
                .set(InternalSalesOrderEntity::getContactPhoneSnapshot, command.contactPhoneSnapshot())
                .set(InternalSalesOrderEntity::getRegionCode, command.regionCode())
                .set(InternalSalesOrderEntity::getOwnerSalesUserId, command.ownerSalesUserId())
                .set(InternalSalesOrderEntity::getOwnerSalesName, command.ownerSalesName())
                .set(InternalSalesOrderEntity::getOwnerEmployeeCode, command.ownerEmployeeCode())
                .set(InternalSalesOrderEntity::getOwnerEmployeeNameSnapshot, command.ownerEmployeeNameSnapshot())
                .set(InternalSalesOrderEntity::getOrderDate, local(command.orderDate()))
                .set(InternalSalesOrderEntity::getOrderStatusCode, orderStatusCode)
                .set(InternalSalesOrderEntity::getOrderTypeCode, command.orderTypeCode())
                .set(InternalSalesOrderEntity::getPaymentMethodCode, command.paymentMethodCode())
                .set(InternalSalesOrderEntity::getPaymentVoucherKeysJson, json(command.paymentVoucherKeys()))
                .set(InternalSalesOrderEntity::getPaymentStatusCode, paymentStatusCode)
                .set(InternalSalesOrderEntity::getTotalQuantity, command.totalQuantity())
                .set(InternalSalesOrderEntity::getOriginalAmount, command.originalAmount())
                .set(InternalSalesOrderEntity::getDiscountRate, command.discountRate())
                .set(InternalSalesOrderEntity::getDiscountAmount, command.discountAmount())
                .set(InternalSalesOrderEntity::getPayableAmount, payableAmount)
                .set(InternalSalesOrderEntity::getUnpaidAmount, unpaidAmount)
                .set(InternalSalesOrderEntity::getSourceUnpaidAmount, sourceUnpaidAmount)
                .set(InternalSalesOrderEntity::getRemark, command.remark())
                .set(InternalSalesOrderEntity::getRevision, command.revision() + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getSourceSystemCode, existing.getSourceSystemCode())
                .eq(InternalSalesOrderEntity::getSourceOrderNo, existing.getSourceOrderNo())
                .eq(InternalSalesOrderEntity::getRevision, command.revision())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        if (!command.lines().isEmpty() || lines(tenantId, id).isEmpty()) {
            logicDeleteLines(tenantId, id, actorId, now);
            insertLines(tenantId, id, command.lines(), actorId, now);
        }
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView updateSourceStatus(
            String tenantId, Long id, String sourceStatusCode, int revision, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (Objects.equals(existing.getSourceStatusCode(), sourceStatusCode)) {
            return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getSourceStatusCode, sourceStatusCode)
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView updateSourceProjection(
            String tenantId, Long id, SalesOrderSourceProjectionWrite command, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (sourceProjectionSame(existing, command)) {
            return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getSourceStatusCode, command.sourceStatusCode())
                .set(InternalSalesOrderEntity::getSourceCreatorId, command.sourceCreatorId())
                .set(InternalSalesOrderEntity::getSourceCreatorStaffCode, command.sourceCreatorStaffCode())
                .set(InternalSalesOrderEntity::getSourceCreatorName, command.sourceCreatorName())
                .set(InternalSalesOrderEntity::getOwnerSalesUserId, command.ownerSalesUserId())
                .set(InternalSalesOrderEntity::getOwnerSalesName, command.ownerSalesName())
                .set(InternalSalesOrderEntity::getOwnerEmployeeCode, command.ownerEmployeeCode())
                .set(InternalSalesOrderEntity::getOwnerEmployeeNameSnapshot, command.ownerEmployeeNameSnapshot())
                .set(InternalSalesOrderEntity::getRevision, command.revision() + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, command.revision())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView submit(String tenantId, Long id, int revision, String actorId) {
        requireDraft(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.SUBMITTED.code())
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.DRAFT.code())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView cancel(String tenantId, Long id, int revision, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        if (SalesOrderStatus.CANCELLED.code().equals(existing.getOrderStatusCode())) {
            throw conflict("销售订单已取消");
        }
        if (!SalesOrderOutboundStatus.PENDING.code().equals(existing.getOutboundStatusCode())) {
            throw conflict("销售订单已出库，不能取消");
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.CANCELLED.code())
                .set(InternalSalesOrderEntity::getPaymentStatusCode, SalesOrderPaymentStatus.CANCELLED.code())
                .set(InternalSalesOrderEntity::getUnpaidAmount, ZERO)
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView cancelBySource(String tenantId, Long id, int revision, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        if (SalesOrderStatus.CANCELLED.code().equals(existing.getOrderStatusCode())
                && SalesOrderPaymentStatus.CANCELLED.code().equals(existing.getPaymentStatusCode())
                && ZERO.compareTo(existing.getUnpaidAmount() == null ? ZERO : existing.getUnpaidAmount()) == 0) {
            return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        }
        if (existing.getSourceSystemCode() == null || existing.getSourceSystemCode().isBlank()) {
            throw conflict("只有外部来源销售订单允许按来源取消");
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.CANCELLED.code())
                .set(InternalSalesOrderEntity::getPaymentStatusCode, SalesOrderPaymentStatus.CANCELLED.code())
                .set(InternalSalesOrderEntity::getUnpaidAmount, ZERO)
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailView confirmOutbound(
            String tenantId, Long id, int revision, Instant shipmentTime, String actorId) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
        if (!SalesOrderStatus.SUBMITTED.code().equals(existing.getOrderStatusCode())) {
            throw conflict("只有已提交销售订单才能确认出库");
        }
        if (!SalesOrderOutboundStatus.PENDING.code().equals(existing.getOutboundStatusCode())) {
            throw conflict("销售订单出库状态已变化，请刷新后重试");
        }
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getOutboundStatusCode, SalesOrderOutboundStatus.OUT_CONFIRMED.code())
                .set(InternalSalesOrderEntity::getShipmentTime, local(firstNonNull(shipmentTime, instant(existing.getShipmentTime()), instant(now))))
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.SUBMITTED.code())
                .eq(InternalSalesOrderEntity::getOutboundStatusCode, SalesOrderOutboundStatus.PENDING.code())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        return salesOrder(tenantId, id).orElseThrow(() -> notFound("销售订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireDraft(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getDeleted, 1)
                .set(InternalSalesOrderEntity::getRevision, revision + 1)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getRevision, revision)
                .eq(InternalSalesOrderEntity::getOrderStatusCode, SalesOrderStatus.DRAFT.code())
                .eq(InternalSalesOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售订单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
    }

    private InternalSalesOrderEntity requireDraft(String tenantId, Long id) {
        InternalSalesOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (!SalesOrderStatus.DRAFT.code().equals(existing.getOrderStatusCode())) {
            throw conflict("只有草稿销售订单允许编辑或删除");
        }
        return existing;
    }

    private Optional<InternalSalesOrderEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalSalesOrderEntity>lambdaQuery()
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, id)
                .eq(InternalSalesOrderEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalSalesOrderEntity> query(String tenantId, SalesOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSalesOrderEntity> query = Wrappers.<InternalSalesOrderEntity>lambdaQuery()
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getDeleted, 0);
        if (criteria.orderNo() != null) query.like(InternalSalesOrderEntity::getOrderNo, criteria.orderNo());
        if (criteria.sourceOrderNo() != null) {
            query.like(InternalSalesOrderEntity::getSourceOrderNo, criteria.sourceOrderNo());
        }
        if (criteria.sourceStatusCode() != null) {
            query.eq(InternalSalesOrderEntity::getSourceStatusCode, criteria.sourceStatusCode());
        }
        if (criteria.dataQualityStatusCode() != null) {
            query.eq(InternalSalesOrderEntity::getDataQualityStatusCode, criteria.dataQualityStatusCode());
        }
        if (criteria.customerName() != null) {
            query.like(InternalSalesOrderEntity::getCustomerNameSnapshot, criteria.customerName());
        }
        if (criteria.contactPhone() != null) {
            query.like(InternalSalesOrderEntity::getContactPhoneSnapshot, criteria.contactPhone());
        }
        if (criteria.regionCode() != null) query.eq(InternalSalesOrderEntity::getRegionCode, criteria.regionCode());
        if (criteria.ownerEmployeeCode() != null) {
            query.eq(InternalSalesOrderEntity::getOwnerEmployeeCode, criteria.ownerEmployeeCode());
        } else if (criteria.ownerSalesUserId() != null) {
            query.eq(InternalSalesOrderEntity::getOwnerSalesUserId, criteria.ownerSalesUserId());
        }
        if (criteria.orderStatusCode() != null) {
            query.eq(InternalSalesOrderEntity::getOrderStatusCode, criteria.orderStatusCode());
        }
        if (criteria.paymentStatusCode() != null) {
            query.eq(InternalSalesOrderEntity::getPaymentStatusCode, criteria.paymentStatusCode());
        }
        if (criteria.outboundStatusCode() != null) {
            query.eq(InternalSalesOrderEntity::getOutboundStatusCode, criteria.outboundStatusCode());
        }
        if (criteria.orderDateFrom() != null) {
            query.ge(InternalSalesOrderEntity::getOrderDate, local(criteria.orderDateFrom()));
        }
        if (criteria.orderDateTo() != null) {
            query.le(InternalSalesOrderEntity::getOrderDate, local(criteria.orderDateTo()));
        }
        if (hasLineCriteria(criteria)) {
            List<Long> orderIds = matchingLineOrderIds(tenantId, criteria);
            if (orderIds.isEmpty()) {
                query.eq(InternalSalesOrderEntity::getId, -1L);
            } else {
                query.in(InternalSalesOrderEntity::getId, orderIds);
            }
        }
        return query;
    }

    private List<Long> matchingLineOrderIds(String tenantId, SalesOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSalesOrderLineEntity> query = Wrappers.<InternalSalesOrderLineEntity>lambdaQuery()
                .select(InternalSalesOrderLineEntity::getOrderId)
                .eq(InternalSalesOrderLineEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderLineEntity::getDeleted, 0);
        if (criteria.productId() != null) {
            query.eq(InternalSalesOrderLineEntity::getProductId, criteria.productId());
        }
        if (criteria.productVariantId() != null) {
            query.eq(InternalSalesOrderLineEntity::getProductVariantId, criteria.productVariantId());
        }
        if (criteria.productCodeSnapshot() != null) {
            query.eq(InternalSalesOrderLineEntity::getProductCodeSnapshot, criteria.productCodeSnapshot());
        }
        if (criteria.skuCodeSnapshot() != null) {
            query.eq(InternalSalesOrderLineEntity::getSkuCodeSnapshot, criteria.skuCodeSnapshot());
        }
        if (criteria.productNameSnapshot() != null) {
            query.like(InternalSalesOrderLineEntity::getProductNameSnapshot, criteria.productNameSnapshot());
        }
        if (criteria.specificationSnapshot() != null) {
            query.like(InternalSalesOrderLineEntity::getSpecificationSnapshot, criteria.specificationSnapshot());
        }
        return lineMapper.selectList(query)
                .stream()
                .map(InternalSalesOrderLineEntity::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static boolean hasLineCriteria(SalesOrderSearchCriteria criteria) {
        return criteria.productId() != null
                || criteria.productVariantId() != null
                || criteria.productCodeSnapshot() != null
                || criteria.skuCodeSnapshot() != null
                || criteria.productNameSnapshot() != null
                || criteria.specificationSnapshot() != null;
    }

    private InternalSalesOrderEntity orderEntity(
            String tenantId, String orderNo, SalesOrderWrite command, String actorId, LocalDateTime now) {
        InternalSalesOrderEntity entity = new InternalSalesOrderEntity();
        entity.setTenantId(tenantId);
        entity.setOrderNo(orderNo);
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setSourceOrderNo(command.sourceOrderNo());
        entity.setSourceStatusCode(command.sourceStatusCode());
        entity.setSourceCreatorId(command.sourceCreatorId());
        entity.setSourceCreatorStaffCode(command.sourceCreatorStaffCode());
        entity.setSourceCreatorName(command.sourceCreatorName());
        entity.setDataQualityStatusCode(command.dataQualityStatusCode());
        entity.setDataQualityMessage(command.dataQualityMessage());
        entity.setCustomerId(command.customerId());
        entity.setCustomerCodeSnapshot(command.customerCodeSnapshot());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setContactNameSnapshot(command.contactNameSnapshot());
        entity.setContactPhoneSnapshot(command.contactPhoneSnapshot());
        entity.setRegionCode(command.regionCode());
        entity.setOwnerSalesUserId(command.ownerSalesUserId());
        entity.setOwnerSalesName(command.ownerSalesName());
        entity.setOwnerEmployeeCode(command.ownerEmployeeCode());
        entity.setOwnerEmployeeNameSnapshot(command.ownerEmployeeNameSnapshot());
        entity.setOrderDate(local(command.orderDate()));
        entity.setOrderStatusCode(command.orderStatusCode());
        entity.setOrderTypeCode(command.orderTypeCode());
        entity.setPaymentMethodCode(command.paymentMethodCode());
        entity.setPaymentVoucherKeysJson(json(command.paymentVoucherKeys()));
        entity.setPaymentStatusCode(SalesOrderPaymentStatus.UNPAID.code());
        entity.setOutboundStatusCode(SalesOrderOutboundStatus.PENDING.code());
        entity.setTotalQuantity(command.totalQuantity());
        entity.setOriginalAmount(command.originalAmount());
        entity.setDiscountRate(command.discountRate());
        entity.setDiscountAmount(command.discountAmount());
        entity.setPayableAmount(command.payableAmount());
        entity.setPaidAmount(java.math.BigDecimal.ZERO);
        entity.setUnpaidAmount(initialUnpaidAmount(command));
        entity.setSourceUnpaidAmount(command.sourceUnpaidAmount());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private void insertLines(String tenantId, Long orderId, List<SalesOrderLineWrite> lines,
                             String actorId, LocalDateTime now) {
        for (SalesOrderLineWrite item : lines) {
            InternalSalesOrderLineEntity entity = new InternalSalesOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setOrderId(orderId);
            entity.setLineNo(item.lineNo());
            entity.setProductId(item.productId());
            entity.setProductVariantId(item.productVariantId());
            entity.setProductCodeSnapshot(item.productCodeSnapshot());
            entity.setSkuCodeSnapshot(item.skuCodeSnapshot());
            entity.setProductNameSnapshot(item.productNameSnapshot());
            entity.setSpecificationSnapshot(item.specificationSnapshot());
            entity.setUnitCode(item.unitCode());
            entity.setQuantity(item.quantity());
            entity.setUnitPrice(item.unitPrice());
            entity.setDiscountRate(item.discountRate());
            entity.setDiscountAmount(item.discountAmount());
            entity.setLineAmount(item.lineAmount());
            entity.setRemark(item.remark());
            entity.setRevision(1);
            entity.setCreatedBy(auditActor(actorId));
            entity.setCreatedTime(now);
            entity.setUpdatedBy(auditActor(actorId));
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            lineMapper.insert(entity);
        }
    }

    private void logicDeleteLines(String tenantId, Long orderId, String actorId, LocalDateTime now) {
        lineMapper.update(null, Wrappers.<InternalSalesOrderLineEntity>lambdaUpdate()
                .set(InternalSalesOrderLineEntity::getDeleted, 1)
                .setSql("revision = revision + 1")
                .set(InternalSalesOrderLineEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderLineEntity::getUpdatedTime, now)
                .eq(InternalSalesOrderLineEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderLineEntity::getOrderId, orderId)
                .eq(InternalSalesOrderLineEntity::getDeleted, 0));
    }

    private List<SalesOrderLineView> lines(String tenantId, Long orderId) {
        return lineMapper.selectList(Wrappers.<InternalSalesOrderLineEntity>lambdaQuery()
                        .eq(InternalSalesOrderLineEntity::getTenantId, tenantId)
                        .eq(InternalSalesOrderLineEntity::getOrderId, orderId)
                        .eq(InternalSalesOrderLineEntity::getDeleted, 0)
                        .orderByAsc(InternalSalesOrderLineEntity::getLineNo))
                .stream()
                .map(MybatisPlusSalesOrderRepository::line)
                .toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Map<Long, String> shipmentStatuses(String tenantId, List<InternalSalesOrderEntity> orders) {
        if (orders == null || orders.isEmpty()) return Map.of();
        List<Long> orderIds = orders.stream()
                .map(InternalSalesOrderEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (orderIds.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        for (InternalSalesShipmentEntity shipment : shipmentMapper.selectList(
                Wrappers.<InternalSalesShipmentEntity>lambdaQuery()
                        .select(InternalSalesShipmentEntity::getSalesOrderId,
                                InternalSalesShipmentEntity::getShipmentStatusCode)
                        .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                        .in(InternalSalesShipmentEntity::getSalesOrderId, orderIds)
                        .ne(InternalSalesShipmentEntity::getShipmentStatusCode, SalesShipmentStatus.CANCELLED.code())
                        .eq(InternalSalesShipmentEntity::getDeleted, 0))) {
            Long orderId = shipment.getSalesOrderId();
            String candidate = shipment.getShipmentStatusCode();
            String current = result.get(orderId);
            if (shipmentStatusRank(candidate) > shipmentStatusRank(current)) {
                result.put(orderId, candidate);
            }
        }
        return result;
    }

    private String shipmentStatus(String tenantId, Long orderId) {
        if (orderId == null) return null;
        InternalSalesOrderEntity order = new InternalSalesOrderEntity();
        order.setId(orderId);
        return shipmentStatuses(tenantId, List.of(order)).get(orderId);
    }

    private static int shipmentStatusRank(String statusCode) {
        if (SalesShipmentStatus.SIGNED.code().equals(statusCode)) return 3;
        if (SalesShipmentStatus.SHIPPED.code().equals(statusCode)) return 2;
        if (SalesShipmentStatus.CREATED.code().equals(statusCode)) return 1;
        return 0;
    }

    private static SalesOrderSummaryView summary(InternalSalesOrderEntity entity, String shipmentStatusCode) {
        return new SalesOrderSummaryView(entity.getId(), entity.getOrderNo(), entity.getSourceSystemCode(),
                entity.getSourceOrderNo(), entity.getSourceStatusCode(), entity.getSourceCreatorId(),
                entity.getSourceCreatorStaffCode(), entity.getSourceCreatorName(),
                entity.getDataQualityStatusCode(), entity.getDataQualityMessage(), entity.getCustomerId(),
                entity.getCustomerNameSnapshot(), entity.getContactPhoneSnapshot(), entity.getRegionCode(),
                null,
                entity.getOwnerSalesUserId(), entity.getOwnerSalesName(),
                entity.getOwnerEmployeeCode(), entity.getOwnerEmployeeNameSnapshot(),
                instant(entity.getOrderDate()),
                instant(entity.getPaymentTime()), instant(entity.getShipmentTime()),
                shipmentStatusCode,
                entity.getOrderStatusCode(), entity.getPaymentStatusCode(), entity.getOutboundStatusCode(),
                entity.getTotalQuantity(), entity.getOriginalAmount(), entity.getDiscountAmount(),
                entity.getPayableAmount(), entity.getPaidAmount(),
                entity.getUnpaidAmount(), entity.getRevision(), instant(entity.getUpdatedTime()));
    }

    private static SalesOrderDetailView detail(
            InternalSalesOrderEntity entity, List<SalesOrderLineView> lines, String shipmentStatusCode) {
        return new SalesOrderDetailView(entity.getId(), entity.getOrderNo(), entity.getSourceSystemCode(),
                entity.getSourceOrderNo(), entity.getSourceStatusCode(), entity.getSourceCreatorId(),
                entity.getSourceCreatorStaffCode(), entity.getSourceCreatorName(),
                entity.getDataQualityStatusCode(), entity.getDataQualityMessage(), entity.getCustomerId(),
                entity.getCustomerCodeSnapshot(), entity.getCustomerNameSnapshot(), entity.getContactNameSnapshot(),
                entity.getContactPhoneSnapshot(), entity.getRegionCode(), null, entity.getOwnerSalesUserId(),
                entity.getOwnerSalesName(), entity.getOwnerEmployeeCode(),
                entity.getOwnerEmployeeNameSnapshot(), instant(entity.getOrderDate()),
                instant(entity.getPaymentTime()), instant(entity.getShipmentTime()),
                shipmentStatusCode,
                entity.getOrderStatusCode(), entity.getOrderTypeCode(), entity.getPaymentMethodCode(),
                entity.getPaymentStatusCode(),
                entity.getOutboundStatusCode(), entity.getTotalQuantity(), entity.getOriginalAmount(),
                entity.getDiscountRate(), entity.getDiscountAmount(), entity.getPayableAmount(),
                entity.getPaidAmount(), entity.getUnpaidAmount(), entity.getRemark(), entity.getRevision(),
                entity.getCreatedBy(), instant(entity.getCreatedTime()), entity.getUpdatedBy(),
                instant(entity.getUpdatedTime()), parseStrings(entity.getPaymentVoucherKeysJson()),
                List.of(), lines);
    }

    private static SalesOrderLineView line(InternalSalesOrderLineEntity entity) {
        return new SalesOrderLineView(entity.getId(), entity.getLineNo(), entity.getProductId(),
                entity.getProductVariantId(), entity.getProductCodeSnapshot(), entity.getSkuCodeSnapshot(),
                entity.getProductNameSnapshot(), entity.getSpecificationSnapshot(), entity.getUnitCode(),
                entity.getQuantity(), entity.getUnitPrice(), entity.getDiscountRate(), entity.getDiscountAmount(),
                entity.getLineAmount(), entity.getRemark());
    }

    private static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Order销售订单付款凭证JSON序列化失败", exception);
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
            throw new IllegalStateException("Order销售订单付款凭证JSON反序列化失败", exception);
        }
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static BigDecimal totalDiscountAmount(InternalSalesOrderEntity entity) {
        BigDecimal derived = amount(entity.getOriginalAmount()).subtract(amount(entity.getPayableAmount()));
        if (derived.compareTo(ZERO) > 0) return derived;
        return amount(entity.getDiscountAmount());
    }

    private static BigDecimal initialUnpaidAmount(SalesOrderWrite command) {
        if (command == null) return ZERO;
        return unpaidAmount(command.sourceSystemCode(), false,
                amount(command.payableAmount()), ZERO, command.sourceUnpaidAmount());
    }

    private static BigDecimal unpaidAmount(String sourceSystemCode, boolean cancelled,
                                           BigDecimal payableAmount, BigDecimal paidAmount,
                                           BigDecimal sourceUnpaidAmount) {
        if (cancelled) return ZERO;
        if (SOURCE_SYSTEM_FEISHU.equalsIgnoreCase(sourceSystemCode) && sourceUnpaidAmount != null) {
            return sourceUnpaidAmount;
        }
        BigDecimal unpaidAmount = amount(payableAmount).subtract(amount(paidAmount));
        return unpaidAmount.compareTo(ZERO) < 0 ? ZERO : unpaidAmount;
    }

    private static String paymentStatus(BigDecimal payableAmount, BigDecimal paidAmount) {
        BigDecimal payable = amount(payableAmount);
        BigDecimal paid = amount(paidAmount);
        if (paid.compareTo(ZERO) <= 0) return SalesOrderPaymentStatus.UNPAID.code();
        if (paid.compareTo(payable) >= 0) return SalesOrderPaymentStatus.PAID.code();
        return SalesOrderPaymentStatus.PARTIAL_PAID.code();
    }

    private static String orderStatusAfterExternalProjection(
            InternalSalesOrderEntity existing, SalesOrderWrite command) {
        if (SalesOrderStatus.CANCELLED.code().equals(existing.getOrderStatusCode())) {
            return SalesOrderStatus.CANCELLED.code();
        }
        if (SOURCE_SYSTEM_FEISHU.equalsIgnoreCase(existing.getSourceSystemCode())
                && DATA_QUALITY_NEEDS_REVIEW.equals(command.dataQualityStatusCode())) {
            return SalesOrderStatus.DRAFT.code();
        }
        if (SalesOrderStatus.DRAFT.code().equals(existing.getOrderStatusCode())) {
            return command.orderStatusCode();
        }
        return existing.getOrderStatusCode();
    }

    private static Instant firstNonNull(Instant first, Instant second, Instant third) {
        if (first != null) return first;
        if (second != null) return second;
        return third;
    }

    private static String auditActor(String actorId) {
        return actorId == null || actorId.isBlank() ? SYSTEM_ACTOR : actorId;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private static boolean sourceProjectionSame(
            InternalSalesOrderEntity existing, SalesOrderSourceProjectionWrite command) {
        return Objects.equals(existing.getSourceStatusCode(), command.sourceStatusCode())
                && Objects.equals(existing.getSourceCreatorId(), command.sourceCreatorId())
                && Objects.equals(existing.getSourceCreatorStaffCode(), command.sourceCreatorStaffCode())
                && Objects.equals(existing.getSourceCreatorName(), command.sourceCreatorName())
                && Objects.equals(existing.getOwnerSalesUserId(), command.ownerSalesUserId())
                && Objects.equals(existing.getOwnerSalesName(), command.ownerSalesName())
                && Objects.equals(existing.getOwnerEmployeeCode(), command.ownerEmployeeCode())
                && Objects.equals(existing.getOwnerEmployeeNameSnapshot(), command.ownerEmployeeNameSnapshot());
    }
}

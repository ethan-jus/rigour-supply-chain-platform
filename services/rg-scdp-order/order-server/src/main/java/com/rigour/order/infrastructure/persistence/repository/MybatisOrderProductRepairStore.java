package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import com.rigour.order.application.port.out.OrderProductRepairStore;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderLineEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderProductRepairLineEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderProductRepairPreviewEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderProductRepairLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderProductRepairPreviewMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 受控历史修复仓储；锁与CAS共同保护预览，SQL白名单仅允许商品引用和审计字段。 */
@Repository
public class MybatisOrderProductRepairStore implements OrderProductRepairStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final InternalSalesOrderMapper orders;
    private final InternalSalesOrderLineMapper lines;
    private final OrderProductRepairPreviewMapper previews;
    private final OrderProductRepairLineMapper repairs;
    private final Clock clock;
    private final OrderDataScope scopes;
    private final OrderAttributionWriter attribution;

    public MybatisOrderProductRepairStore(InternalSalesOrderMapper orders, InternalSalesOrderLineMapper lines,
                                         OrderProductRepairPreviewMapper previews, OrderProductRepairLineMapper repairs, Clock clock, OrderDataScope scopes, OrderAttributionWriter attribution) {
        this.orders = orders;
        this.lines = lines;
        this.previews = previews;
        this.repairs = repairs;
        this.clock = clock;
        this.scopes=scopes;this.attribution=attribution;
    }

    @Override public Optional<Snapshot> snapshot(String tenantId, Long orderId) {
        return snapshot(tenantId, orderId, false);
    }

    private Optional<Snapshot> snapshot(String tenantId, Long orderId, boolean lock) {
        scopes.requireOrder(tenantId,orderId,"order:history:repair");
        var order = orders.selectOne(Wrappers.<InternalSalesOrderEntity>lambdaQuery()
                .eq(InternalSalesOrderEntity::getTenantId, tenantId).eq(InternalSalesOrderEntity::getId, orderId)
                .eq(InternalSalesOrderEntity::getDeleted, 0).last(lock ? "FOR UPDATE" : ""));
        if (order == null) return Optional.empty();
        var rows = lines.selectList(Wrappers.<InternalSalesOrderLineEntity>lambdaQuery()
                .eq(InternalSalesOrderLineEntity::getTenantId, tenantId).eq(InternalSalesOrderLineEntity::getOrderId, orderId)
                .eq(InternalSalesOrderLineEntity::getDeleted, 0).orderByAsc(InternalSalesOrderLineEntity::getId)
                .last(lock ? "FOR UPDATE" : ""));
        return Optional.of(new Snapshot(order.getId(), order.getOrderNo(), order.getSourceOrderNo(),
                order.getSourceSystemCode(), order.getOrderStatusCode(), order.getOwnerSalesUserId(),
                order.getRevision(), order.getTotalQuantity(), fingerprint(order, rows),
                rows.stream().map(row -> new Line(view(row), row.getRevision())).toList()));
    }

    @Override public void save(String tenantId, Saved saved) {
        var preview = saved.preview();
        scopes.requireOrder(tenantId,preview.orderId(),"order:history:repair");
        var entity = new OrderProductRepairPreviewEntity();
        entity.previewId = preview.previewId();
        entity.tenantId = tenantId;
        entity.orderId = preview.orderId();
        entity.actorId = preview.createdBy();
        entity.status = preview.status();
        entity.fingerprint = saved.fingerprint();
        entity.previewJson = JSON.writeValueAsString(preview);
        entity.createdAt = local(preview.createdAt());
        entity.expiresAt = local(preview.expiresAt());
        previews.insert(entity);
    }

    @Override public Optional<Saved> preview(String tenantId, Long orderId, String previewId) {
        scopes.requireOrder(tenantId,orderId,"order:history:repair");
        return Optional.ofNullable(previewEntity(tenantId, orderId, previewId, false)).map(MybatisOrderProductRepairStore::saved);
    }

    private OrderProductRepairPreviewEntity previewEntity(String tenantId, Long orderId, String previewId, boolean lock) {
        return previews.selectOne(Wrappers.<OrderProductRepairPreviewEntity>query()
                .eq("tenant_id", tenantId).eq("order_id", orderId).eq("preview_id", previewId)
                .last(lock ? "FOR UPDATE" : ""));
    }

    @Override public List<Applied> recentRepairs(String tenantId, Long orderId) {
        scopes.requireOrder(tenantId,orderId,"order:history:repair");
        return previews.selectList(Wrappers.<OrderProductRepairPreviewEntity>query()
                .eq("tenant_id", tenantId).eq("order_id", orderId).eq("status", "APPLIED")
                .orderByDesc("applied_at").orderByDesc("preview_id").last("LIMIT 50"))
                .stream().map(row -> JSON.readValue(row.appliedJson, Applied.class)).toList();
    }

    @Override public Optional<Applied> currentRepair(String tenantId, Long orderId, Long lineId, Integer lineRevision) {
        scopes.requireOrder(tenantId,orderId,"order:history:repair");
        return repairs.currentEvidence(tenantId, null, lineId - 1, 1, scopes.mapperPredicate("order:read","o.")).stream()
                .filter(row -> row.orderId.equals(orderId) && row.lineId.equals(lineId) && row.lineRevision.equals(lineRevision))
                .findFirst()
                .map(row -> JSON.readValue(row.appliedJson, Applied.class));
    }

    @Override public List<Evidence> evidence(String tenantId, String ownerUserId, long afterLineId, int limit) {
        return repairs.currentEvidence(tenantId, ownerUserId, afterLineId, limit, scopes.mapperPredicate("order:read","o.")).stream().map(row -> {
            var proposed = JSON.readValue(row.appliedJson, Applied.class).lines().getFirst().proposed();
            return new Evidence(row.orderId, row.sourceOrderNo, row.lineId, row.lineRevision,
                    row.productId, row.productVariantId, row.productCode, row.skuCode,
                    proposed.productName(), proposed.specification(), row.sourceNamespace, row.sourceCaptureRef,
                    row.sourceProductRecordId, row.sourceProductCode, row.sourceEvidence, row.sourceIdentityStatus,
                    row.storedUnitCode, row.transactionQuantity, row.historicalTransactionUnitCode,
                    row.transactionUnitEvidence, row.transactionUnitStatus, row.standardQuantity, row.standardUnitCode,
                    row.conversionFactor, row.conversionEvidence, row.bindingEvidence, row.previewId,
                    row.appliedBy, row.appliedAt.toInstant(ZoneOffset.UTC));
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Applied apply(String tenantId, Long orderId, String previewId, String actorId, Instant now) {
        // 与通用订单更新保持先锁订单、再锁明细的顺序；重试在同一预览锁内返回已保存结果。
        var current = snapshot(tenantId, orderId, true).orElseThrow(() -> conflict("订单已删除"));
        attribution.requireNoExecution(tenantId,orderId);
        var entity = previewEntity(tenantId, orderId, previewId, true);
        now = clock.instant();
        if (entity == null || !actorId.equals(entity.actorId)) throw conflict("预览不存在或操作员不匹配");
        var saved = saved(entity);
        if (saved.applied() != null) return saved.applied();
        var preview = saved.preview();
        if (!"READY".equals(entity.status) || !now.isBefore(preview.expiresAt())
                || !saved.fingerprint().equals(current.fingerprint())
                || !preview.expectedRevision().equals(current.revision())
                || !"FEISHU".equals(current.sourceSystemCode())
                || "CANCELLED".equals(current.orderStatusCode())
                || current.totalQuantity() == null || current.totalQuantity().signum() <= 0) {
            throw conflict("订单或预览已变化，未应用修复");
        }
        var result = new Applied(previewId, orderId, current.revision() + 1, actorId, now, preview.lines());
        if (orders.update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .eq(InternalSalesOrderEntity::getTenantId, tenantId).eq(InternalSalesOrderEntity::getId, orderId)
                .eq(InternalSalesOrderEntity::getDeleted, 0).eq(InternalSalesOrderEntity::getRevision, current.revision())
                .set(InternalSalesOrderEntity::getRevision, result.revision())
                .set(InternalSalesOrderEntity::getUpdatedBy, actorId).set(InternalSalesOrderEntity::getUpdatedTime, local(now))) != 1) {
            throw conflict("订单版本冲突");
        }
        for (var line : preview.lines()) {
            if (!line.blockers().isEmpty() || line.proposed() == null || line.original().quantity().signum() <= 0) {
                throw conflict("预览含不可修复行");
            }
            if (lines.update(null, Wrappers.<InternalSalesOrderLineEntity>lambdaUpdate()
                    .eq(InternalSalesOrderLineEntity::getTenantId, tenantId).eq(InternalSalesOrderLineEntity::getOrderId, orderId)
                    .eq(InternalSalesOrderLineEntity::getId, line.original().id()).eq(InternalSalesOrderLineEntity::getDeleted, 0)
                    .eq(InternalSalesOrderLineEntity::getRevision, line.lineRevision())
                    .gt(InternalSalesOrderLineEntity::getQuantity, 0)
                    .set(InternalSalesOrderLineEntity::getProductId, line.proposed().productId())
                    .set(InternalSalesOrderLineEntity::getProductVariantId, line.proposed().productVariantId())
                    .set(InternalSalesOrderLineEntity::getRevision, line.lineRevision() + 1)
                    .set(InternalSalesOrderLineEntity::getUpdatedBy, actorId)
                    .set(InternalSalesOrderLineEntity::getUpdatedTime, local(now))) != 1) throw conflict("明细版本冲突");
            repairs.insert(audit(tenantId, result, line));
        }
        if (previews.update(null, Wrappers.<OrderProductRepairPreviewEntity>update()
                .eq("tenant_id", tenantId).eq("order_id", orderId).eq("preview_id", previewId)
                .eq("actor_id", actorId).eq("status", "READY")
                .set("status", "APPLIED").set("applied_at", local(now))
                .set("applied_json", JSON.writeValueAsString(result))) != 1) throw conflict("预览状态冲突");
        return result;
    }

    private static OrderProductRepairLineEntity audit(String tenant, Applied result, LinePreview line) {
        var row = new OrderProductRepairLineEntity();
        row.tenantId = tenant;
        row.orderId = result.orderId();
        row.lineId = line.original().id();
        row.previewId = result.previewId();
        row.lineRevision = line.lineRevision() + 1;
        row.productId = line.proposed().productId();
        row.productVariantId = line.proposed().productVariantId();
        row.productCode = line.proposed().productCode();
        row.skuCode = line.proposed().skuCode();
        row.storedUnitCode = line.original().unitCode();
        row.historicalTransactionUnitCode = line.requested().historicalTransactionUnitCode();
        row.transactionUnitEvidence = line.requested().transactionUnitEvidence();
        row.transactionUnitStatus = line.transactionUnitStatus();
        row.transactionQuantity = line.original().quantity();
        row.standardUnitCode = line.requested().standardUnitCode();
        row.standardQuantity = line.requested().standardQuantity();
        row.conversionFactor = line.requested().conversionFactor();
        row.bindingEvidence = line.requested().bindingEvidence();
        row.sourceNamespace = line.requested().sourceNamespace();
        row.sourceCaptureRef = line.requested().sourceCaptureRef();
        row.sourceProductRecordId = line.requested().sourceProductRecordId();
        row.sourceProductCode = line.requested().sourceProductCode();
        row.sourceEvidence = line.requested().sourceEvidence();
        row.sourceIdentityStatus = line.sourceIdentityStatus();
        row.conversionEvidence = line.requested().conversionEvidence();
        row.originalJson = JSON.writeValueAsString(line.original());
        row.appliedJson = JSON.writeValueAsString(new Applied(result.previewId(), result.orderId(), result.revision(),
                result.appliedBy(), result.appliedAt(), List.of(line)));
        row.appliedBy = result.appliedBy();
        row.appliedAt = local(result.appliedAt());
        return row;
    }

    private static Saved saved(OrderProductRepairPreviewEntity entity) {
        return new Saved(JSON.readValue(entity.previewJson, Preview.class), entity.fingerprint,
                entity.appliedJson == null ? null : JSON.readValue(entity.appliedJson, Applied.class));
    }
    private static String fingerprint(InternalSalesOrderEntity order, List<InternalSalesOrderLineEntity> lines) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(List.of(order, lines)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static SalesOrderLineView view(InternalSalesOrderLineEntity row) {
        return new SalesOrderLineView(row.getId(), row.getLineNo(), row.getProductId(), row.getProductVariantId(),
                row.getProductCodeSnapshot(), row.getSkuCodeSnapshot(), row.getProductNameSnapshot(),
                row.getSpecificationSnapshot(), row.getUnitCode(), row.getQuantity(), row.getUnitPrice(),
                row.getDiscountRate(), row.getDiscountAmount(), row.getLineAmount(), row.getRemark());
    }
    private static LocalDateTime local(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message, List.of()); }
}

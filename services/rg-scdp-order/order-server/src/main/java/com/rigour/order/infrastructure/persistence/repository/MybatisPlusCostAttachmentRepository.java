package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;
import com.rigour.order.application.port.out.CostAttachmentStore;
import com.rigour.order.infrastructure.persistence.entity.CostAttachmentEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;
import com.rigour.order.infrastructure.persistence.mapper.CostAttachmentMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 与费用保存共用租户锁；附件元数据、版本与审计由调用方的事务一起提交。 */
@Repository
public class MybatisPlusCostAttachmentRepository implements CostAttachmentStore {
    private final CostAttachmentMapper attachments;
    private final OrderLogisticsCostMapper costs;

    public MybatisPlusCostAttachmentRepository(
            CostAttachmentMapper attachments, OrderLogisticsCostMapper costs) {
        this.attachments = attachments;
        this.costs = costs;
    }

    public void lock(String tenant) {
        costs.ensureGuard(tenant);
        costs.lock(tenant);
    }

    private LambdaQueryWrapper<CostAttachmentEntity> scope(String tenant, String entry) {
        return new LambdaQueryWrapper<CostAttachmentEntity>()
                .eq(CostAttachmentEntity::getTenantId, tenant)
                .eq(CostAttachmentEntity::getEntryId, entry);
    }

    public List<LogisticsCostAttachmentView> list(String tenant, String entry) {
        return attachments
                .selectList(
                        scope(tenant, entry)
                                .orderByAsc(
                                        CostAttachmentEntity::getCreatedTime,
                                        CostAttachmentEntity::getId))
                .stream()
                .map(
                        r ->
                                new LogisticsCostAttachmentView(
                                        r.getId(),
                                        r.getFileName(),
                                        r.getContentType(),
                                        r.getFileSize(),
                                        r.getCreatedBy(),
                                        r.getCreatedTime().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    public String fileKey(String tenant, String entry, String id) {
        var row = attachments.selectOne(scope(tenant, entry).eq(CostAttachmentEntity::getId, id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在", List.of());
        return row.getFileKey();
    }

    public void add(
            String tenant,
            String entry,
            int revision,
            LogisticsCostAttachmentView file,
            String key) {
        int changed =
                costs.update(
                        null,
                        new LambdaUpdateWrapper<OrderLogisticsCostEntity>()
                                .eq(OrderLogisticsCostEntity::getTenantId, tenant)
                                .eq(OrderLogisticsCostEntity::getId, entry)
                                .eq(OrderLogisticsCostEntity::getRevision, revision)
                                .eq(OrderLogisticsCostEntity::getStatusCode, "DRAFT")
                                .set(OrderLogisticsCostEntity::getUpdatedBy, file.createdBy())
                                .set(
                                        OrderLogisticsCostEntity::getUpdatedTime,
                                        LocalDateTime.now(ZoneOffset.UTC))
                                .setSql("revision=revision+1"));
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT, "费用已被修改，请刷新", List.of());
        var row = new CostAttachmentEntity();
        row.setId(file.id());
        row.setTenantId(tenant);
        row.setEntryId(entry);
        row.setFileKey(key);
        row.setFileName(file.fileName());
        row.setContentType(file.contentType());
        row.setFileSize(file.size());
        row.setCreatedBy(file.createdBy());
        row.setCreatedTime(LocalDateTime.ofInstant(file.createdTime(), ZoneOffset.UTC));
        attachments.insert(row);
    }
}
